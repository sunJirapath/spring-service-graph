package io.github.sunjirapath.ssg.output;

import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code index.md} and {@code services/<name>.md}. Deterministic: sorted, no timestamps.
 * Evidence links are relative paths from the page to the scanned file.
 */
public final class MarkdownOutput {

    private final GraphView g;
    private final Map<String, Path> repoRoots;
    private final Path out;
    /** e.g. https://git.example/{repo}/blob/main/{file}#L{line}; null links to local files */
    private final String linkBase;

    private MarkdownOutput(Graph graph, Map<String, Path> repoRoots, Path out, String linkBase) {
        this.linkBase = linkBase == null || linkBase.isBlank() ? null : linkBase;
        this.g = new GraphView(graph);
        this.repoRoots = repoRoots;
        this.out = out.toAbsolutePath().normalize();
    }

    public static void write(Graph graph, Map<String, Path> repoRoots, Path out) throws IOException {
        write(graph, repoRoots, out, null);
    }

    /**
     * @param linkBase URL template for evidence links with {repo}, {file} and {line}, so pages do not
     *                 depend on where the fleet is checked out; null links to the local files
     */
    public static void write(Graph graph, Map<String, Path> repoRoots, Path out, String linkBase) throws IOException {
        var w = new MarkdownOutput(graph, repoRoots, out, linkBase);
        for (Node.Service s : w.g.services()) {
            TextFile.write(w.out.resolve("services/" + w.g.fileName(s.name()) + ".md"), w.servicePage(s));
        }
        TextFile.write(w.out.resolve("index.md"), w.index());
    }

    /** One service page with evidence as plain {@code repo/file:line} text (no links), e.g. for MCP. */
    public static String servicePage(Graph graph, String service) {
        var w = new MarkdownOutput(graph, Map.of(), Path.of("."), null);
        return w.g.services().stream().filter(s -> s.name().equals(service)).findFirst().map(w::servicePage)
                .orElseThrow(() -> new IllegalArgumentException("unknown service: " + service));
    }

    // ---------------------------------------------------------------- service page

    private String servicePage(Node.Service s) {
        Path dir = out.resolve("services");
        var exposes = g.from(s.id(), Edge.Kind.EXPOSES);
        var calls = g.from(s.id(), Edge.Kind.CALLS);
        var produces = g.from(s.id(), Edge.Kind.PRODUCES);
        var consumes = g.from(s.id(), Edge.Kind.CONSUMES);
        var data = g.graph.edges().stream().filter(e -> e.from().equals(s.id())
                && (e.kind() == Edge.Kind.OWNS || e.kind() == Edge.Kind.USES)).toList();
        var findings = g.graph.findings().stream().filter(f -> f.service().equals(s.name())).toList();

        var md = new StringBuilder();
        md.append("---\n")
                .append("service: ").append(s.name()).append('\n')
                .append("repo: ").append(s.repo()).append('\n')
                .append("path: ").append(s.path()).append('\n')
                .append("exposes: ").append(exposes.size()).append('\n')
                .append("calls: ").append(calls.size()).append('\n')
                .append("publishes: ").append(produces.size()).append('\n')
                .append("consumes: ").append(consumes.size()).append('\n')
                .append("tables: ").append(data.stream().map(Edge::to).distinct().count()).append('\n')
                .append("findings: ").append(findings.size()).append('\n')
                .append("---\n\n")
                .append("# ").append(s.name()).append("\n\n")
                .append("Repo `").append(s.repo()).append("`, module `").append(s.path()).append("`.\n\n");

        md.append("## Exposes\n\n");
        table(md, List.of("Method", "Path", "Called by", "Reaches", "Source", "Confidence", "Evidence"), exposes, e -> {
            var ep = (Node.Endpoint) g.nodes.get(e.to());
            String callers = g.to(e.to(), Edge.Kind.CALLS).stream().map(c -> g.owner(c.from())).distinct().sorted()
                    .collect(Collectors.joining(", "));
            String reaches = g.from(e.to(), Edge.Kind.REACHES).stream().map(r -> g.label(r.to())).distinct().sorted()
                    .map(MarkdownOutput::code).collect(Collectors.joining("<br>"));
            return Arrays.asList(ep.method(), code(ep.path()), callers.isEmpty() ? "-" : callers, reaches.isEmpty() ? "-" : reaches,
                    e.attrs().get("source"), e.confidence().name(), evidence(dir, e.evidence()));
        });

        md.append("## Calls\n\n");
        table(md, List.of("Target", "Method", "Path", "Client", "Matched", "Confidence", "Evidence"), calls, e -> {
            Node target = g.nodes.get(e.to());
            String matched = target instanceof Node.Endpoint ep ? code(ep.method() + " " + ep.path())
                    : target instanceof Node.Service ? "**no matching endpoint**" : "-";
            return Arrays.asList(g.owner(e.to()), e.attrs().get("method"), code(e.attrs().get("path")), e.attrs().get("client"),
                    matched, e.confidence().name(), evidence(dir, e.evidence()));
        });

        md.append("## Publishes\n\n");
        table(md, List.of("Topic", "Consumers", "Via", "Confidence", "Evidence"), produces, e -> Arrays.asList(
                code(g.label(e.to())), others(e.to(), Edge.Kind.CONSUMES), e.attrs().get("via"), e.confidence().name(),
                evidence(dir, e.evidence())));

        md.append("## Consumes\n\n");
        table(md, List.of("Topic", "Producers", "Via", "Confidence", "Evidence"), consumes, e -> Arrays.asList(
                code(g.label(e.to())), others(e.to(), Edge.Kind.PRODUCES), e.attrs().get("via"), e.confidence().name(),
                evidence(dir, e.evidence())));

        md.append("## Data\n\n");
        table(md, List.of("Table", "Store", "Relation", "Confidence", "Evidence"), data, e -> {
            var t = (Node.Table) g.nodes.get(e.to());
            return Arrays.asList(code(t.name()), t.store(), e.kind().name().toLowerCase(), e.confidence().name(),
                    evidence(dir, e.evidence()));
        });

        md.append("## Findings\n\n");
        if (findings.isEmpty()) md.append("_None._\n");
        for (Finding f : findings) {
            md.append("- **").append(f.kind()).append("** ").append(escape(f.message())).append(" — ")
                    .append(evidence(dir, f.evidence())).append('\n');
        }
        return md.toString();
    }

    private String others(String topicId, Edge.Kind kind) {
        String s = g.to(topicId, kind).stream().map(e -> g.owner(e.from())).distinct().sorted().collect(Collectors.joining(", "));
        return s.isEmpty() ? "-" : s;
    }

    // ---------------------------------------------------------------- index

    private String index() {
        Path dir = out;
        var md = new StringBuilder("# Fleet overview\n\n");
        var services = g.services();
        long endpoints = g.graph.nodes().stream().filter(Node.Endpoint.class::isInstance).count();
        long topics = g.graph.nodes().stream().filter(Node.Topic.class::isInstance).count();
        long tables = g.graph.nodes().stream().filter(Node.Table.class::isInstance).count();
        md.append(services.size()).append(" services, ").append(endpoints).append(" endpoints, ")
                .append(topics).append(" topics, ").append(tables).append(" tables, ")
                .append(g.graph.findings().size()).append(" findings.\n\n");

        md.append("## Services\n\n");
        table(md, List.of("Service", "Repo", "Module", "Exposes", "Calls", "Publishes", "Consumes", "Findings"), services, s -> Arrays.asList(
                "[" + s.name() + "](services/" + g.fileName(s.name()) + ".md)", s.repo(), code(s.path()),
                count(s.id(), Edge.Kind.EXPOSES), count(s.id(), Edge.Kind.CALLS), count(s.id(), Edge.Kind.PRODUCES),
                count(s.id(), Edge.Kind.CONSUMES),
                String.valueOf(g.graph.findings().stream().filter(f -> f.service().equals(s.name())).count())));

        md.append("## Service dependencies\n\n");
        var deps = new TreeMap<String, TreeSet<String>>();
        for (Edge e : g.graph.edges()) {
            if (e.kind() != Edge.Kind.CALLS) continue;
            deps.computeIfAbsent(g.owner(e.from()) + " → " + g.owner(e.to()), k -> new TreeSet<>()).add(e.attrs().get("client"));
        }
        table(md, List.of("From → To", "Clients"), List.copyOf(deps.entrySet()), d -> Arrays.asList(d.getKey(), String.join(", ", d.getValue())));

        md.append("## Topics\n\n");
        var topicNodes = g.graph.nodes().stream().filter(Node.Topic.class::isInstance).toList();
        table(md, List.of("Topic", "Producers", "Consumers"), topicNodes, t -> Arrays.asList(code(g.label(t.id())),
                others(t.id(), Edge.Kind.PRODUCES), others(t.id(), Edge.Kind.CONSUMES)));

        md.append("## Findings\n\n");
        var byKind = g.graph.findings().stream().collect(Collectors.groupingBy(Finding::kind, TreeMap::new, Collectors.counting()));
        table(md, List.of("Kind", "Count"), List.copyOf(byKind.entrySet()), k -> Arrays.asList(k.getKey().name(), k.getValue().toString()));
        for (Finding f : g.graph.findings()) {
            md.append("- **").append(f.kind()).append("** `").append(f.service()).append("`: ").append(escape(f.message()))
                    .append(" — ").append(evidence(dir, f.evidence())).append('\n');
        }
        return md.toString();
    }

    private String count(String id, Edge.Kind kind) {
        return String.valueOf(g.from(id, kind).size());
    }

    // ---------------------------------------------------------------- helpers

    private static <T> void table(StringBuilder md, List<String> header, List<T> rows, Function<T, List<String>> cells) {
        if (rows.isEmpty()) {
            md.append("_None._\n\n");
            return;
        }
        md.append("| ").append(String.join(" | ", header)).append(" |\n|");
        header.forEach(h -> md.append("---|"));
        md.append('\n');
        for (T r : rows) {
            md.append("| ").append(cells.apply(r).stream().map(c -> c == null ? "" : c.replace("|", "\\|"))
                    .collect(Collectors.joining(" | "))).append(" |\n");
        }
        md.append('\n');
    }

    private String evidence(Path fromDir, List<Evidence> evidence) {
        return evidence.stream().map(e -> {
            Path root = repoRoots.get(e.repo());
            String text = e.repo() + "/" + e.file() + ":" + e.line();
            if (linkBase != null) {
                return "[" + text + "](" + linkBase.replace("{repo}", e.repo()).replace("{file}", e.file().replace(" ", "%20"))
                        .replace("{line}", String.valueOf(e.line())) + ")";
            }
            if (root == null) return "`" + text + "`";
            Path target = root.resolve(e.file());
            // no relative path exists across drives (C: vs E:), so link the absolute file URI there
            String link = target.getRoot() != null && target.getRoot().equals(fromDir.getRoot())
                    ? fromDir.relativize(target).toString().replace('\\', '/').replace(" ", "%20")
                    : target.toUri().toString();
            return "[" + text + "](" + link + "#L" + e.line() + ")";
        }).collect(Collectors.joining("<br>"));
    }

    private static String code(String s) {
        return s == null ? "" : "`" + s + "`";
    }

    private static String escape(String s) {
        return s.replace("<", "&lt;").replace(">", "&gt;");
    }
}
