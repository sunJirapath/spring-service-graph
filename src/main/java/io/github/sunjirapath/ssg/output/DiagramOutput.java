package io.github.sunjirapath.ssg.output;

import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * {@code diagrams/fleet.puml} (services, topics, externals) and {@code diagrams/<service>.mmd}
 * (one service and its direct neighbours, tables included). Calls are folded to service level.
 */
public final class DiagramOutput {

    /** A service-level arrow: from/to are node ids of services, topics, tables or externals. */
    private record Arrow(String from, String to, String label) {}

    private DiagramOutput() {}

    public static void write(Graph graph, Path out) throws IOException {
        var g = new GraphView(graph);
        var arrows = arrows(g);
        TextFile.write(out.resolve("diagrams/fleet.puml"), fleet(g, arrows));
        for (Node.Service s : g.services()) {
            var mine = arrows.stream().filter(a -> a.from.equals(s.id()) || a.to.equals(s.id())).toList();
            TextFile.write(out.resolve("diagrams/" + GraphView.fileName(s.name()) + ".mmd"), mermaid(g, s, mine));
        }
    }

    private static List<Arrow> arrows(GraphView g) {
        var labels = new TreeMap<String, TreeSet<String>>(); // "from\0to" -> labels
        for (Edge e : g.graph.edges()) {
            String from;
            String to;
            String label;
            switch (e.kind()) {
                case EXPOSES, REACHES -> { continue; }
                case CALLS -> {
                    from = e.from();
                    Node t = g.nodes.get(e.to());
                    to = t instanceof Node.Endpoint ep ? Node.Service.idOf(ep.service()) : e.to();
                    label = e.attrs().get("client");
                }
                case CONSUMES -> { from = e.to(); to = e.from(); label = "consume"; }
                case PRODUCES -> { from = e.from(); to = e.to(); label = "produce"; }
                default -> { from = e.from(); to = e.to(); label = e.kind().name().toLowerCase(); }
            }
            labels.computeIfAbsent(from + "\0" + to, k -> new TreeSet<>()).add(label);
        }
        var out = new ArrayList<Arrow>();
        labels.forEach((k, v) -> {
            String[] ft = k.split("\0");
            out.add(new Arrow(ft[0], ft[1], String.join(", ", v)));
        });
        return out;
    }

    private static String fleet(GraphView g, List<Arrow> arrows) {
        var shown = arrows.stream().filter(a -> !(g.nodes.get(a.from) instanceof Node.Table) && !(g.nodes.get(a.to) instanceof Node.Table)).toList();
        var aliases = aliases(g, shown, g.services().stream().map(Node::id).toList());
        var sb = new StringBuilder("@startuml fleet\nskinparam componentStyle rectangle\nleft to right direction\n\n");
        aliases.forEach((id, alias) -> sb.append(switch (g.nodes.get(id)) {
            case Node.Topic t -> "queue";
            case Node.External x -> "cloud";
            default -> "component";
        }).append(" \"").append(g.label(id).replace("\"", "'")).append("\" as ").append(alias).append('\n'));
        sb.append('\n');
        for (Arrow a : shown) {
            sb.append(aliases.get(a.from)).append(" --> ").append(aliases.get(a.to)).append(" : ").append(a.label).append('\n');
        }
        return sb.append("@enduml\n").toString();
    }

    private static String mermaid(GraphView g, Node.Service s, List<Arrow> arrows) {
        var aliases = aliases(g, arrows, List.of(s.id()));
        var sb = new StringBuilder("flowchart LR\n");
        aliases.forEach((id, alias) -> {
            String label = g.label(id).replace("\"", "#quot;");
            sb.append("  ").append(alias).append(switch (g.nodes.get(id)) {
                case Node.Topic t -> "[/\"" + label + "\"/]";
                case Node.Table t -> "[(\"" + label + "\")]";
                case Node.External x -> "{{\"" + label + "\"}}";
                default -> "[\"" + label + "\"]";
            }).append('\n');
        });
        for (Arrow a : arrows) {
            sb.append("  ").append(aliases.get(a.from)).append(" -->|").append(a.label).append("| ").append(aliases.get(a.to)).append('\n');
        }
        return sb.toString();
    }

    /** Stable short aliases (n0, n1, ...) in node-id order, avoiding name-escaping collisions. */
    private static Map<String, String> aliases(GraphView g, List<Arrow> arrows, List<String> always) {
        var ids = new TreeSet<>(always);
        arrows.forEach(a -> { ids.add(a.from); ids.add(a.to); });
        var out = new LinkedHashMap<String, String>();
        for (String id : ids) out.put(id, "n" + out.size());
        return out;
    }
}
