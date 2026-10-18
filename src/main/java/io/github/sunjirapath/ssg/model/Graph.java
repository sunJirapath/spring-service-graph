package io.github.sunjirapath.ssg.model;

import java.util.List;

/** The scan result. All lists are sorted so output is deterministic. */
public record Graph(List<Node> nodes, List<Edge> edges, List<Finding> findings) {}
