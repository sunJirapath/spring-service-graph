package io.github.sunjirapath.ssg.mcp;

import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import io.github.sunjirapath.ssg.output.MarkdownOutput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The questions the MCP tools answer, as plain functions over a graph. */
public final class GraphQueries {

    private final Graph g;
    private final Map<String, Node> nodes;

    public GraphQueries(Graph g) {
        this.g = g;
        this.nodes = g.nodes().stream().collect(Collectors.toMap(Node::id, Function.identity()));
    }

    /**
     * Node ids for a target: an exact node id, a service or topic name, {@code METHOD /path},
     * {@code service METHOD /path}, a bare endpoint path, or a table name.
     */
    public List<String> resolve(String target) {
        String t = target.strip();
        if (nodes.containsKey(t)) return List.of(t);
        for (String id : List.of(Node.Service.idOf(t), Node.Topic.idOf(t), Node.External.idOf(t))) {
            if (nodes.containsKey(id)) return List.of(id);
        }
        var out = new ArrayList<String>();
        for (Node n : g.nodes()) {
            boolean hit = switch (n) {
                case Node.Endpoint e -> t.equals(e.path()) || t.equalsIgnoreCase(e.method() + " " + e.path())
                        || t.equalsIgnoreCase(e.service() + " " + e.method() + " " + e.path());
                case Node.Table tb -> t.equals(tb.name()) || t.equals(tb.service() + "/" + tb.name());
                default -> false;
            };
            if (hit) out.add(n.id());
        }
        if (out.isEmpty()) throw new IllegalArgumentException("no node matches '" + target
                + "'; use a service or topic name, 'METHOD /path', a table name, or a node id from graph.json");
        return out;
    }

    /** Every edge pointing at the target (or, for a service, at it or its endpoints), except EXPOSES. */
    public Map<String, Object> whoCalls(String target) {
        var ids = expand(resolve(target));
        var edges = g.edges().stream().filter(e -> e.kind() != Edge.Kind.EXPOSES && ids.contains(e.to()))
                .map(GraphQueries::view).toList();
        var out = new LinkedHashMap<String, Object>();
        out.put("target", target);
        out.put("matches", resolve(target));
        out.put("incoming", edges);
        return out;
    }

    /**
     * What may break if the target changes: walks incoming edges backwards up to {@code depth} hops.
     * Endpoints keep the walk going (their callers are affected too); a service reached through a
     * plain CALLS edge is a leaf, because without reach data we cannot tell which of its endpoints use it.
     */
    public Map<String, Object> impactOf(String target, int depth) {
        var start = expand(resolve(target));
        var affected = new ArrayList<Map<String, Object>>();
        var seen = new LinkedHashSet<>(start);
        Set<String> frontier = new TreeSet<>(start);
        for (int d = 1; d <= depth && !frontier.isEmpty(); d++) {
            var next = new TreeSet<String>();
            for (Edge e : g.edges()) {
                if (e.kind() == Edge.Kind.EXPOSES || !frontier.contains(e.to()) || !seen.add(e.from())) continue;
                var row = new LinkedHashMap<String, Object>();
                row.put("node", e.from());
                row.put("depth", d);
                row.put("because", view(e));
                affected.add(row);
                if (nodes.get(e.from()) instanceof Node.Endpoint) next.add(e.from());
            }
            frontier = next;
        }
        var services = affected.stream().map(r -> owner((String) r.get("node"))).collect(Collectors.toCollection(TreeSet::new));
        var out = new LinkedHashMap<String, Object>();
        out.put("target", target);
        out.put("matches", resolve(target));
        out.put("affectedServices", services);
        out.put("affected", affected);
        return out;
    }

    public String serviceCard(String service) {
        return MarkdownOutput.servicePage(g, service);
    }

    /** A service stands for itself and all its endpoints. */
    private Set<String> expand(List<String> ids) {
        var out = new LinkedHashSet<>(ids);
        for (String id : ids) {
            if (nodes.get(id) instanceof Node.Service s) {
                g.nodes().stream().filter(n -> n instanceof Node.Endpoint e && e.service().equals(s.name()))
                        .forEach(n -> out.add(n.id()));
            }
        }
        return out;
    }

    private String owner(String id) {
        return switch (nodes.get(id)) {
            case Node.Service s -> s.name();
            case Node.Endpoint e -> e.service();
            case Node.Table t -> t.service();
            case null, default -> id;
        };
    }

    private static Map<String, Object> view(Edge e) {
        var m = new LinkedHashMap<String, Object>();
        m.put("kind", e.kind().name());
        m.put("from", e.from());
        m.put("to", e.to());
        m.put("confidence", e.confidence().name());
        if (!e.attrs().isEmpty()) m.put("attrs", e.attrs());
        m.put("evidence", e.evidence().stream().map(Object::toString).toList());
        return m;
    }
}
