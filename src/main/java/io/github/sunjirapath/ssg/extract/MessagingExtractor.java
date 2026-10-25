package io.github.sunjirapath.ssg.extract;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Value;
import io.github.sunjirapath.ssg.scan.Ann;
import io.github.sunjirapath.ssg.scan.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RabbitMQ and RocketMQ destinations, as topics: {@code @RabbitListener(queues)}, {@code RabbitTemplate/AmqpTemplate
 * convertAndSend/send}, {@code @RocketMQMessageListener(topic)}, {@code RocketMQTemplate} sends ({@code "topic:tag"}),
 * and the plain client's {@code new Message(topic, ...)} and {@code DefaultMQPushConsumer.subscribe(topic, tags)}.
 * A RabbitMQ send is named by the queue its (exchange, routing key) is bound to by a {@code @Bean Binding}, plus the
 * queue a bound queue dead-letters to; without a binding it is named by the routing key.
 */
// shortcut: bindings are read per service from bean methods only (bind(queueBean).to(exchangeBean).with(key), queues
// from new Queue/QueueBuilder, dead-letter arguments via withArgument/put/deadLetter*) and matched by exact key;
// topic/fanout wildcards, keyless bindings, `new Binding(..)`, Map.of arguments and a binding declared in another
// service than the producer fall back to the routing key; upgrade when a fleet needs them.
public final class MessagingExtractor implements Extractor {

    private static final Set<String> RABBIT = Set.of("RabbitTemplate", "AmqpTemplate", "RabbitOperations", "RabbitMessagingTemplate");
    private static final Set<String> RABBIT_SENDS = Set.of("convertAndSend", "send", "convertSendAndReceive", "sendAndReceive");
    private static final Set<String> ROCKET = Set.of("RocketMQTemplate");
    private static final Set<String> ROCKET_SENDS = Set.of("send", "convertAndSend", "syncSend", "asyncSend", "sendOneWay",
            "syncSendOrderly", "asyncSendOrderly", "sendOneWayOrderly", "sendMessageInTransaction", "syncSendDelayTimeSeconds");

    @Override
    public List<Fact> extract(Service s) {
        var facts = new ArrayList<Fact>();
        var binds = bindings(s);
        for (CompilationUnit cu : s.units()) {
            for (var c : cu.findAll(ClassOrInterfaceDeclaration.class)) listeners(s, c, facts);
            for (var m : cu.findAll(MethodDeclaration.class)) listeners(s, m, facts);
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) send(s, call, binds, facts);
            for (ObjectCreationExpr o : cu.findAll(ObjectCreationExpr.class)) {
                // org.apache.rocketmq.common.message.Message(topic, tags, body); Spring's Message has no such constructor
                if (o.getType().getNameAsString().equals("Message") && o.getArguments().size() >= 2
                        && cu.getImports().stream().anyMatch(i -> i.getNameAsString().startsWith("org.apache.rocketmq"))) {
                    facts.add(new Fact.TopicUse(true, s.eval(o.getArgument(0)), s.evidence(o), "RocketMQ Message", "rocketmq"));
                }
            }
        }
        return facts;
    }

    private static void listeners(Service s, NodeWithAnnotations<?> n, List<Fact> facts) {
        var ev = s.evidence((Node) n);
        Ann.find(n, "RabbitListener").ifPresent(a -> {
            var queues = Ann.attr(a, "queues", "value");
            if (queues.isPresent()) for (Value v : s.evalAll(queues.get())) facts.add(new Fact.TopicUse(false, v, ev, "RabbitListener", "rabbit"));
            else facts.add(new Fact.TopicUse(false, Value.unresolved(a.toString()), ev, "RabbitListener", "rabbit")); // bindings/queuesToDeclare
        });
        Ann.find(n, "RocketMQMessageListener").ifPresent(a -> {
            Value topic = Ann.attr(a, "topic").map(s::eval).orElse(Value.unresolved(a.toString()));
            facts.add(new Fact.TopicUse(false, topic, ev, "RocketMQMessageListener", "rocketmq"));
        });
    }

    private static void send(Service s, MethodCallExpr call, List<Bind> binds, List<Fact> facts) {
        if (call.getScope().isEmpty() || call.getArguments().isEmpty()) return;
        var scope = call.getScope().get();
        String name = call.getNameAsString();
        int args = call.getArguments().size();
        if (RABBIT_SENDS.contains(name) && args >= 2 && (Receivers.isReceiver(s, scope, RABBIT, "rabbittemplate")
                || Receivers.isReceiver(s, scope, RABBIT, "amqptemplate"))) {
            int keyArg = rabbitKey(s, call);
            Value key = keyArg < 0 ? Value.unresolved(call.toString()) : s.eval(call.getArgument(keyArg));
            var queues = new LinkedHashMap<String, Value>();
            route(binds, keyArg == 1 ? s.eval(call.getArgument(0)) : null, key, queues);
            for (Value q : queues.values()) facts.add(new Fact.TopicUse(true, q, s.evidence(call), "RabbitTemplate", "rabbit"));
        } else if (ROCKET_SENDS.contains(name) && args >= 2 && Receivers.isReceiver(s, scope, ROCKET, "rocketmqtemplate")) {
            facts.add(new Fact.TopicUse(true, withoutTag(s.eval(call.getArgument(0))), s.evidence(call), "RocketMQTemplate", "rocketmq"));
        } else if (name.equals("subscribe") && args == 2 && Receivers.isReceiver(s, scope,
                Set.of("DefaultMQPushConsumer", "DefaultLitePullConsumer", "DefaultMQPullConsumer"), "mqconsumer")) {
            facts.add(new Fact.TopicUse(false, s.eval(call.getArgument(0)), s.evidence(call), "RocketMQ subscribe", "rocketmq"));
        }
    }

    /** Metadata overloads keep the routing key first, or use the template's runtime default. */
    private static int rabbitKey(Service s, MethodCallExpr call) {
        int count = call.getArguments().size();
        boolean converts = call.getNameAsString().startsWith("convert");
        if (count == 2) return rabbitMetadata(s, call.getArgument(1)) ? -1 : 0;
        if (count == 3) {
            Expression last = call.getArgument(2);
            if (rabbitMetadata(s, last)) return converts && rabbitMetadata(s, call.getArgument(1)) ? -1 : 0;
            return argumentType(s, last).isPresent() ? 1 : -1;
        }
        if (count == 4 && converts && rabbitMetadata(s, call.getArgument(2))) return 0;
        return count <= 5 ? 1 : -1;
    }

    private static boolean rabbitMetadata(Service s, Expression value) {
        return value.isLambdaExpr() || value.isMethodReferenceExpr()
                || argumentType(s, value).filter(t -> t.equals("MessagePostProcessor") || t.equals("CorrelationData")).isPresent();
    }

    private static java.util.Optional<String> argumentType(Service s, Expression value) {
        java.util.Optional<String> type;
        if (value.isObjectCreationExpr()) type = java.util.Optional.of(value.asObjectCreationExpr().getType().getNameAsString());
        else if (value.isCastExpr()) type = java.util.Optional.of(value.asCastExpr().getType().asString());
        else if (value.isNameExpr()) type = s.index().declaredType(value.asNameExpr().getNameAsString(), value);
        else if (value.isFieldAccessExpr()) type = s.index().declaredType(value.asFieldAccessExpr().getNameAsString(), value);
        else if (value.isLiteralExpr()) type = java.util.Optional.of("literal");
        else type = java.util.Optional.empty();
        return type.map(t -> t.substring(t.lastIndexOf('.') + 1));
    }

    private record Queue(Value name, Value deadExchange, Value deadKey) {}

    private record Bind(Value exchange, Value key, Queue queue) {}

    /** Queues a message reaches: those bound to (exchange, key) and where they dead-letter to; the key itself if none. */
    private static void route(List<Bind> binds, Value exchange, Value key, Map<String, Value> out) {
        boolean bound = false;
        for (Bind b : binds) {
            if (!key.resolved() || !b.key().text().equals(key.text())) continue;
            // an unknown exchange on either side cannot rule the binding out
            if (exchange != null && exchange.resolved() && b.exchange().resolved() && !exchange.text().equals(b.exchange().text())) continue;
            bound = true;
            // putIfAbsent also stops dead-letter cycles
            if (out.putIfAbsent(b.queue().name().text(), b.queue().name()) == null && b.queue().deadKey() != null)
                route(binds, b.queue().deadExchange(), b.queue().deadKey(), out);
        }
        if (!bound) out.putIfAbsent(key.text(), key);
    }

    /** Every {@code @Bean Binding} method, with its queue and exchange beans looked up by method (or parameter) name. */
    private static List<Bind> bindings(Service s) {
        var queues = new HashMap<String, Queue>();
        var exchanges = new HashMap<String, Value>();
        var bindMethods = new ArrayList<MethodDeclaration>();
        for (CompilationUnit cu : s.units()) {
            for (var m : cu.findAll(MethodDeclaration.class)) {
                String type = m.getType().asString();
                if (type.equals("Queue")) {
                    var q = queue(s, m);
                    if (q != null) queues.put(m.getNameAsString(), q);
                } else if (type.endsWith("Exchange")) {
                    var x = exchange(s, m);
                    if (x != null) exchanges.put(m.getNameAsString(), x);
                } else if (type.equals("Binding")) bindMethods.add(m);
            }
        }
        var binds = new ArrayList<Bind>();
        for (var m : bindMethods) {
            Expression q = null, x = null, k = null;
            for (var c : m.findAll(MethodCallExpr.class)) {
                if (c.getArguments().isEmpty()) continue;
                switch (c.getNameAsString()) {
                    case "bind" -> q = c.getArgument(0);
                    case "to" -> x = c.getArgument(0);
                    case "with" -> k = c.getArgument(0);
                    default -> { }
                }
            }
            Queue queue = q == null ? null : queues.get(beanName(q));
            if (queue == null || k == null) continue; // keyless (fanout/headers) bindings are not matched
            Value exchange = x == null ? null : exchanges.get(beanName(x));
            binds.add(new Bind(exchange != null ? exchange : Value.unresolved(String.valueOf(x)), s.eval(k), queue));
        }
        return binds;
    }

    private static String beanName(Expression e) {
        return e instanceof MethodCallExpr c ? c.getNameAsString() : e.toString();
    }

    private static Queue queue(Service s, MethodDeclaration m) {
        Value name = null, dlx = null, dlk = null;
        for (var o : m.findAll(ObjectCreationExpr.class))
            if (o.getType().getNameAsString().equals("Queue") && !o.getArguments().isEmpty()) name = s.eval(o.getArgument(0));
        for (var c : m.findAll(MethodCallExpr.class)) {
            var a = c.getArguments();
            if (a.isEmpty()) continue;
            switch (c.getNameAsString()) {
                case "durable", "nonDurable" -> {
                    if (c.getScope().filter(e -> e.toString().equals("QueueBuilder")).isPresent()) name = s.eval(a.get(0));
                }
                case "deadLetterExchange" -> dlx = s.eval(a.get(0));
                case "deadLetterRoutingKey" -> dlk = s.eval(a.get(0));
                case "withArgument", "put" -> {
                    if (a.size() != 2) break;
                    String arg = s.eval(a.get(0)).text();
                    if (arg.equals("x-dead-letter-exchange")) dlx = s.eval(a.get(1));
                    if (arg.equals("x-dead-letter-routing-key")) dlk = s.eval(a.get(1));
                }
                default -> { }
            }
        }
        return name == null ? null : new Queue(name, dlx, dlk);
    }

    private static Value exchange(Service s, MethodDeclaration m) {
        Value name = null;
        for (var o : m.findAll(ObjectCreationExpr.class))
            if (o.getType().getNameAsString().endsWith("Exchange") && !o.getArguments().isEmpty()) name = s.eval(o.getArgument(0));
        for (var c : m.findAll(MethodCallExpr.class))
            if (c.getNameAsString().endsWith("Exchange") && !c.getArguments().isEmpty()
                    && c.getScope().filter(e -> e.toString().equals("ExchangeBuilder")).isPresent()) name = s.eval(c.getArgument(0));
        return name;
    }

    /** RocketMQ destinations are {@code topic:tag}; the tag filters within the topic. */
    static Value withoutTag(Value v) {
        int colon = v.text().indexOf(':');
        return !v.resolved() || colon < 0 ? v : new Value(v.text().substring(0, colon), v.confidence(), v.raw(), v.via());
    }
}
