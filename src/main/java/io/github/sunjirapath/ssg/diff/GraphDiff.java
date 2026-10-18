package io.github.sunjirapath.ssg.diff;

import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares two scans (base = before the change, head = after) and writes a Markdown impact report:
 * breaking changes first, then what was added or removed. Evidence line numbers are ignored when
 * matching, so moved code is not reported as a change.
 */
public final class GraphDiff {

    private final Graph base;
    private final Graph head;
    private final Map<String, Node> baseNodes;
    private final Map<String, Node> headNodes;

    private GraphDiff(Graph base, Graph head) {
        this.base = base;
        this.head = head;
        this.baseNodes = base.nodes().stream().collect(Collectors.toMap(Node::id, Function.identity()));
        this.headNodes = head.nodes().stream().collect(Collectors.toMap(Node::id, Function.identity()));
    }

    public static String report(Graph base, Graph head) {
        return new GraphDiff(base, head).render();
    }

    /** Identity of an edge across scans: everything but evidence, confidence and the reach chain. */
    static String key(Edge e) {
        var attrs = new TreeMap<>(e.attrs());
        attrs.remove("chain");
        return e.kind() + "|" + e.from() + "|" + e.to() + "|" + attrs;
    }

    private static String key(Finding f) {
        return f.kind() + "|" + f.service() + "|" + f.message();
    }

    private String render() {
        var baseEdges = index(base.edges(), GraphDiff::key);
        var headEdges = index(head.edges(), GraphDiff::key);
        var addedEdges = minus(headEdges, baseEdges);
        var removedEdges = minus(baseEdges, headEdges);
        var addedNodes = minus(headNodes, baseNodes);
        var removedNodes = minus(baseNodes, headNodes);
        var baseFindings = index(base.findings(), GraphDiff::key);
        var headFindings = index(head.findings(), GraphDiff::key);
        var newFindings = minus(headFindings, baseFindings);
        var fixedFindings = minus(baseFindings, headFindings);

        var md = new StringBuilder("# Impact report\n\n");
        md.append("| | Base | Head |\n|---|---|---|\n");
        for (String kind : List.of("service", "endpoint", "topic", "table", "external")) {
            md.append("| ").append(kind).append("s | ").append(count(base, kind)).append(" | ").append(count(head, kind)).append(" |\n");
        }
        md.append("| edges | ").append(base.edges().size()).append(" | ").append(head.edges().size()).append(" |\n");
        md.append("| findings | ").append(base.findings().size()).append(" | ").append(head.findings().size()).append(" |\n\n");

        breaking(md, removedNodes, newFindings);

        nodes(md, "Endpoints added", addedNodes, Node.Endpoint.class, head);
        nodes(md, "Endpoints removed", removedNodes, Node.Endpoint.class, base);
        nodes(md, "Services added", addedNodes, Node.Service.class, head);
        nodes(md, "Services removed", removedNodes, Node.Service.class, base);
        edges(md, "Calls added", addedEdges, headNodes, Edge.Kind.CALLS);
        edges(md, "Calls removed", removedEdges, baseNodes, Edge.Kind.CALLS);
        edges(md, "Messaging added", addedEdges, headNodes, Edge.Kind.PRODUCES, Edge.Kind.CONSUMES);
        edges(md, "Messaging removed", removedEdges, baseNodes, Edge.Kind.PRODUCES, Edge.Kind.CONSUMES);
        edges(md, "Data added", addedEdges, headNodes, Edge.Kind.OWNS, Edge.Kind.USES);
        edges(md, "Data removed", removedEdges, baseNodes, Edge.Kind.OWNS, Edge.Kind.USES);
        edges(md, "Reach added", addedEdges, headNodes, Edge.Kind.REACHES);
        edges(md, "Reach removed", removedEdges, baseNodes, Edge.Kind.REACHES);
        boolean confidenceChanged = confidence(md, baseEdges, headEdges);
        findings(md, "New findings", newFindings);
        findings(md, "Resolved findings", fixedFindings);

        if (addedNodes.isEmpty() && removedNodes.isEmpty() && addedEdges.isEmpty() && removedEdges.isEmpty()
                && newFindings.isEmpty() && fixedFindings.isEmpty() && !confidenceChanged) {
            md.append("No changes.\n");
        }
        return md.toString();
    }

    // ---------------------------------------------------------------- breaking changes

    private void breaking(StringBuilder md, Map<String, Node> removedNodes, Map<String, Finding> newFindings) {
        var lines = new ArrayList<String>();
        for (Node n : removedNodes.values()) {
            if (!(n instanceof Node.Endpoint) && !(n instanceof Node.Service)) continue;
            // callers in base that still exist in head now call something that is gone
            var callers = incoming(base, n.id(), Edge.Kind.CALLS).stream().map(e -> owner(baseNodes, e.from()))
                    .filter(c -> headNodes.containsKey(Node.Service.idOf(c)) && !c.equals(owner(baseNodes, n.id())))
                    .collect(Collectors.toCollection(TreeSet::new));
            if (!callers.isEmpty()) lines.add("Removed " + label(n) + " is still called by " + String.join(", ", callers));
        }
        for (Node n : baseNodes.values()) {
            if (!(n instanceof Node.Topic t) || !headNodes.containsKey(t.id())) continue;
            boolean producedBefore = !incoming(base, t.id(), Edge.Kind.PRODUCES).isEmpty();
            boolean producedNow = !incoming(head, t.id(), Edge.Kind.PRODUCES).isEmpty();
            var consumers = incoming(head, t.id(), Edge.Kind.CONSUMES).stream().map(e -> owner(headNodes, e.from()))
                    .collect(Collectors.toCollection(TreeSet::new));
            if (producedBefore && !producedNow && !consumers.isEmpty()) {
                lines.add("Topic `" + t.name() + "` has no producer any more but is still consumed by " + String.join(", ", consumers));
            }
        }
        for (Finding f : newFindings.values()) {
            if (f.kind() == Finding.Kind.CONTRACT_DRIFT) lines.add("New contract drift in " + f.service() + ": " + f.message() + " — " + ev(f.evidence()));
        }
        md.append("## Breaking changes\n\n");
        if (lines.isEmpty()) md.append("_None found._\n\n");
        else lines.stream().sorted().forEach(l -> md.append("- ").append(l).append('\n'));
        if (!lines.isEmpty()) md.append('\n');
    }

    // ---------------------------------------------------------------- sections

    private <T extends Node> void nodes(StringBuilder md, String title, Map<String, Node> changed, Class<T> type, Graph g) {
        var rows = changed.values().stream().filter(type::isInstance).toList();
        if (rows.isEmpty()) return;
        md.append("## ").append(title).append("\n\n");
        Map<String, Node> nodes = g == base ? baseNodes : headNodes;
        for (Node n : rows) {
            var callers = incoming(g, n.id(), Edge.Kind.CALLS).stream().map(e -> owner(nodes, e.from()))
                    .collect(Collectors.toCollection(TreeSet::new));
            md.append("- ").append(label(n));
            if (!callers.isEmpty()) md.append(" — called by ").append(String.join(", ", callers));
            md.append('\n');
        }
        md.append('\n');
    }

    private void edges(StringBuilder md, String title, Map<String, Edge> changed, Map<String, Node> nodes, Edge.Kind... kinds) {
        var wanted = List.of(kinds);
        var rows = changed.values().stream().filter(e -> wanted.contains(e.kind())).toList();
        if (rows.isEmpty()) return;
        md.append("## ").append(title).append("\n\n");
        for (Edge e : rows) {
            md.append("- ").append(labelOf(nodes, e.from())).append(" **").append(e.kind().name().toLowerCase()).append("** ")
                    .append(labelOf(nodes, e.to()));
            String detail = e.attrs().entrySet().stream().filter(a -> !a.getKey().equals("chain"))
                    .map(a -> a.getKey() + "=" + a.getValue()).collect(Collectors.joining(", "));
            if (!detail.isEmpty()) md.append(" (").append(detail).append(')');
            md.append(" `").append(e.confidence()).append("` — ").append(ev(e.evidence())).append('\n');
        }
        md.append('\n');
    }

    private boolean confidence(StringBuilder md, Map<String, Edge> baseEdges, Map<String, Edge> headEdges) {
        var rows = new ArrayList<String>();
        headEdges.forEach((k, h) -> {
            Edge b = baseEdges.get(k);
            if (b != null && b.confidence() != h.confidence()) {
                rows.add(labelOf(headNodes, h.from()) + " " + h.kind().name().toLowerCase() + " " + labelOf(headNodes, h.to())
                        + ": " + b.confidence() + " → " + h.confidence());
            }
        });
        if (rows.isEmpty()) return false;
        md.append("## Confidence changes\n\n");
        rows.forEach(r -> md.append("- ").append(r).append('\n'));
        md.append('\n');
        return true;
    }

    private static void findings(StringBuilder md, String title, Map<String, Finding> changed) {
        if (changed.isEmpty()) return;
        md.append("## ").append(title).append("\n\n");
        for (Finding f : changed.values()) {
            md.append("- **").append(f.kind()).append("** `").append(f.service()).append("`: ").append(f.message())
                    .append(" — ").append(ev(f.evidence())).append('\n');
        }
        md.append('\n');
    }

    // ---------------------------------------------------------------- helpers

    private static <T> Map<String, T> index(List<T> items, Function<T, String> key) {
        var out = new TreeMap<String, T>();
        for (T t : items) out.putIfAbsent(key.apply(t), t);
        return out;
    }

    private static <T> Map<String, T> minus(Map<String, T> a, Map<String, T> b) {
        var out = new LinkedHashMap<String, T>();
        a.forEach((k, v) -> { if (!b.containsKey(k)) out.put(k, v); });
        return out;
    }

    private static List<Edge> incoming(Graph g, String id, Edge.Kind kind) {
        return g.edges().stream().filter(e -> e.kind() == kind && e.to().equals(id)).toList();
    }

    private static long count(Graph g, String kind) {
        return g.nodes().stream().filter(n -> kindOf(n).equals(kind)).count();
    }

    private static String kindOf(Node n) {
        return switch (n) {
            case Node.Service s -> "service";
            case Node.Endpoint e -> "endpoint";
            case Node.Topic t -> "topic";
            case Node.Table t -> "table";
            case Node.External x -> "external";
        };
    }

    private static String owner(Map<String, Node> nodes, String id) {
        return switch (nodes.get(id)) {
            case Node.Service s -> s.name();
            case Node.Endpoint e -> e.service();
            case Node.Table t -> t.service();
            case Node.Topic t -> t.name();
            case Node.External x -> x.name();
            case null -> id;
        };
    }

    private static String label(Node n) {
        return switch (n) {
            case Node.Service s -> "service `" + s.name() + "`";
            case Node.Endpoint e -> "`" + e.service() + " " + e.method() + " " + e.path() + "`";
            case Node.Topic t -> "topic `" + t.name() + "`";
            case Node.Table t -> "table `" + t.service() + "/" + t.name() + "`";
            case Node.External x -> "external `" + x.name() + "`";
        };
    }

    private static String labelOf(Map<String, Node> nodes, String id) {
        Node n = nodes.get(id);
        return n == null ? "`" + id + "`" : label(n);
    }

    private static String ev(List<Evidence> evidence) {
        return evidence.stream().filter(Objects::nonNull).limit(3).map(e -> "`" + e + "`").collect(Collectors.joining(", "));
    }

}
