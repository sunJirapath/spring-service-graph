package io.github.sunjirapath.ssg.output;

import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read-only lookups over a graph for the writers. */
final class GraphView {

    final Graph graph;
    final Map<String, Node> nodes;

    GraphView(Graph graph) {
        this.graph = graph;
        this.nodes = graph.nodes().stream().collect(Collectors.toMap(Node::id, Function.identity()));
    }

    List<Node.Service> services() {
        return graph.nodes().stream().filter(Node.Service.class::isInstance).map(Node.Service.class::cast).toList();
    }

    List<Edge> from(String id, Edge.Kind kind) {
        return graph.edges().stream().filter(e -> e.from().equals(id) && e.kind() == kind).toList();
    }

    List<Edge> to(String id, Edge.Kind kind) {
        return graph.edges().stream().filter(e -> e.to().equals(id) && e.kind() == kind).toList();
    }

    /** The service or external system a node belongs to, for service-level views. */
    String owner(String id) {
        return switch (nodes.get(id)) {
            case Node.Service s -> s.name();
            case Node.Endpoint e -> e.service();
            case Node.Table t -> t.service();
            case Node.External x -> x.name();
            case Node.Topic t -> t.name();
            case null -> id;
        };
    }

    String label(String id) {
        return switch (nodes.get(id)) {
            case Node.Service s -> s.name();
            case Node.Endpoint e -> e.service() + " " + e.method() + " " + e.path();
            case Node.Table t -> t.name();
            case Node.External x -> x.name();
            case Node.Topic t -> t.name();
            case null -> id;
        };
    }

    /** File-system-safe name for per-service files. */
    static String fileName(String service) {
        return service.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
