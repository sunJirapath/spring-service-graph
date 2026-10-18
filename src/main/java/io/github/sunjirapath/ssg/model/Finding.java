package io.github.sunjirapath.ssg.model;

import java.util.List;

/** Something a reader should look at: contract drift, unresolved values, scan problems. */
public record Finding(Kind kind, String service, String message, List<Evidence> evidence) {

    public enum Kind { CONTRACT_DRIFT, UNRESOLVED, MISSING_INTERFACE, PARSE_ERROR }
}
