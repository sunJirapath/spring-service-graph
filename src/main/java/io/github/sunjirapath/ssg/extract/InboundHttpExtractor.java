package io.github.sunjirapath.ssg.extract;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Value;
import io.github.sunjirapath.ssg.scan.Ann;
import io.github.sunjirapath.ssg.scan.JavaIndex;
import io.github.sunjirapath.ssg.scan.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * JAX-RS resources, WebFlux router beans and {@code @RestController}/{@code @Controller} endpoints, including mappings declared only on
 * implemented interfaces (openapi-generator {@code interfaceOnly}). When such an interface is
 * not in the checkout (an unbuilt repo), endpoints come from the OpenAPI spec instead.
 */
public final class InboundHttpExtractor implements Extractor {

    private static final String WEBFLUX = "org.springframework.web.reactive.function.server.";
    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    @Override
    public List<Fact> extract(Service s) {
        var facts = new ArrayList<Fact>();
        Value contextPath = contextPath(s);
        Value jaxRsPath = s.units().stream().flatMap(cu -> cu.findAll(ClassOrInterfaceDeclaration.class).stream())
                .flatMap(c -> Ann.find(c, "ApplicationPath").stream()).flatMap(a -> Ann.attr(a, "value").stream())
                .map(s::eval).findFirst().orElse(Value.exact(""));
        List<Prefix> prefixes = pathPrefixes(s);
        var missing = new java.util.LinkedHashMap<ClassOrInterfaceDeclaration, List<String>>(); // controller -> absent interfaces
        for (CompilationUnit cu : s.units()) {
            for (var m : cu.findAll(MethodDeclaration.class)) {
                if (Ann.has(m, "Bean")) functionalRoutes(m, s, contextPath, facts);
            }
            for (var c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                jaxRsResources(c, s, contextPath, jaxRsPath, facts);
                if (c.isInterface() || !Ann.has(c, "RestController", "Controller")) continue;
                Value prefix = prefixes.stream().filter(p -> p.applies().test(c)).map(Prefix::path).findFirst().orElse(Value.exact(""));
                List<Value> classPaths = classPaths(c, s);
                for (MethodDeclaration m : Mappings.mappedMethods(c, s, md -> Mappings.mvc(md, s).isPresent())) {
                    var mapping = Mappings.mvc(m, s).orElseThrow();
                    var handler = s.evidence(override(c, m).getName()); // the method line itself, below any @Override
                    for (Value cp : classPaths) {
                        for (Value mp : mapping.paths()) {
                            Value path = Mappings.joinValues(contextPath, prefix, cp, mp);
                            for (String verb : mapping.methods()) {
                                // evidence: the mapping annotation, not the first of possibly many annotations
                                facts.add(new Fact.Endpoint(verb, path, s.evidence(mapping.annotation()), "controller", handler));
                            }
                        }
                    }
                }
                var absent = c.getImplementedTypes().stream().filter(t -> s.index().resolveType(t, c).isEmpty() && !framework(t, c))
                        .map(t -> t.getName().getIdentifier()).toList();
                if (!absent.isEmpty()) missing.put(c, absent);
            }
        }
        var exposed = new java.util.HashSet<String>();
        facts.forEach(f -> { if (f instanceof Fact.Endpoint e) exposed.add(e.method() + " " + e.path().text()); });
        missing.forEach((c, names) -> {
            // only the operations of the interfaces this controller implements, minus what controllers already expose
            var spec = OpenApiSpec.endpoints(s, contextPath, java.util.Set.copyOf(names));
            if (spec.isEmpty()) {
                facts.add(new Fact.Problem("MISSING_INTERFACE", "controller " + c.getNameAsString() + " implements "
                        + String.join(", ", names) + ", which is not in sources, and no OpenAPI spec provides its operations",
                        s.evidence(c)));
            }
            for (Fact.Endpoint e : spec) if (exposed.add(e.method() + " " + e.path().text())) facts.add(e);
        });
        return facts;
    }

    private static void jaxRsResources(ClassOrInterfaceDeclaration c, Service s, Value contextPath, Value applicationPath,
                                       List<Fact> facts) {
        if (c.isInterface() || !Ann.has(c, "Path")) return;
        Value classPath = Ann.find(c, "Path").flatMap(a -> Ann.attr(a, "value")).map(s::eval).orElse(Value.exact(""));
        for (var m : c.getMethods()) {
            var verb = Ann.find(m, "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD");
            if (verb.isEmpty()) continue; // @Path without an HTTP method is a sub-resource locator
            Value methodPath = Ann.find(m, "Path").flatMap(a -> Ann.attr(a, "value")).map(s::eval).orElse(Value.exact(""));
            facts.add(new Fact.Endpoint(verb.get().getName().getIdentifier(),
                    Mappings.joinValues(contextPath, applicationPath, classPath, methodPath), s.evidence(verb.get()),
                    "jax-rs", s.evidence(m.getName())));
        }
    }

    private record Route(String method, Value path) {}

    /** Inline RouterFunctions.route/andRoute predicates and route().GET(path, handler) builder chains. */
    private static void functionalRoutes(MethodDeclaration bean, Service s, Value contextPath, List<Fact> facts) {
        for (var call : bean.findAll(MethodCallExpr.class)) {
            MethodCallExpr root = routerRoot(call).orElse(null);
            if (root == null || call.getArguments().size() < 2) continue;
            Route route;
            if ((call == root && call.getNameAsString().equals("route")) || call.getNameAsString().equals("andRoute")) {
                route = routePredicate(call.getArgument(0), s).orElse(null);
            } else if (root.getArguments().isEmpty() && HTTP_METHODS.contains(call.getNameAsString())) {
                route = new Route(call.getNameAsString(), s.eval(call.getArgument(0)));
            } else {
                continue;
            }
            if (route != null) facts.add(new Fact.Endpoint(route.method(), Mappings.joinValues(contextPath, nestedPath(call, route, s)),
                    s.evidence(call.getName()), "router", routeHandler(call.getArgument(call.getArguments().size() - 1), bean, s)));
        }
    }

    private static Optional<MethodCallExpr> routerRoot(MethodCallExpr call) {
        if ((call.getNameAsString().equals("route") || call.getNameAsString().equals("nest"))
                && staticCall(call, WEBFLUX + "RouterFunctions")) return Optional.of(call);
        return call.getScope().filter(Expression::isMethodCallExpr).map(Expression::asMethodCallExpr)
                .flatMap(InboundHttpExtractor::routerRoot);
    }

    private static Value nestedPath(MethodCallExpr call, Route route, Service s) {
        Value path = route.path();
        for (Node child = call, parent = call.getParentNode().orElse(null); parent != null;
                child = parent, parent = parent.getParentNode().orElse(null)) {
            if (parent instanceof MethodCallExpr nest && nest.getNameAsString().equals("nest")
                    && staticCall(nest, WEBFLUX + "RouterFunctions") && nest.getArguments().size() == 2
                    && nest.getArgument(1) == child)
                path = Mappings.joinValues(nestingPredicate(nest.getArgument(0), route.method(), s), path);
        }
        return path;
    }

    private static Value nestingPredicate(Expression expression, String method, Service s) {
        if (expression.isEnclosedExpr()) return nestingPredicate(expression.asEnclosedExpr().getInner(), method, s);
        var route = routePredicate(expression, s);
        if (route.isPresent()) return route.get().method().equals(method) ? route.get().path() : Value.unresolved(expression.toString());
        if (expression instanceof MethodCallExpr call) {
            if (staticCall(call, WEBFLUX + "RequestPredicates")) {
                if (call.getNameAsString().equals("path") && call.getArguments().size() == 1)
                    return s.eval(call.getArgument(0));
                if (call.getNameAsString().equals("method"))
                    return call.getArguments().stream().flatMap(e -> Mappings.enumNames(e).stream()).anyMatch(method::equals)
                            ? Value.exact("") : Value.unresolved(expression.toString());
                if (Set.of("accept", "contentType", "headers", "queryParam", "all").contains(call.getNameAsString()))
                    return Value.exact("");
            }
            if (call.getNameAsString().equals("and") && call.getArguments().size() == 1 && call.getScope().isPresent()) {
                Value left = nestingPredicate(call.getScope().get(), method, s);
                Value right = nestingPredicate(call.getArgument(0), method, s);
                if (left.text().isEmpty()) return right;
                if (right.text().isEmpty()) return left;
            }
        }
        // Unknown/disjunctive nesting must not claim that the inner path is complete.
        return Value.unresolved(expression.toString());
    }

    private static boolean staticCall(MethodCallExpr call, String owner) {
        if (call.getScope().isPresent()) {
            String scope = call.getScope().get().toString();
            return scope.equals(owner) || scope.equals(owner.substring(owner.lastIndexOf('.') + 1));
        }
        return call.findCompilationUnit().stream().flatMap(cu -> cu.getImports().stream()).anyMatch(i -> i.isStatic()
                && (i.isAsterisk() ? i.getNameAsString().equals(owner) : i.getNameAsString().equals(owner + "." + call.getNameAsString())));
    }

    private static Optional<Route> routePredicate(Expression e, Service s) {
        if (e.isEnclosedExpr()) return routePredicate(e.asEnclosedExpr().getInner(), s);
        if (!(e instanceof MethodCallExpr call)) return Optional.empty();
        if (HTTP_METHODS.contains(call.getNameAsString()) && call.getArguments().size() == 1
                && staticCall(call, WEBFLUX + "RequestPredicates")) {
            return Optional.of(new Route(call.getNameAsString(), s.eval(call.getArgument(0))));
        }
        if (call.getNameAsString().equals("and") && call.getArguments().size() == 1
                && call.getScope().orElse(null) instanceof MethodCallExpr left && call.getArgument(0) instanceof MethodCallExpr right) {
            MethodCallExpr path = left.getNameAsString().equals("path") ? left : right;
            MethodCallExpr method = path == left ? right : left;
            if (path.getNameAsString().equals("path") && method.getNameAsString().equals("method")
                    && path.getArguments().size() == 1 && method.getArguments().size() == 1
                    && staticCall(path, WEBFLUX + "RequestPredicates") && staticCall(method, WEBFLUX + "RequestPredicates")) {
                var verbs = Mappings.enumNames(method.getArgument(0));
                if (verbs.size() == 1 && HTTP_METHODS.contains(verbs.getFirst())) {
                    return Optional.of(new Route(verbs.getFirst(), s.eval(path.getArgument(0))));
                }
            }
        }
        return Optional.empty();
    }

    private static Evidence routeHandler(Expression e, MethodDeclaration bean, Service s) {
        if (!(e instanceof MethodReferenceExpr ref)) return null;
        String scope = ref.getScope().toString();
        Optional<TypeDeclaration<?>> type = scope.equals("this")
                ? bean.findAncestor(TypeDeclaration.class).map(t -> (TypeDeclaration<?>) t)
                : s.index().resolveType(s.index().declaredType(scope, ref).orElse(scope), ref);
        var methods = type.stream().flatMap(t -> t.getMethodsByName(ref.getIdentifier()).stream())
                .filter(m -> m.getParameters().size() == 1).toList();
        return methods.size() == 1 ? s.evidence(methods.getFirst().getName()) : null;
    }

    /** Spring and JDK interfaces such as {@code ErrorController} carry no mappings of the app's own. */
    private static boolean framework(com.github.javaparser.ast.type.ClassOrInterfaceType t, ClassOrInterfaceDeclaration c) {
        String name = t.getNameWithScope();
        String fqn = c.findCompilationUnit().flatMap(cu -> cu.getImports().stream()
                        .filter(i -> !i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(name)).findFirst())
                .map(i -> i.getNameAsString()).orElse(name);
        return fqn.matches("(org\\.springframework\\.(web|boot|security|context|beans|core)|javax?|jakarta)\\..*");
    }

    /** The controller's own method overriding {@code m} (same name and parameter types), else {@code m}. */
    private static MethodDeclaration override(ClassOrInterfaceDeclaration c, MethodDeclaration m) {
        return c.getMethodsByName(m.getNameAsString()).stream()
                .filter(o -> Mappings.signature(o).equals(Mappings.signature(m))).findFirst().orElse(m);
    }

    /** Class-level paths: on the controller, else on the first super type that declares one. */
    private static List<Value> classPaths(TypeDeclaration<?> type, Service s) {
        Optional<Mappings.Mapping> own = Mappings.mvc(type, s);
        if (own.isPresent()) return own.get().paths();
        for (var st : s.index().superTypes(type)) {
            List<Value> inherited = classPaths(st, s);
            if (!(inherited.size() == 1 && inherited.getFirst().text().isEmpty())) return inherited;
        }
        return List.of(Value.exact(""));
    }

    static Value contextPath(Service s) {
        for (String key : List.of("server.servlet.context-path", "spring.webflux.base-path", "spring.mvc.servlet.path")) {
            if (s.config().get(key) != null) return s.config().valueOf(key);
        }
        return Value.exact("");
    }

    /** A Spring MVC path prefix and the controllers it applies to. */
    record Prefix(Value path, Predicate<ClassOrInterfaceDeclaration> applies) {}

    /** An Ant package pattern with a wildcard, such as {@code **.controller.admin.**}. */
    private static final Pattern ANT_PACKAGE = Pattern.compile("(?=.*\\*)[\\w*]+(?:\\.[\\w*]+)+");

    /**
     * {@code PathMatchConfigurer.addPathPrefix(prefix, predicate)} and {@code setPathPrefixes(Map.of(prefix, predicate, ...))},
     * in call order, since Spring applies the first prefix whose predicate matches. When {@code setPathPrefixes} gets a
     * map built elsewhere, the prefixes come from {@link #packagePrefixes}.
     */
    static List<Prefix> pathPrefixes(Service s) {
        var out = new ArrayList<Prefix>();
        boolean opaque = false;
        for (CompilationUnit cu : s.units()) {
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                var args = call.getArguments();
                if (call.getNameAsString().equals("addPathPrefix") && args.size() == 2) {
                    out.add(new Prefix(s.eval(args.get(0)), handlerTypes(args.get(1))));
                } else if (call.getNameAsString().equals("setPathPrefixes") && args.size() == 1) {
                    if (args.get(0) instanceof MethodCallExpr map && map.getNameAsString().equals("of")) {
                        for (int i = 0; i + 1 < map.getArguments().size(); i += 2) {
                            out.add(new Prefix(s.eval(map.getArgument(i)), handlerTypes(map.getArgument(i + 1))));
                        }
                    } else {
                        opaque = true;
                    }
                }
            }
        }
        if (opaque) out.addAll(packagePrefixes(s));
        return out;
    }

    /**
     * The controllers a {@code HandlerTypePredicate} or lambda selects: by the annotation classes given to
     * {@code forAnnotation}/{@code isAnnotationPresent} and the packages given to {@code forBasePackage}. Any other
     * predicate is taken to match every controller.
     */
    private static Predicate<ClassOrInterfaceDeclaration> handlerTypes(Expression e) {
        var calls = e.findAll(MethodCallExpr.class);
        String[] annotations = calls.stream().filter(m -> m.getNameAsString().matches("forAnnotation|isAnnotationPresent"))
                .flatMap(m -> m.getArguments().stream()).filter(Expression::isClassExpr)
                .map(a -> JavaIndex.simpleName(a.asClassExpr().getType())).toArray(String[]::new);
        List<String> packages = calls.stream().filter(m -> m.getNameAsString().equals("forBasePackage"))
                .flatMap(m -> m.getArguments().stream()).filter(Expression::isStringLiteralExpr)
                .map(a -> a.asStringLiteralExpr().getValue()).toList();
        return c -> (annotations.length == 0 || Ann.has(c, annotations))
                && (packages.isEmpty() || packages.stream().anyMatch(p -> packageOf(c).equals(p) || packageOf(c).startsWith(p + ".")));
    }

    /**
     * yudao's {@code setPathPrefixes}, built from {@code @ConfigurationProperties} defaults such as
     * {@code new Api("/admin-api", "**.controller.admin.**")}: a prefix for the {@code @RestController}s in packages
     * matching an Ant pattern with "." separators. A config key such as {@code yudao.web.admin-api.prefix}
     * overrides the default prefix.
     */
    // shortcut: the package pattern is always the constructor default; read <prefix>.<field>.controller if a fork overrides it.
    private static List<Prefix> packagePrefixes(Service s) {
        var out = new ArrayList<Prefix>();
        for (CompilationUnit cu : s.units()) {
            for (ObjectCreationExpr o : cu.findAll(ObjectCreationExpr.class)) {
                var a = o.getArguments();
                if (a.size() == 2 && a.get(0) instanceof StringLiteralExpr p && p.getValue().startsWith("/")
                        && a.get(1) instanceof StringLiteralExpr pkg && ANT_PACKAGE.matcher(pkg.getValue()).matches()) {
                    Pattern packages = antPackage(pkg.getValue());
                    String key = overrideKey(s, o);
                    Value prefix = key != null && s.config().get(key) != null ? s.config().valueOf(key) : s.eval(p);
                    out.add(new Prefix(prefix, c -> Ann.has(c, "RestController") && packages.matcher("." + packageOf(c)).matches()));
                }
            }
        }
        return out;
    }

    /** "yudao.web.admin-api.prefix" for {@code adminApi = new Api(..)} in {@code @ConfigurationProperties("yudao.web")}. */
    private static String overrideKey(Service s, ObjectCreationExpr o) {
        if (!(o.getParentNode().orElse(null) instanceof VariableDeclarator v)) return null;
        return o.findAncestor(ClassOrInterfaceDeclaration.class).flatMap(c -> Ann.find(c, "ConfigurationProperties"))
                .flatMap(ann -> Ann.attr(ann, "prefix", "value")).map(e -> s.eval(e).text())
                .map(prefix -> prefix + "." + v.getNameAsString().replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase() + ".prefix")
                .orElse(null);
    }

    /** Matches "." + package name: {@code **} is any number of segments, {@code *} part of one. */
    static Pattern antPackage(String ant) {
        var re = new StringBuilder();
        for (String seg : ant.split("\\.")) {
            re.append(seg.equals("**") ? "(?:\\.[^.]+)*" : "\\." + Pattern.quote(seg).replace("*", "\\E[^.]*\\Q"));
        }
        return Pattern.compile(re.toString());
    }

    private static String packageOf(ClassOrInterfaceDeclaration c) {
        return c.findCompilationUnit().flatMap(CompilationUnit::getPackageDeclaration).map(p -> p.getNameAsString()).orElse("");
    }
}
