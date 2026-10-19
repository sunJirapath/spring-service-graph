package io.github.sunjirapath.ssg.extract;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import io.github.sunjirapath.ssg.scan.Service;

import java.util.Locale;
import java.util.Set;

/** Shared by extractors so that none of them depends on another. */
final class Receivers {

    private Receivers() {}

    /**
     * Whether a call's receiver ({@code name} or {@code this.name}) is declared as one of {@code types};
     * when its declaration is not in sources, whether its name contains {@code nameHint}.
     */
    static boolean isReceiver(Service s, Expression scope, Set<String> types, String nameHint) {
        String name = switch (scope) {
            case NameExpr n -> n.getNameAsString();
            case FieldAccessExpr f -> f.getNameAsString();
            default -> "";
        };
        if (name.isEmpty()) return false;
        return s.index().declaredType(name, scope).map(types::contains)
                .orElseGet(() -> name.toLowerCase(Locale.ROOT).contains(nameHint));
    }
}
