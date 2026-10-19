package io.github.sunjirapath.ssg.extract;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Value;
import io.github.sunjirapath.ssg.scan.Ann;
import io.github.sunjirapath.ssg.scan.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Outbound HTTP: {@code @FeignClient}, {@code @HttpExchange} interfaces, and best-effort
 * RestTemplate / WebClient / RestClient call sites. Load-balanced names become {@code lb://name}.
 */
public final class OutboundHttpExtractor implements Extractor {

    private static final Map<String, String> REST_TEMPLATE = Map.ofEntries(
            Map.entry("getForObject", "GET"), Map.entry("getForEntity", "GET"),
            Map.entry("postForObject", "POST"), Map.entry("postForEntity", "POST"), Map.entry("postForLocation", "POST"),
            Map.entry("put", "PUT"), Map.entry("delete", "DELETE"), Map.entry("patchForObject", "PATCH"),
            Map.entry("headForHeaders", "HEAD"), Map.entry("optionsForAllow", "OPTIONS"),
            Map.entry("exchange", "?"), Map.entry("execute", "?"));
    private static final Set<String> REST_TEMPLATE_TYPES = Set.of("RestTemplate", "RestOperations", "OAuth2RestTemplate");
    private static final Set<String> FLUENT_VERBS = Set.of("get", "post", "put", "delete", "patch", "head", "options", "method");

    @Override
    public List<Fact> extract(Service s) {
        var facts = new ArrayList<Fact>();
        for (CompilationUnit cu : s.units()) {
            for (var c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!c.isInterface()) continue;
                Ann.find(c, "FeignClient").ifPresent(a -> feign(s, c, a, facts));
                if (Ann.has(c, "HttpExchange") || c.getMethods().stream().anyMatch(m -> Mappings.exchange(m, s).isPresent())) {
                    httpExchange(s, c, facts);
                }
            }
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                restTemplate(s, call).ifPresent(facts::add);
                fluent(s, call).ifPresent(facts::add);
            }
        }
        return facts;
    }

    // ---------------------------------------------------------------- declarative clients

    private static void feign(Service s, ClassOrInterfaceDeclaration c, com.github.javaparser.ast.expr.AnnotationExpr a,
                              List<Fact> facts) {
        Value name = Ann.attr(a, "name", "value").map(s::eval).orElse(Value.exact(c.getNameAsString()));
        Value url = Ann.attr(a, "url").map(s::eval).filter(v -> !v.text().isBlank()).orElse(null);
        Value base = url != null ? url : Value.exact("lb://").concat(name, name.raw());
        Value path = Ann.attr(a, "path").map(s::eval).orElse(Value.exact(""));
        List<Value> classPaths = Mappings.mvc(c, s).map(Mappings.Mapping::paths).orElse(List.of(Value.exact("")));
        for (MethodDeclaration m : Mappings.mappedMethods(c, s, md -> Mappings.mvc(md, s).isPresent())) {
            var mapping = Mappings.mvc(m, s).orElseThrow();
            for (Value cp : classPaths) {
                for (Value mp : mapping.paths()) {
                    Value full = url(base, Mappings.joinValues(path, cp, mp));
                    for (String verb : mapping.methods()) facts.add(new Fact.Call("feign", verb, full, s.evidence(m)));
                }
            }
        }
    }

    private static void httpExchange(Service s, ClassOrInterfaceDeclaration c, List<Fact> facts) {
        // Without a type-level url the base URL is set on the proxy factory at runtime.
        Value base = Mappings.exchange(c, s).map(m -> m.paths().getFirst()).filter(v -> !v.text().isBlank())
                .orElse(Value.unresolved("<proxy factory base url>"));
        for (MethodDeclaration m : Mappings.mappedMethods(c, s, md -> Mappings.exchange(md, s).isPresent())) {
            var mapping = Mappings.exchange(m, s).orElseThrow();
            List<String> verbs = mapping.annotation().getName().getIdentifier().equals("HttpExchange")
                    ? Ann.attr(mapping.annotation(), "method").map(s::eval).map(v -> List.of(v.text().toUpperCase()))
                            .orElse(List.of("*"))
                    : mapping.methods();
            for (Value mp : mapping.paths()) {
                for (String verb : verbs) facts.add(new Fact.Call("http-exchange", verb, url(base, mp), s.evidence(m)));
            }
        }
    }

    // ---------------------------------------------------------------- imperative clients

    private static Optional<Fact> restTemplate(Service s, MethodCallExpr call) {
        String verb = REST_TEMPLATE.get(call.getNameAsString());
        if (verb == null || call.getArguments().isEmpty() || call.getScope().isEmpty()) return Optional.empty();
        if (!isReceiver(s, call.getScope().get(), REST_TEMPLATE_TYPES, "resttemplate")) return Optional.empty();
        if (verb.equals("?")) {
            verb = call.getArguments().size() > 1 ? Mappings.enumNames(call.getArgument(1)).getFirst() : "*";
        }
        Expression arg = call.getArgument(0);
        Value url = arg instanceof ObjectCreationExpr ? Value.unresolved(arg.toString()) : s.eval(arg);
        return Optional.of(new Fact.Call("rest-template", verb, withBaseUrl(s, call, url), s.evidence(call)));
    }

    /** {@code client.get().uri(...)} and {@code client.method(HttpMethod.X).uri(...)} for WebClient and RestClient. */
    private static Optional<Fact> fluent(Service s, MethodCallExpr call) {
        if (!call.getNameAsString().equals("uri") || call.getArguments().isEmpty()) return Optional.empty();
        if (!(call.getScope().orElse(null) instanceof MethodCallExpr verbCall)
                || !FLUENT_VERBS.contains(verbCall.getNameAsString())) {
            return Optional.empty();
        }
        String verb = verbCall.getNameAsString().equals("method")
                ? verbCall.getArguments().stream().findFirst().map(a -> Mappings.enumNames(a).getFirst()).orElse("*")
                : verbCall.getNameAsString().toUpperCase();
        if (verbCall.getNameAsString().equals("method") && verbCall.getArguments().size() != 1) return Optional.empty();
        boolean gatewayDsl = call.findAncestor(MethodDeclaration.class)
                .filter(md -> md.getType().asString().endsWith("RouteLocator")).isPresent();
        if (gatewayDsl) return Optional.empty(); // route DSL .method("GET").uri(..) belongs to GatewayExtractor
        String client = clientKind(s, verbCall);
        if (client == null) return Optional.empty(); // e.g. optional.get().uri(..) is not an HTTP client
        Expression arg = call.getArgument(0);
        Value url = arg instanceof LambdaExpr l ? uriBuilderPath(s, l) : s.eval(arg);
        return Optional.of(new Fact.Call(client, verb, withBaseUrl(s, call, url), s.evidence(call)));
    }

    /** {@code uri(b -> b.path("/x").queryParam(..).build())}: only the path segments are kept. */
    /**
     * "webclient" or "restclient" from the receiver's declared type, else from the chain text
     * ({@code webClientBuilder.build()}, {@code RestClient.create()}); null when neither says so.
     */
    private static String clientKind(Service s, MethodCallExpr verbCall) {
        Expression root = verbCall;
        while (root instanceof MethodCallExpr m && m.getScope().isPresent()) root = m.getScope().get();
        String name = switch (root) {
            case NameExpr n -> n.getNameAsString();
            case FieldAccessExpr f -> f.getNameAsString();
            default -> null;
        };
        String type = name == null ? "" : s.index().declaredType(name, root).orElse("");
        String text = (type + " " + verbCall.getScope().map(Object::toString).orElse("")).toLowerCase();
        return text.contains("webclient") ? "webclient" : text.contains("restclient") ? "restclient" : null;
    }

    private static Value uriBuilderPath(Service s, LambdaExpr l) {
        var parts = l.findAll(MethodCallExpr.class).stream()
                .filter(m -> m.getNameAsString().equals("path") && m.getArguments().size() == 1)
                // findAll walks a chain outermost-first; chained calls all begin at the receiver, so order by end
                .sorted(java.util.Comparator.comparing(m -> m.getEnd().orElseThrow()))
                .map(m -> s.eval(m.getArgument(0))).toList();
        if (parts.isEmpty()) return Value.unresolved(l.toString());
        return Mappings.joinValues(parts.toArray(Value[]::new));
    }

    private static boolean isReceiver(Service s, Expression scope, Set<String> types, String nameHint) {
        String name = switch (scope) {
            case NameExpr n -> n.getNameAsString();
            case FieldAccessExpr f -> f.getNameAsString();
            case MethodCallExpr m -> m.getNameAsString();
            default -> "";
        };
        if (name.isEmpty()) return false;
        Optional<String> type = scope instanceof MethodCallExpr ? Optional.empty() : s.index().declaredType(name, scope);
        return type.map(types::contains).orElseGet(() -> name.toLowerCase().contains(nameHint));
    }

    /**
     * Relative URLs get the {@code baseUrl(..)}/{@code rootUri(..)} configured in the same file,
     * when there is exactly one; otherwise the base is unknown.
     */
    private static Value withBaseUrl(Service s, Node call, Value url) {
        if (url.text().contains("://") || url.text().startsWith(Value.UNKNOWN)) return url;
        CompilationUnit cu = call.findCompilationUnit().orElseThrow();
        var bases = cu.findAll(MethodCallExpr.class).stream()
                .filter(m -> Set.of("baseUrl", "rootUri").contains(m.getNameAsString()) && m.getArguments().size() == 1)
                .map(m -> s.eval(m.getArgument(0))).toList();
        var distinct = bases.stream().map(Value::text).distinct().toList();
        Value base = distinct.size() == 1 ? bases.getFirst() : Value.unresolved("<client base url>");
        return url(base, url);
    }

    /** base + path with exactly one slash between them. */
    static Value url(Value base, Value path) {
        String b = base.text();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        String p = path.text().isEmpty() || path.text().startsWith("/") ? path.text() : "/" + path.text();
        Value trimmedBase = new Value(b, base.confidence(), base.raw(), base.via());
        Value fixedPath = new Value(p, path.confidence(), path.raw(), path.via());
        String raw = base.raw().isEmpty() ? path.raw() : path.raw().isEmpty() ? base.raw() : base.raw() + " + " + path.raw();
        return trimmedBase.concat(fixedPath, raw);
    }
}
