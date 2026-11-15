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
        this.linkBase = linkBase == null || linkBase.isBlank() ? null : safeLinkBase(linkBase);
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
        TextFile.removeRetired(graph, w.out, "services", ".md");
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
                .append("service: ").append(escape(s.name())).append('\n')
                .append("repo: ").append(escape(s.repo())).append('\n')
                .append("path: ").append(escape(s.path())).append('\n')
                .append("exposes: ").append(exposes.size()).append('\n')
                .append("calls: ").append(calls.size()).append('\n')
                .append("publishes: ").append(produces.size()).append('\n')
                .append("consumes: ").append(consumes.size()).append('\n')
                .append("tables: ").append(data.stream().map(Edge::to).distinct().count()).append('\n')
                .append("findings: ").append(findings.size()).append('\n')
                .append("---\n\n")
                .append("# ").append(escape(s.name())).append("\n\n")
                .append("Repo ").append(code(s.repo())).append(", module ").append(code(s.path())).append(".\n\n");

        md.append("## Exposes\n\n");
        table(md, List.of("Method", "Path", "Called by", "Reaches", "Source", "Confidence", "Evidence"), exposes, e -> {
            var ep = (Node.Endpoint) g.nodes.get(e.to());
            String callers = g.to(e.to(), Edge.Kind.CALLS).stream().map(c -> g.owner(c.from())).distinct().sorted()
                    .collect(Collectors.joining(", "));
            String reaches = g.from(e.to(), Edge.Kind.REACHES).stream().map(r -> g.label(r.to())).distinct().sorted()
                    .map(MarkdownOutput::code).collect(Collectors.joining("<br>"));
            return Arrays.asList(escape(ep.method()), code(ep.path()), callers.isEmpty() ? "-" : escape(callers), reaches.isEmpty() ? "-" : reaches,
                    escape(e.attrs().get("source")), e.confidence().name(), evidence(dir, e.evidence()));
        });

        md.append("## Calls\n\n");
        table(md, List.of("Target", "Method", "Path", "Client", "Matched", "Confidence", "Evidence"), calls, e -> {
            Node target = g.nodes.get(e.to());
            String matched = target instanceof Node.Endpoint ep ? code(ep.method() + " " + ep.path())
                    : target instanceof Node.Service ? ("unavailable".equals(e.attrs().get("contract"))
                    ? "**contract unavailable**" : "**no matching endpoint**") : "-";
            return Arrays.asList(escape(g.owner(e.to())), escape(e.attrs().get("method")), code(e.attrs().get("path")), escape(e.attrs().get("client")),
                    matched, e.confidence().name(), evidence(dir, e.evidence()));
        });

        md.append("## Publishes\n\n");
        table(md, List.of("Topic", "Consumers", "Via", "Confidence", "Evidence"), produces, e -> Arrays.asList(
                code(g.label(e.to())), others(e.to(), Edge.Kind.CONSUMES), escape(e.attrs().get("via")), e.confidence().name(),
                evidence(dir, e.evidence())));

        md.append("## Consumes\n\n");
        table(md, List.of("Topic", "Producers", "Via", "Confidence", "Evidence"), consumes, e -> Arrays.asList(
                code(g.label(e.to())), others(e.to(), Edge.Kind.PRODUCES), escape(e.attrs().get("via")), e.confidence().name(),
                evidence(dir, e.evidence())));

        md.append("## Data\n\n");
        table(md, List.of("Table", "Store", "Relation", "Confidence", "Evidence"), data, e -> {
            var t = (Node.Table) g.nodes.get(e.to());
            return Arrays.asList(code(t.name()), escape(t.store()), e.kind().name().toLowerCase(), e.confidence().name(),
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
        return s.isEmpty() ? "-" : escape(s);
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
                "[" + escape(s.name()) + "](services/" + g.fileName(s.name()) + ".md)", escape(s.repo()), code(s.path()),
                count(s.id(), Edge.Kind.EXPOSES), count(s.id(), Edge.Kind.CALLS), count(s.id(), Edge.Kind.PRODUCES),
                count(s.id(), Edge.Kind.CONSUMES),
                String.valueOf(g.graph.findings().stream().filter(f -> f.service().equals(s.name())).count())));

        md.append("## Service dependencies\n\n");
        var deps = new TreeMap<String, TreeSet<String>>();
        for (Edge e : g.graph.edges()) {
            if (e.kind() != Edge.Kind.CALLS) continue;
            deps.computeIfAbsent(g.owner(e.from()) + " → " + g.owner(e.to()), k -> new TreeSet<>()).add(e.attrs().get("client"));
        }
        table(md, List.of("From → To", "Clients"), List.copyOf(deps.entrySet()), d -> Arrays.asList(escape(d.getKey()), escape(String.join(", ", d.getValue()))));

        md.append("## Topics\n\n");
        var topicNodes = g.graph.nodes().stream().filter(Node.Topic.class::isInstance).toList();
        table(md, List.of("Topic", "Producers", "Consumers"), topicNodes, t -> Arrays.asList(code(g.label(t.id())),
                others(t.id(), Edge.Kind.PRODUCES), others(t.id(), Edge.Kind.CONSUMES)));

        md.append("## Findings\n\n");
        var byKind = g.graph.findings().stream().collect(Collectors.groupingBy(Finding::kind, TreeMap::new, Collectors.counting()));
        table(md, List.of("Kind", "Count"), List.copyOf(byKind.entrySet()), k -> Arrays.asList(k.getKey().name(), k.getValue().toString()));
        for (Finding f : g.graph.findings()) {
            md.append("- **").append(f.kind()).append("** ").append(code(f.service())).append(": ").append(escape(f.message()))
                    .append(" — ").append(evidence(dir, f.evidence())).append('\n');
        }
        return md.toString();
    }

    private String count(String id, Edge.Kind kind) {
        return String.valueOf(g.from(id, kind).size());
    }

    // ---------------------------------------------------------------- helpers

    private static String safeLinkBase(String template) {
        String lower = template.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.startsWith("https://") || lower.startsWith("http://") || template.startsWith("//"))
                || template.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c)))
            throw new IllegalArgumentException("--link-base requires an HTTP(S) URL without whitespace or controls");
        String safe = template.replaceAll("(?<=//)[^/?#]*@", "[redacted]@");
        int fragment = safe.indexOf('#');
        if (fragment >= 0 && !safe.substring(fragment + 1).matches("L?\\{line}")) {
            safe = safe.substring(0, fragment + 1) + "[redacted]";
        }
        int query = safe.indexOf('?');
        if (query < 0) return safe;
        int end = safe.indexOf('#', query);
        if (end < 0) end = safe.length();
        // Only placeholder query values are needed for evidence; literal values may be credentials.
        String fields = Arrays.stream(safe.substring(query + 1, end).split("&", -1)).map(field -> {
            int equals = field.indexOf('=');
            String value = equals < 0 ? "" : field.substring(equals + 1);
            return equals < 0 ? "[redacted]"
                    : field.substring(0, equals + 1) + (value.matches("\\{(repo|file|line)}") ? value : "[redacted]");
        }).collect(Collectors.joining("&"));
        return safe.substring(0, query + 1) + fields + safe.substring(end);
    }

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
                return "[" + escape(text) + "](" + link(linkBase.replace("{repo}", link(e.repo())).replace("{file}", link(e.file()))
                        .replace("{line}", String.valueOf(e.line()))) + ")";
            }
            if (root == null) return code(text);
            Path target = root.resolve(e.file());
            // no relative path exists across drives (C: vs E:), so link the absolute file URI there
            String link = target.getRoot() != null && target.getRoot().equals(fromDir.getRoot())
                    ? fromDir.relativize(target).toString().replace('\\', '/').replace(" ", "%20")
                    : target.toUri().toString();
            return "[" + escape(text) + "](" + link(link) + "#L" + e.line() + ")";
        }).collect(Collectors.joining("<br>"));
    }

    private static String code(String s) {
        return s == null ? "" : "`" + escapeCode(s) + "`";
    }

    private static String escape(String s) {
        if (s == null) return "";
        var out = new StringBuilder();
        s.codePoints().forEach(c -> {
            if (c == '<') out.append("&lt;");
            else if (c == '>') out.append("&gt;");
            else if (c == '&') out.append("&amp;");
            else if ("[]!\\".indexOf(c) >= 0 || Character.isISOControl(c)) out.append("&#").append(c).append(';');
            else out.appendCodePoint(c);
        });
        return out.toString();
    }

    private static String escapeCode(String text) {
        var out = new StringBuilder();
        text.codePoints().forEach(c -> {
            if (c == '<') out.append("&lt;");
            else if (c == '>') out.append("&gt;");
            else if (c == '&') out.append("&amp;");
            else if (c == '`' || Character.isISOControl(c)) out.append("&#").append(c).append(';');
            else out.appendCodePoint(c);
        });
        return out.toString();
    }

    private static String link(String text) {
        var out = new StringBuilder();
        text.codePoints().forEach(c -> {
            if (c <= 32 || c >= 127 || "()<>\"'`\\".indexOf(c) >= 0)
                out.append(java.net.URLEncoder.encode(new String(Character.toChars(c)), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20"));
            else out.appendCodePoint(c);
        });
        return out.toString();
    }
}
