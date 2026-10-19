package io.github.sunjirapath.ssg.extract;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Value;
import io.github.sunjirapath.ssg.scan.Ann;
import io.github.sunjirapath.ssg.scan.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * RabbitMQ and RocketMQ destinations, as topics: {@code @RabbitListener(queues)}, {@code RabbitTemplate/AmqpTemplate
 * convertAndSend/send}, {@code @RocketMQMessageListener(topic)}, {@code RocketMQTemplate} sends ({@code "topic:tag"}),
 * and the plain client's {@code new Message(topic, ...)} and {@code DefaultMQPushConsumer.subscribe(topic, tags)}.
 */
// shortcut: a RabbitMQ producer is named by its routing key and a consumer by its queue, so they link only when
// the routing key equals the queue name (the default exchange, or the common key == queue convention);
// read Binding beans (BindingBuilder.bind(q).to(x).with(key)) if fleets bind keys to differently named queues.
public final class MessagingExtractor implements Extractor {

    private static final Set<String> RABBIT = Set.of("RabbitTemplate", "AmqpTemplate", "RabbitOperations", "RabbitMessagingTemplate");
    private static final Set<String> RABBIT_SENDS = Set.of("convertAndSend", "send", "convertSendAndReceive", "sendAndReceive");
    private static final Set<String> ROCKET = Set.of("RocketMQTemplate");
    private static final Set<String> ROCKET_SENDS = Set.of("send", "convertAndSend", "syncSend", "asyncSend", "sendOneWay",
            "syncSendOrderly", "asyncSendOrderly", "sendOneWayOrderly", "sendMessageInTransaction", "syncSendDelayTimeSeconds");

    @Override
    public List<Fact> extract(Service s) {
        var facts = new ArrayList<Fact>();
        for (CompilationUnit cu : s.units()) {
            for (var c : cu.findAll(ClassOrInterfaceDeclaration.class)) listeners(s, c, facts);
            for (var m : cu.findAll(MethodDeclaration.class)) listeners(s, m, facts);
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) send(s, call, facts);
            for (ObjectCreationExpr o : cu.findAll(ObjectCreationExpr.class)) {
                // org.apache.rocketmq.common.message.Message(topic, tags, body); Spring's Message has no such constructor
                if (o.getType().getNameAsString().equals("Message") && o.getArguments().size() >= 2
                        && cu.getImports().stream().anyMatch(i -> i.getNameAsString().startsWith("org.apache.rocketmq"))) {
                    facts.add(new Fact.TopicUse(true, s.eval(o.getArgument(0)), s.evidence(o), "RocketMQ Message"));
                }
            }
        }
        return facts;
    }

    private static void listeners(Service s, NodeWithAnnotations<?> n, List<Fact> facts) {
        var ev = s.evidence((Node) n);
        Ann.find(n, "RabbitListener").ifPresent(a -> {
            var queues = Ann.attr(a, "queues", "value");
            if (queues.isPresent()) for (Value v : s.evalAll(queues.get())) facts.add(new Fact.TopicUse(false, v, ev, "RabbitListener"));
            else facts.add(new Fact.TopicUse(false, Value.unresolved(a.toString()), ev, "RabbitListener")); // bindings/queuesToDeclare
        });
        Ann.find(n, "RocketMQMessageListener").ifPresent(a -> {
            Value topic = Ann.attr(a, "topic").map(s::eval).orElse(Value.unresolved(a.toString()));
            facts.add(new Fact.TopicUse(false, topic, ev, "RocketMQMessageListener"));
        });
    }

    private static void send(Service s, MethodCallExpr call, List<Fact> facts) {
        if (call.getScope().isEmpty() || call.getArguments().isEmpty()) return;
        var scope = call.getScope().get();
        String name = call.getNameAsString();
        int args = call.getArguments().size();
        if (RABBIT_SENDS.contains(name) && args >= 2 && (Receivers.isReceiver(s, scope, RABBIT, "rabbittemplate")
                || Receivers.isReceiver(s, scope, RABBIT, "amqptemplate"))) {
            // (routingKey, msg) or (exchange, routingKey, msg, ...): with 3+ args the second is the key
            Value key = s.eval(call.getArgument(args >= 3 ? 1 : 0));
            facts.add(new Fact.TopicUse(true, key, s.evidence(call), "RabbitTemplate"));
        } else if (ROCKET_SENDS.contains(name) && args >= 2 && Receivers.isReceiver(s, scope, ROCKET, "rocketmqtemplate")) {
            facts.add(new Fact.TopicUse(true, withoutTag(s.eval(call.getArgument(0))), s.evidence(call), "RocketMQTemplate"));
        } else if (name.equals("subscribe") && args == 2 && Receivers.isReceiver(s, scope,
                Set.of("DefaultMQPushConsumer", "DefaultLitePullConsumer", "DefaultMQPullConsumer"), "mqconsumer")) {
            facts.add(new Fact.TopicUse(false, s.eval(call.getArgument(0)), s.evidence(call), "RocketMQ subscribe"));
        }
    }

    /** RocketMQ destinations are {@code topic:tag}; the tag filters within the topic. */
    static Value withoutTag(Value v) {
        int colon = v.text().indexOf(':');
        return !v.resolved() || colon < 0 ? v : new Value(v.text().substring(0, colon), v.confidence(), v.raw(), v.via());
    }
}
