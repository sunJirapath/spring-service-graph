package io.github.sunjirapath.ssg.extract;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
    private static final Set<String> MIME_TYPES = Set.of("MimeType", "MediaType");
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
                    String broker = "unknown";
                    for (String listener : List.of("KafkaListener", "RabbitListener")) {
                        if (Ann.find(m, listener).isPresent() || m.findAncestor(ClassOrInterfaceDeclaration.class)
                                .flatMap(c -> Ann.find(c, listener)).isPresent()) {
                            broker = listener.equals("KafkaListener") ? "kafka" : "rabbit";
                            break;
                        }
                    }
                    for (Value v : s.evalAll(e)) facts.add(new Fact.TopicUse(true, v, s.evidence(m), "SendTo", broker));
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
            for (Value v : s.evalAll(e)) facts.add(new Fact.TopicUse(false, v, ev, "KafkaListener", "kafka"));
        });
        Ann.attr(a.get(), "topicPattern").ifPresent(e ->
                facts.add(new Fact.TopicUse(false, s.eval(e), ev, "KafkaListener(topicPattern)", "kafka")));
        if (Ann.attr(a.get(), "topics", "value", "topicPattern").isEmpty()) {
            facts.add(new Fact.TopicUse(false, Value.unresolved(a.get().toString()), ev, "KafkaListener", "kafka"));
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
            facts.add(new Fact.TopicUse(true, topic, s.evidence(call), "KafkaTemplate", "kafka"));
        } else if (name.equals("send") && call.getArguments().size() >= 2 && Receivers.isReceiver(s, scope, Set.of("StreamBridge"), "streambridge")) {
            Value binding = s.eval(call.getArgument(0));
            bridgeBindings.add(binding.text());
            String key = BINDINGS + binding.text() + ".destination";
            // StreamBridge falls back to a destination named after the binding
            Value topic = s.config().get(key) != null ? s.config().valueOf(key) : binding;
            String broker = streamBroker(s, binding.text());
            if (explicitBinder(s, call)) {
                Expression binder = call.getArgument(1);
                if (!binder.isNullLiteralExpr()) {
                    var precreated = precreatedOutput(s, binding.text());
                    if (precreated.isEmpty()) broker = "unknown";
                    else if (!precreated.get()) broker = binderBroker(s, s.eval(binder));
                }
            }
            facts.add(new Fact.TopicUse(true, topic, s.evidence(call), "StreamBridge", broker));
        }
    }

    private static String streamBroker(Service s, String binding) {
        String key = BINDINGS + binding + ".binder";
        if (s.config().get(key) == null) key = "spring.cloud.stream.default.binder";
        return binderBroker(s, s.config().get(key) == null ? Value.exact("") : s.config().valueOf(key));
    }

    private static String binderBroker(Service s, Value value) {
        // An empty binder name requests the global default, not the common binding default.
        if (value.resolved() && value.text().isBlank()) value = s.config().valueOfAny(
                "spring.cloud.stream.default-binder", "spring.cloud.stream.defaultBinder",
                "spring.cloud.stream.default_binder", "spring.cloud.stream.defaultbinder");
        if (!value.resolved()) return "unknown";
        String binder = value.text();
        String type = "spring.cloud.stream.binders." + binder + ".type";
        if (s.config().get(type) != null) {
            Value resolved = s.config().valueOf(type);
            if (!resolved.resolved()) return "unknown";
            binder = resolved.text();
        }
        return switch (binder) {
            case "kafka" -> "kafka";
            case "rabbit" -> "rabbit";
            case "rocketmq" -> "rocketmq";
            default -> "unknown";
        };
    }

    private static Optional<Boolean> precreatedOutput(Service s, String binding) {
        var functional = functionOutput(s, binding);
        if (functional.orElse(false)) return functional;
        String[] keys = {"spring.cloud.stream.output-bindings", "spring.cloud.stream.outputBindings",
                "spring.cloud.stream.output_bindings", "spring.cloud.stream.outputbindings"};
        for (String key : keys) {
            if (s.config().get(key) == null) continue;
            Value outputs = s.config().valueOfAny(keys);
            for (String output : outputs.text().split(";")) if (output.strip().equals(binding)) return Optional.of(true);
            return outputs.resolved() ? functional : Optional.empty();
        }
        return functional;
    }

    private static Optional<Boolean> functionOutput(Service s, String binding) {
        String key = "spring.cloud.stream.function.definition";
        Value definitions = s.config().get(key) == null ? Value.exact("") : s.config().valueOf(key);
        if (definitions.resolved() && definitions.text().isBlank()) {
            key = "spring.cloud.function.definition";
            definitions = s.config().get(key) == null ? Value.exact("") : s.config().valueOf(key);
        }
        // shortcut: compositions and external function registrations need context modeling; keep ambiguity unresolved.
        if (!definitions.resolved() || definitions.text().contains("|")) return Optional.empty();
        if (definitions.text().isBlank()) {
            String autodetectKey = "spring.cloud.stream.function.autodetect";
            if (s.config().get(autodetectKey) != null) {
                Value autodetect = s.config().valueOf(autodetectKey);
                if (!autodetect.resolved()) return Optional.empty();
                if (autodetect.text().equalsIgnoreCase("false")) return Optional.of(false);
            }
            var functions = s.units().stream().flatMap(u -> u.findAll(MethodDeclaration.class).stream())
                    .filter(m -> Ann.has(m, "Bean") && m.getType().isClassOrInterfaceType()
                            && Set.of("Function", "Supplier", "Consumer", "BiFunction", "BiConsumer")
                            .contains(m.getType().asClassOrInterfaceType().getNameAsString())).toList();
            if (functions.isEmpty()) return Optional.of(false);
            if (functions.size() != 1) return Optional.empty();
            var method = functions.getFirst();
            definitions = Ann.attr(Ann.find(method, "Bean").orElseThrow(), "name", "value").map(s::evalAll)
                    .flatMap(names -> names.stream().findFirst()).orElseGet(() -> Value.exact(method.getNameAsString()));
            if (!definitions.resolved()) return Optional.empty();
        }
        for (String definition : definitions.text().split(";")) {
            String function = definition.strip();
            ClassOrInterfaceType type = null;
            for (var unit : s.units()) for (var method : unit.findAll(MethodDeclaration.class)) {
                var bean = Ann.find(method, "Bean");
                if (bean.isEmpty() || !method.getType().isClassOrInterfaceType()) continue;
                var names = Ann.attr(bean.get(), "name", "value").map(s::evalAll)
                        .orElseGet(() -> List.of(Value.exact(method.getNameAsString())));
                if (names.stream().noneMatch(n -> n.resolved() && n.text().equals(function))) continue;
                type = method.getType().asClassOrInterfaceType();
            }
            if (type == null) return Optional.empty();
            if (Set.of("Consumer", "BiConsumer").contains(type.getNameAsString())) continue;
            if (!Set.of("Function", "Supplier", "BiFunction").contains(type.getNameAsString())) return Optional.empty();
            var outputType = type.getTypeArguments().flatMap(args -> args.getLast());
            if (outputType.isEmpty() || !outputType.get().isClassOrInterfaceType()) return Optional.empty();
            var out = outputType.get().asClassOrInterfaceType();
            if (!type.getNameAsString().equals("Supplier") && (out.getNameAsString().equals("Void")
                    || out.getNameAsString().equals("Mono") && out.getTypeArguments().filter(args -> args.size() == 1
                    && args.get(0).isClassOrInterfaceType()
                    && args.get(0).asClassOrInterfaceType().getNameAsString().equals("Void")).isPresent())) continue;
            int count = out.getNameAsString().matches("Tuple[2-8]")
                    ? out.getTypeArguments().map(List::size).orElse(0) : 1;
            if (count == 0) return Optional.empty();
            for (int i = 0; i < count; i++) {
                String output = function + "-out-" + i;
                String alias = "spring.cloud.stream.function.bindings." + output;
                Value name = s.config().get(alias) == null ? Value.exact(output) : s.config().valueOf(alias);
                if (!name.resolved()) return Optional.empty();
                if (name.text().equals(binding)) return Optional.of(true);
            }
        }
        return Optional.of(false);
    }

    private static boolean explicitBinder(Service s, MethodCallExpr call) {
        if (call.getArguments().size() == 4) return true;
        if (call.getArguments().size() != 3 || mimeType(s, call.getArgument(2))) return false;
        return !(call.getArgument(1) instanceof NameExpr n
                && s.index().declaredType(n.getNameAsString(), n).filter(t -> !t.equals("String")).isPresent());
    }

    private static boolean mimeType(Service s, Expression e) {
        return switch (e) {
            case EnclosedExpr enclosed -> mimeType(s, enclosed.getInner());
            case CastExpr cast -> cast.getType().isClassOrInterfaceType()
                    && MIME_TYPES.contains(cast.getType().asClassOrInterfaceType().getNameAsString());
            case ObjectCreationExpr object -> MIME_TYPES.contains(object.getType().getNameAsString());
            case NameExpr name -> s.index().declaredType(name.getNameAsString(), name).filter(MIME_TYPES::contains).isPresent();
            case FieldAccessExpr field -> field.getScope().toString().matches("(?:.*\\.)?(?:MimeTypeUtils|MediaType)")
                    && !field.getNameAsString().endsWith("_VALUE") && !field.getNameAsString().endsWith("COMPARATOR")
                    || s.index().declaredType(field.getNameAsString(), field).filter(MIME_TYPES::contains).isPresent();
            case MethodCallExpr call -> Set.of("valueOf", "parseMimeType", "parseMediaType").contains(call.getNameAsString())
                    && call.getScope().map(Object::toString).orElse("").matches("(?:.*\\.)?(?:MimeType(?:Utils)?|MediaType)");
            default -> false;
        };
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
                        e.evidence(), "cloud-stream:" + binding, streamBroker(s, binding)));
            }
        }
    }
}
