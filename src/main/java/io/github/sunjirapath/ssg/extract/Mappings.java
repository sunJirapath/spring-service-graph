package io.github.sunjirapath.ssg.extract;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import io.github.sunjirapath.ssg.model.Value;
import io.github.sunjirapath.ssg.scan.Ann;
import io.github.sunjirapath.ssg.scan.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Spring MVC and HTTP-interface mapping annotations, shared by inbound and outbound extraction. */
final class Mappings {

    /** HTTP methods ({@code *} = any) and paths declared by one annotation. */
    record Mapping(List<String> methods, List<Value> paths, AnnotationExpr annotation) {}

    private static final Map<String, String> MVC = Map.of("GetMapping", "GET", "PostMapping", "POST",
            "PutMapping", "PUT", "DeleteMapping", "DELETE", "PatchMapping", "PATCH", "RequestMapping", "*");
    private static final Map<String, String> EXCHANGE = Map.of("GetExchange", "GET", "PostExchange", "POST",
            "PutExchange", "PUT", "DeleteExchange", "DELETE", "PatchExchange", "PATCH", "HttpExchange", "*");

    private Mappings() {}

    static Optional<Mapping> mvc(NodeWithAnnotations<?> n, Service s) {
        return find(n, s, MVC, "value", "path");
    }

    static Optional<Mapping> exchange(NodeWithAnnotations<?> n, Service s) {
        return find(n, s, EXCHANGE, "value", "url");
    }

    private static Optional<Mapping> find(NodeWithAnnotations<?> n, Service s, Map<String, String> kinds,
                                          String... pathAttrs) {
        for (AnnotationExpr a : n.getAnnotations()) {
            String verb = kinds.get(a.getName().getIdentifier());
            if (verb == null) continue;
            List<String> methods = verb.equals("*")
                    ? Ann.attr(a, "method").map(Mappings::enumNames).filter(l -> !l.isEmpty()).orElse(List.of("*")) // method = {} means any
                    : List.of(verb);
            List<Value> paths = Ann.attr(a, pathAttrs).map(s::evalAll).orElse(List.of(Value.exact("")));
            return Optional.of(new Mapping(methods, paths.isEmpty() ? List.of(Value.exact("")) : paths, a));
        }
        return Optional.empty();
    }

    /** {@code RequestMethod.GET}, {@code GET}, {@code "GET"} or arrays of them. */
    static List<String> enumNames(Expression e) {
        if (e instanceof ArrayInitializerExpr a) return a.getValues().stream().flatMap(v -> enumNames(v).stream()).toList();
        if (e instanceof FieldAccessExpr f) return List.of(f.getNameAsString());
        // a static import like GET, not a variable holding the method
        if (e instanceof NameExpr n && n.getNameAsString().matches("[A-Z]+")) return List.of(n.getNameAsString());
        if (e.isStringLiteralExpr()) return List.of(e.asStringLiteralExpr().asString().toUpperCase());
        return List.of("*");
    }

    /**
     * Methods of {@code type} and its super types (found in sources) that carry a mapping.
     * A method declared lower in the hierarchy shadows one with the same name and arity above it,
     * but an un-annotated override still inherits the annotation from the interface.
     */
    static List<MethodDeclaration> mappedMethods(TypeDeclaration<?> type, Service s,
                                                 java.util.function.Predicate<MethodDeclaration> mapped) {
        var out = new ArrayList<MethodDeclaration>();
        collect(type, s, mapped, out, new HashSet<>(), new HashSet<>(), 0);
        return out;
    }

    private static void collect(TypeDeclaration<?> type, Service s, java.util.function.Predicate<MethodDeclaration> mapped,
                                List<MethodDeclaration> out, java.util.Set<String> seen,
                                java.util.Set<TypeDeclaration<?>> visited, int depth) {
        if (!visited.add(type)) return; // the visited set already stops cycles
        for (MethodDeclaration m : type.getMethods()) {
            String sig = signature(m);
            if (mapped.test(m) && seen.add(sig)) out.add(m);
        }
        for (var st : s.index().superTypes(type)) collect(st, s, mapped, out, seen, visited, depth + 1);
    }

    /** Name + parameter types, so overloads stay distinct while overrides match. */
    static String signature(MethodDeclaration m) {
        return m.getNameAsString() + m.getParameters().stream()
                .map(p -> io.github.sunjirapath.ssg.scan.JavaIndex.simpleName(p.getType())).toList();
    }

    /** Joins path segments: adds leading slashes, drops trailing ones, keeps "/" for the root. */
    static String join(String... parts) {
        var sb = new StringBuilder();
        for (String p : parts) {
            String t = p.strip();
            while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
            if (t.isEmpty()) continue;
            if (!t.startsWith("/")) sb.append('/');
            sb.append(t);
        }
        String out = sb.toString().replaceAll("/{2,}", "/");
        return out.isEmpty() ? "/" : out;
    }

    static Value joinValues(Value... parts) {
        var texts = new String[parts.length];
        var via = new ArrayList<io.github.sunjirapath.ssg.model.Evidence>();
        var conf = io.github.sunjirapath.ssg.model.Confidence.EXACT;
        var raw = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            texts[i] = parts[i].text();
            via.addAll(parts[i].via());
            conf = conf.and(parts[i].confidence());
            if (!parts[i].raw().isEmpty()) raw.append(raw.isEmpty() ? "" : " + ").append(parts[i].raw());
        }
        return new Value(join(texts), conf, raw.toString(), via);
    }
}
