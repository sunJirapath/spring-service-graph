package io.github.sunjirapath.ssg.extract;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.scan.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Endpoint-level reach: walks in-service method calls from each endpoint handler (breadth first,
 * depth-limited) and records which sinks it reaches. Sinks are facts other extractors found: outbound
 * calls and produced topics located inside a reached method, and repositories whose type a reached
 * method calls (so Spring Data's synthesized methods like {@code findByEmail} count too).
 *
 * <p>Calls are resolved without a symbol solver: {@code this}/unqualified calls to the enclosing type,
 * {@code field.m()} through the field's declared type, {@code Type.m()} statically, and interfaces
 * to their implementations in the service. Calls on chains, lambdas and method references are not
 * followed.
 *
 * <p>shortcut: sinks are matched to methods by file and line (evidence has no column), so two methods
 * declared on the same source line cannot be told apart; add columns to Evidence if that ever matters.
 */
public final class ReachAnalysis {

    static final int MAX_DEPTH = 6;

    private record Step(MethodDeclaration method, String chain, int depth) {}

    private final Service s;
    private final List<Fact> sinks = new ArrayList<>();
    private final Map<String, Evidence> repositories = new LinkedHashMap<>(); // simple name -> TableUse evidence
    private final Map<TypeDeclaration<?>, List<TypeDeclaration<?>>> implementations = new IdentityHashMap<>();

    private ReachAnalysis(Service s, List<Fact> facts) {
        this.s = s;
        for (Fact f : facts) {
            switch (f) {
                case Fact.Call c -> sinks.add(c);
                case Fact.TopicUse t when t.produce() -> sinks.add(t);
                case Fact.TableUse t when t.via().startsWith("repository ") ->
                        repositories.putIfAbsent(t.via().substring("repository ".length()), t.evidence());
                default -> { }
            }
        }
        for (CompilationUnit cu : s.units()) {
            for (var c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                for (var st : s.index().superTypes(c)) implementations.computeIfAbsent(st, k -> new ArrayList<>()).add(c);
            }
        }
    }

    public static List<Fact> analyze(Service s, List<Fact> facts) {
        var r = new ReachAnalysis(s, facts);
        var out = new ArrayList<Fact>();
        for (Fact f : facts) {
            if (f instanceof Fact.Endpoint e && e.handler() != null) r.reach(e, out);
        }
        return out;
    }

    private void reach(Fact.Endpoint e, List<Fact> out) {
        var start = methodAt(e.handler());
        if (start.isEmpty()) return;
        var seen = new IdentityHashMap<MethodDeclaration, Boolean>();
        var reached = new LinkedHashMap<Evidence, String>(); // sink -> shortest chain
        var queue = new ArrayDeque<Step>();
        queue.add(new Step(start.get(), name(start.get()), 0));
        seen.put(start.get(), true);
        while (!queue.isEmpty()) {
            Step step = queue.poll();
            for (Fact sink : sinks) {
                Evidence ev = evidenceOf(sink);
                if (contains(step.method, ev)) reached.putIfAbsent(ev, step.chain);
            }
            if (step.depth >= MAX_DEPTH) continue;
            for (MethodCallExpr call : step.method.findAll(MethodCallExpr.class)) {
                scopeType(call).map(t -> repositories.get(t.getNameAsString()))
                        .ifPresent(repo -> reached.putIfAbsent(repo, step.chain + " → " + call.getScope().get() + "." + call.getNameAsString()));
                for (MethodDeclaration next : targets(call)) {
                    if (seen.put(next, true) == null) queue.add(new Step(next, step.chain + " → " + name(next), step.depth + 1));
                }
            }
        }
        reached.forEach((sink, chain) -> out.add(new Fact.Reach(e.method(), e.path().text(), e.handler(), sink, chain)));
    }

    /** Methods {@code call} may dispatch to, by name and arity. */
    private List<MethodDeclaration> targets(MethodCallExpr call) {
        Optional<TypeDeclaration<?>> type = call.getScope().isEmpty() || call.getScope().get() instanceof ThisExpr
                || call.getScope().get() instanceof com.github.javaparser.ast.expr.SuperExpr // super.m(): enclosing type and its supers
                ? call.findAncestor(TypeDeclaration.class).map(t -> (TypeDeclaration<?>) t)
                : scopeType(call);
        if (type.isEmpty()) return List.of();
        var out = new ArrayList<MethodDeclaration>();
        collect(type.get(), call.getNameAsString(), call.getArguments().size(), out, Set.of(), 0);
        return out;
    }

    private void collect(TypeDeclaration<?> t, String name, int arity, List<MethodDeclaration> out,
                         Set<TypeDeclaration<?>> visiting, int depth) {
        if (depth > 64 || visiting.contains(t)) return;
        var path = Stream.concat(visiting.stream(), Stream.of(t)).collect(java.util.stream.Collectors.toSet());
        t.getMethodsByName(name).stream().filter(m -> m.getParameters().size() == arity).forEach(out::add);
        for (var st : s.index().superTypes(t)) collect(st, name, arity, out, path, depth + 1);
        // dispatch from an interface or abstract class to its implementations in this service
        for (var impl : implementations.getOrDefault(t, List.of())) {
            impl.getMethodsByName(name).stream().filter(m -> m.getParameters().size() == arity && !out.contains(m)).forEach(out::add);
        }
    }

    /** The declared type of the call's receiver, when it is a field/variable or a type name. */
    private Optional<TypeDeclaration<?>> scopeType(MethodCallExpr call) {
        if (call.getScope().isEmpty()) return Optional.empty();
        Expression scope = call.getScope().get();
        String name = switch (scope) {
            case NameExpr n -> n.getNameAsString();
            case FieldAccessExpr f when f.getScope() instanceof ThisExpr -> f.getNameAsString();
            default -> null;
        };
        if (name == null) {
            return scope instanceof FieldAccessExpr f ? s.index().resolveType(f.toString(), call) : Optional.empty();
        }
        Optional<String> declared = s.index().declaredType(name, call);
        return declared.isPresent() ? s.index().resolveType(declared.get(), call) : s.index().resolveType(name, call);
    }

    private Optional<MethodDeclaration> methodAt(Evidence ev) {
        return s.units().stream().filter(cu -> s.repo().relative(s.index().fileOf(cu)).equals(ev.file()))
                .flatMap(cu -> cu.findAll(MethodDeclaration.class).stream())
                .filter(m -> m.getBegin().map(p -> p.line).orElse(-1) == ev.line()).findFirst();
    }

    private boolean contains(MethodDeclaration m, Evidence ev) {
        var cu = m.findCompilationUnit().orElseThrow();
        if (!s.repo().relative(s.index().fileOf(cu)).equals(ev.file())) return false;
        int begin = m.getBegin().map(p -> p.line).orElse(-1);
        int end = m.getEnd().map(p -> p.line).orElse(-1);
        return ev.line() >= begin && ev.line() <= end;
    }

    private static Evidence evidenceOf(Fact f) {
        return switch (f) {
            case Fact.Call c -> c.evidence();
            case Fact.TopicUse t -> t.evidence();
            default -> throw new IllegalArgumentException(f.toString());
        };
    }

    private static String name(MethodDeclaration m) {
        String type = m.findAncestor(TypeDeclaration.class).map(t -> ((TypeDeclaration<?>) t).getNameAsString()).orElse("?");
        return type + "." + m.getNameAsString();
    }
}
