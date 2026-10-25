package io.github.sunjirapath.ssg.output;

import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Read-only lookups over a graph for the writers. */
final class GraphView {

    private static final Pattern WINDOWS_DEVICE = Pattern.compile("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?");

    final Graph graph;
    final Map<String, Node> nodes;
    private final Map<String, String> fileNames = new HashMap<>();

    GraphView(Graph graph) {
        this.graph = graph;
        this.nodes = graph.nodes().stream().collect(Collectors.toMap(Node::id, Function.identity()));
        var used = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        // Reserve familiar names first; sorted allocation keeps collisions stable on every filesystem.
        var names = services().stream().map(Node.Service::name)
                .sorted(Comparator.comparing((String name) -> !name.equals(safeName(name)) || WINDOWS_DEVICE.matcher(name).matches())
                        .thenComparing(Comparator.naturalOrder())).toList();
        for (String service : names) {
            String base = safeName(service);
            String name = base;
            for (int suffix = 2; WINDOWS_DEVICE.matcher(name).matches() || !used.add(name); suffix++) {
                // Windows reserves device names even with extensions, so suffix the stem.
                int dot = base.indexOf('.');
                name = (dot < 0 ? base : base.substring(0, dot)) + "-" + suffix + (dot < 0 ? "" : base.substring(dot));
            }
            fileNames.put(service, name);
        }
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

    /** A consistent, collision-free basename for pages, index links and diagrams. */
    String fileName(String service) {
        return fileNames.get(service);
    }

    private static String safeName(String service) {
        return service.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
