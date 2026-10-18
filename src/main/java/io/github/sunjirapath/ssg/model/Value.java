package io.github.sunjirapath.ssg.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A folded string value.
 *
 * @param text       the value; parts that could not be folded appear as {@link #UNKNOWN}
 * @param confidence how the value was obtained
 * @param raw        the source expression, kept so unresolved values can be shown as written
 * @param via        where constants or properties used by the value are defined
 */
public record Value(String text, Confidence confidence, String raw, List<Evidence> via) {

    public static final String UNKNOWN = "{?}";

    public Value {
        via = List.copyOf(via);
    }

    public static Value exact(String text) {
        return new Value(text, Confidence.EXACT, text, List.of());
    }

    public static Value unresolved(String raw) {
        return new Value(UNKNOWN, Confidence.UNRESOLVED, raw, List.of());
    }

    public Value withConfidence(Confidence c, Evidence definedAt) {
        var v = new ArrayList<>(via);
        if (definedAt != null) v.add(definedAt);
        return new Value(text, confidence.and(c), raw, v);
    }

    public Value concat(Value other, String rawExpr) {
        var v = new ArrayList<>(via);
        v.addAll(other.via);
        return new Value(text + other.text, confidence.and(other.confidence), rawExpr, v);
    }

    public boolean resolved() {
        return confidence != Confidence.UNRESOLVED;
    }
}
