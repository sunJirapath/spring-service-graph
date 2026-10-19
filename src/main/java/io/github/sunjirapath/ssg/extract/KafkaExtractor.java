package io.github.sunjirapath.ssg.extract;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Value;
import io.github.sunjirapath.ssg.scan.Ann;
import io.github.sunjirapath.ssg.scan.Config;
import io.github.sunjirapath.ssg.scan.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kafka topics: {@code @KafkaListener}, {@code @SendTo}, {@code KafkaTemplate.send/sendDefault},
 * {@code StreamBridge.send} and Spring Cloud Stream {@code bindings.*.destination}.
 */
public final class KafkaExtractor implements Extractor {

    private static final Set<String> TEMPLATE_TYPES = Set.of("KafkaTemplate", "KafkaOperations",
            "ReactiveKafkaProducerTemplate", "RoutingKafkaTemplate");
    private static final String BINDINGS = "spring.cloud.stream.bindings.";
    private static final Pattern DESTINATION = Pattern.compile(Pattern.quote(BINDINGS) + "([^.]+)\\.destination");
    private static final Pattern IN = Pattern.compile("(.+-in-\\d+|input)");
    private static final Pattern OUT = Pattern.compile("(.+-out-\\d+|output)");

    @Override
    public List<Fact> extract(Service s) {
        var facts = new ArrayList<Fact>();
        var bridgeBindings = new HashSet<String>();
        for (CompilationUnit cu : s.units()) {
            for (var c : cu.findAll(ClassOrInterfaceDeclaration.class)) listener(s, c, facts);
            for (var m : cu.findAll(MethodDeclaration.class)) {
                listener(s, m, facts);
                Ann.find(m, "SendTo").flatMap(a -> Ann.attr(a, "value")).ifPresent(e -> {
                    for (Value v : s.evalAll(e)) facts.add(new Fact.TopicUse(true, v, s.evidence(m), "SendTo"));
                });
            }
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) send(s, call, facts, bridgeBindings);
        }
        cloudStream(s, facts, bridgeBindings);
        return facts;
    }

    private static void listener(Service s, NodeWithAnnotations<?> n, List<Fact> facts) {
        var a = Ann.find(n, "KafkaListener");
        if (a.isEmpty()) return;
        var ev = s.evidence((com.github.javaparser.ast.Node) n);
        Ann.attr(a.get(), "topics", "value").ifPresent(e -> {
            for (Value v : s.evalAll(e)) facts.add(new Fact.TopicUse(false, v, ev, "KafkaListener"));
        });
        Ann.attr(a.get(), "topicPattern").ifPresent(e ->
                facts.add(new Fact.TopicUse(false, s.eval(e), ev, "KafkaListener(topicPattern)")));
        if (Ann.attr(a.get(), "topics", "value", "topicPattern").isEmpty()) {
            facts.add(new Fact.TopicUse(false, Value.unresolved(a.get().toString()), ev, "KafkaListener"));
        }
    }

    private static void send(Service s, MethodCallExpr call, List<Fact> facts, Set<String> bridgeBindings) {
        if (call.getScope().isEmpty()) return;
        Expression scope = call.getScope().get();
        String name = call.getNameAsString();
        if ((name.equals("send") || name.equals("sendDefault")) && Receivers.isReceiver(s, scope, TEMPLATE_TYPES, "kafkatemplate")) {
            Value topic;
            if (name.equals("sendDefault")) topic = s.config().valueOf("spring.kafka.template.default-topic");
            else if (call.getArguments().isEmpty()) return;
            else if (call.getArgument(0) instanceof ObjectCreationExpr o && o.getType().getNameAsString().equals("ProducerRecord")
                    && o.getArguments().isNonEmpty()) topic = s.eval(o.getArgument(0));
            else if (call.getArguments().size() == 1) topic = Value.unresolved(call.getArgument(0).toString());
            else topic = s.eval(call.getArgument(0));
            facts.add(new Fact.TopicUse(true, topic, s.evidence(call), "KafkaTemplate"));
        } else if (name.equals("send") && call.getArguments().size() >= 2 && Receivers.isReceiver(s, scope, Set.of("StreamBridge"), "streambridge")) {
            Value binding = s.eval(call.getArgument(0));
            bridgeBindings.add(binding.text());
            String key = BINDINGS + binding.text() + ".destination";
            // StreamBridge falls back to a destination named after the binding
            Value topic = s.config().get(key) != null ? s.config().valueOf(key) : binding;
            facts.add(new Fact.TopicUse(true, topic, s.evidence(call), "StreamBridge"));
        }
    }

    private static void cloudStream(Service s, List<Fact> facts, Set<String> bridgeBindings) {
        for (var entry : s.config().withPrefix(BINDINGS).entrySet()) {
            Matcher m = DESTINATION.matcher(entry.getKey());
            if (!m.matches() || bridgeBindings.contains(m.group(1))) continue;
            String binding = m.group(1);
            Config.Entry e = entry.getValue();
            Optional<Boolean> produce = OUT.matcher(binding).matches() ? Optional.of(true)
                    : IN.matcher(binding).matches() ? Optional.of(false) : Optional.empty();
            if (produce.isEmpty()) {
                facts.add(new Fact.Problem("UNRESOLVED", "Cloud Stream binding '" + binding
                        + "' has no -in-N/-out-N suffix, so its direction is unknown", e.evidence()));
                continue;
            }
            // the evidence is the config line itself, so a literal destination stays EXACT
            Value dest = e.value() == null || e.value().isBlank() ? Value.unresolved(entry.getKey()) : s.config().resolve(e.value());
            for (String d : dest.text().split(",")) {
                facts.add(new Fact.TopicUse(produce.get(), new Value(d.strip(), dest.confidence(), dest.raw(), dest.via()),
                        e.evidence(), "cloud-stream:" + binding));
            }
        }
    }
}
