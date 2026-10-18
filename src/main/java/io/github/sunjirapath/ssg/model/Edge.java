package io.github.sunjirapath.ssg.model;

import java.util.List;
import java.util.SortedMap;

/**
 * A directed edge between two node ids.
 *
 * @param attrs details such as HTTP method, called path or client kind
 */
public record Edge(Kind kind, String from, String to, Confidence confidence, SortedMap<String, String> attrs,
                   List<Evidence> evidence) {

    // An enum, not a sealed hierarchy: edge kinds carry no data of their own.
    public enum Kind { EXPOSES, CALLS, PRODUCES, CONSUMES, OWNS, USES }
}
