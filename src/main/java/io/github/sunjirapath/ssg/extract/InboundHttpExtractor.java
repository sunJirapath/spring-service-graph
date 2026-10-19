package io.github.sunjirapath.ssg.extract;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Value;
import io.github.sunjirapath.ssg.scan.Ann;
import io.github.sunjirapath.ssg.scan.JavaIndex;
import io.github.sunjirapath.ssg.scan.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * {@code @RestController}/{@code @Controller} endpoints, including mappings declared only on
 * implemented interfaces (openapi-generator {@code interfaceOnly}). When such an interface is
 * not in the checkout (an unbuilt repo), endpoints come from the OpenAPI spec instead.
 */
public final class InboundHttpExtractor implements Extractor {

    @Override
    public List<Fact> extract(Service s) {
        var facts = new ArrayList<Fact>();
        Value contextPath = contextPath(s);
        List<Prefix> prefixes = pathPrefixes(s);
        var missing = new java.util.LinkedHashMap<ClassOrInterfaceDeclaration, List<String>>(); // controller -> absent interfaces
        for (CompilationUnit cu : s.units()) {
            for (var c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
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

    /** Spring and JDK interfaces such as {@code ErrorController} carry no mappings of the app's own. */
    private static boolean framework(com.github.javaparser.ast.type.ClassOrInterfaceType t, ClassOrInterfaceDeclaration c) {
        String name = t.getNameWithScope();
        String fqn = c.findCompilationUnit().flatMap(cu -> cu.getImports().stream()
                        .filter(i -> !i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(name)).findFirst())
                .map(i -> i.getNameAsString()).orElse(name);
        return fqn.matches("(org\\.springframework|javax?|jakarta)\\..*");
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
     * matching an Ant pattern with "." separators.
     */
    // shortcut: reads the constructor defaults only, not yudao.web.*.prefix overrides in config; read those if a fork sets them.
    private static List<Prefix> packagePrefixes(Service s) {
        var out = new ArrayList<Prefix>();
        for (CompilationUnit cu : s.units()) {
            for (ObjectCreationExpr o : cu.findAll(ObjectCreationExpr.class)) {
                var a = o.getArguments();
                if (a.size() == 2 && a.get(0) instanceof StringLiteralExpr p && p.getValue().startsWith("/")
                        && a.get(1) instanceof StringLiteralExpr pkg && ANT_PACKAGE.matcher(pkg.getValue()).matches()) {
                    Pattern packages = antPackage(pkg.getValue());
                    out.add(new Prefix(s.eval(p), c -> Ann.has(c, "RestController") && packages.matcher("." + packageOf(c)).matches()));
                }
            }
        }
        return out;
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
