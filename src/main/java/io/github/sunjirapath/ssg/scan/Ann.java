package io.github.sunjirapath.ssg.scan;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

import java.util.Optional;
import java.util.Set;

/**
 * Annotation lookup by simple name. There is no classpath, so {@code @GetMapping} and
 * {@code @org.springframework...GetMapping} both match "GetMapping".
 */
public final class Ann {

    private Ann() {}

    public static Optional<AnnotationExpr> find(NodeWithAnnotations<?> n, String... simpleNames) {
        var names = Set.of(simpleNames);
        return n.getAnnotations().stream().filter(a -> names.contains(a.getName().getIdentifier())).findFirst();
    }

    public static boolean has(NodeWithAnnotations<?> n, String... simpleNames) {
        return find(n, simpleNames).isPresent();
    }

    /** The first attribute present among {@code names}; "value" also matches a single-member annotation. */
    public static Optional<Expression> attr(AnnotationExpr a, String... names) {
        for (String name : names) {
            if (a instanceof SingleMemberAnnotationExpr s && name.equals("value")) return Optional.of(s.getMemberValue());
            if (a instanceof NormalAnnotationExpr n) {
                var hit = n.getPairs().stream().filter(p -> p.getNameAsString().equals(name)).findFirst();
                if (hit.isPresent()) return Optional.of(hit.get().getValue());
            }
        }
        return Optional.empty();
    }
}
