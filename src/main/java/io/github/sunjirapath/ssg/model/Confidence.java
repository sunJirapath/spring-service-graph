package io.github.sunjirapath.ssg.model;

/** How a value was obtained. Declared from most to least certain. */
public enum Confidence {
    /** A literal in source or config. */
    EXACT,
    /** Folded from constants, field initializers or property placeholders. */
    RESOLVED,
    /** Dynamic: built at runtime or otherwise not foldable. */
    UNRESOLVED;

    /** The weaker of the two: a value is only as certain as its least certain part. */
    public Confidence and(Confidence other) {
        return compareTo(other) >= 0 ? this : other;
    }
}
