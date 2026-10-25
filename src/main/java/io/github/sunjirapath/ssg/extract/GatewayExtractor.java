package io.github.sunjirapath.ssg.extract;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Value;
import io.github.sunjirapath.ssg.scan.Config;
import io.github.sunjirapath.ssg.scan.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring Cloud Gateway routes from config (all key generations) and, best-effort, from the
 * Java {@code RouteLocator} DSL. Each route becomes an endpoint on the gateway plus a call to
 * the route URI with the path as the filters rewrite it.
 */
public final class GatewayExtractor implements Extractor {

    private static final List<String> PREFIXES = List.of("spring.cloud.gateway.routes",
            "spring.cloud.gateway.server.webflux.routes", "spring.cloud.gateway.server.webmvc.routes",
            "spring.cloud.gateway.mvc.routes");
    private static final Pattern ROUTE_KEY = Pattern.compile("\\[(\\d+)]\\.(.+)");
    private static final Pattern ITEM = Pattern.compile("(predicates|filters)\\[(\\d+)](?:\\.(.+))?");
    private static final String WILD = "SSGWILDCARD";

    /** A predicate or filter: shortcut form {@code Name=a,b} or long form name + args. */
    record Def(String name, List<String> args, Evidence evidence) {}

    @Override
    public List<Fact> extract(Service s) {
        var facts = new ArrayList<Fact>();
        for (String prefix : PREFIXES) {
            routesFromConfig(s, prefix).values().forEach(route -> route(s, route, facts));
        }
        for (CompilationUnit cu : s.units()) {
            for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                if (m.getType().asString().endsWith("RouteLocator")) javaRoutes(s, m, facts);
            }
        }
        return facts;
    }

    private static Map<Integer, Map<String, Config.Entry>> routesFromConfig(Service s, String prefix) {
        var routes = new TreeMap<Integer, Map<String, Config.Entry>>();
        s.config().withPrefix(prefix + "[").forEach((k, v) -> {
            Matcher m = ROUTE_KEY.matcher(k.substring(prefix.length()));
            if (m.matches()) routes.computeIfAbsent(Integer.parseInt(m.group(1)), i -> new TreeMap<>()).put(m.group(2), v);
        });
        return routes;
    }

    private static void route(Service s, Map<String, Config.Entry> route, List<Fact> facts) {
        Config.Entry uriEntry = route.get("uri");
        if (uriEntry == null || uriEntry.value() == null || uriEntry.value().isBlank()) return; // invalid route
        Value uri = s.config().resolve(uriEntry.value()).withConfidence(Confidence.EXACT, uriEntry.evidence());
        var predicates = defs(s, route, "predicates");
        var filters = defs(s, route, "filters");
        List<String> methods = predicates.stream().filter(d -> d.name.equals("Method"))
                .map(d -> d.args.stream().map(String::toUpperCase).toList())
                .reduce((left, right) -> left.stream().filter(right::contains).toList()).orElse(List.of("*"));
        if (methods.isEmpty()) return;
        for (Def p : predicates) {
            if (!p.name.equals("Path")) continue;
            for (String pattern : p.args) {
                Value inbound = Value.exact(Mappings.join(pattern));
                for (String verb : methods) facts.add(new Fact.Endpoint(verb, inbound, p.evidence, "gateway-route", null));
                if (!remote(uri)) continue;
                Value target = OutboundHttpExtractor.url(uri, rewrite(pattern, filters));
                for (String verb : methods) facts.add(new Fact.Call("gateway", verb, target, uriEntry.evidence()));
            }
        }
    }

    private static List<Def> defs(Service s, Map<String, Config.Entry> route, String kind) {
        var grouped = new TreeMap<Integer, Map<String, Config.Entry>>();
        route.forEach((k, v) -> {
            Matcher m = ITEM.matcher(k);
            if (m.matches() && m.group(1).equals(kind)) {
                grouped.computeIfAbsent(Integer.parseInt(m.group(2)), i -> new TreeMap<>()).put(m.group(3) == null ? "" : m.group(3), v);
            }
        });
        var out = new ArrayList<Def>();
        for (var item : grouped.values()) {
            Config.Entry shortcut = item.get("");
            if (shortcut != null && shortcut.value() != null) {
                String text = s.config().resolve(shortcut.value()).text();
                int eq = text.indexOf('=');
                String args = eq < 0 ? "" : text.substring(eq + 1);
                out.add(new Def(eq < 0 ? text.strip() : text.substring(0, eq).strip(), splitArgs(args), shortcut.evidence()));
            } else if (item.get("name") != null) {
                var args = item.entrySet().stream().filter(e -> e.getKey().startsWith("args.") && e.getValue().value() != null)
                        .map(e -> s.config().resolve(e.getValue().value()).text()).toList();
                out.add(new Def(item.get("name").value(), args, item.get("name").evidence()));
            }
        }
        return out;
    }

    private static List<String> splitArgs(String args) {
        return args.isBlank() ? List.of() : Arrays.stream(args.split(",")).map(String::strip).toList();
    }

    /** Applies the path-changing filters, in order, to a Path predicate pattern. */
    static Value rewrite(String pattern, List<Def> filters) {
        String path = Mappings.join(pattern);
        for (Def f : filters) {
            switch (f.name) {
                case "StripPrefix" -> {
                    try {
                        int n = f.args.isEmpty() ? 1 : Integer.parseInt(f.args.getFirst());
                        var segs = Arrays.stream(path.split("/")).filter(x -> !x.isEmpty()).skip(n).toList();
                        path = "/" + String.join("/", segs);
                    } catch (IllegalArgumentException e) { // unresolved, invalid or negative count
                        return Value.unresolved("StripPrefix=" + String.join(", ", f.args));
                    }
                }
                case "PrefixPath" -> path = Mappings.join(f.args.isEmpty() ? "" : f.args.getFirst(), path);
                case "SetPath" -> path = f.args.isEmpty() ? path : f.args.getFirst();
                case "RewritePath" -> {
                    if (f.args.size() < 2) continue;
                    try {
                        String replacement = f.args.get(1).replace("$\\{", "${");
                        path = path.replace("**", WILD).replaceAll(f.args.getFirst(), replacement).replace(WILD, "**");
                    } catch (IllegalArgumentException | IndexOutOfBoundsException e) { // bad regex or group ref
                        return Value.unresolved("RewritePath=" + String.join(", ", f.args));
                    }
                }
                default -> { }
            }
        }
        return Value.exact(path.isEmpty() ? "/" : path);
    }

    /** {@code r -> r.path("/x/**").filters(f -> f.stripPrefix(1)).uri("lb://svc")} inside a RouteLocator bean. */
    private static void javaRoutes(Service s, MethodDeclaration m, List<Fact> facts) {
        for (LambdaExpr l : m.findAll(LambdaExpr.class)) {
            boolean outermost = l.findAncestor(LambdaExpr.class).filter(p -> hasCall(p, "uri")).isEmpty();
            if (!outermost || !hasCall(l, "uri")) continue;
            var calls = l.findAll(MethodCallExpr.class);
            var uriCall = calls.stream().filter(c -> c.getNameAsString().equals("uri") && c.getArguments().size() == 1).findFirst();
            Value uri = uriCall.map(c -> s.eval(c.getArgument(0))).orElse(Value.unresolved(l.toString()));
            uriCall.filter(c -> !uri.resolved()).ifPresent(c -> facts.add(new Fact.Problem("UNRESOLVED",
                    "gateway route uri not resolved statically (from `" + uri.raw() + "`)", s.evidence(c))));
            var verbs = new ArrayList<String>(); // .method("POST") / .method(HttpMethod.POST), also after .and()
            for (MethodCallExpr c : calls) {
                if (!c.getNameAsString().equals("method")) continue;
                for (var a : c.getArguments()) {
                    verbs.add((a instanceof FieldAccessExpr f ? f.getNameAsString() : s.index().eval(a).text()).toUpperCase());
                }
            }
            if (verbs.isEmpty()) verbs.add("*");
            var filters = new ArrayList<Def>();
            for (MethodCallExpr c : calls.reversed()) { // innermost calls come last in the chain
                // no placeholder resolution: "/${seg}" in rewritePath is a regex group reference, not a property
                List<String> args = c.getArguments().stream().map(a -> s.index().eval(a).text()).toList();
                switch (c.getNameAsString()) {
                    case "stripPrefix" -> filters.add(new Def("StripPrefix", args, null));
                    case "prefixPath" -> filters.add(new Def("PrefixPath", args, null));
                    case "setPath" -> filters.add(new Def("SetPath", args, null));
                    case "rewritePath" -> filters.add(new Def("RewritePath", args, null));
                    default -> { }
                }
            }
            for (MethodCallExpr c : calls) {
                if (!c.getNameAsString().equals("path")) continue;
                for (var arg : c.getArguments()) {
                    Value pattern = s.eval(arg);
                    var ev = s.evidence(c);
                    for (String verb : verbs) facts.add(new Fact.Endpoint(verb, Mappings.joinValues(pattern), ev, "gateway-route", null));
                    if (!remote(uri)) continue;
                    Value path = pattern.resolved() ? rewrite(pattern.text(), filters) : pattern;
                    for (String verb : verbs) facts.add(new Fact.Call("gateway", verb, OutboundHttpExtractor.url(uri, path), ev));
                }
            }
        }
    }

    /** forward:/x and no://op are handled inside the gateway itself, so they are not outbound calls. */
    private static boolean remote(Value uri) {
        return uri.text().contains("://") && !uri.text().startsWith("no://");
    }

    private static boolean hasCall(LambdaExpr l, String name) {
        return l.findAll(MethodCallExpr.class).stream().anyMatch(c -> c.getNameAsString().equals(name));
    }
}
