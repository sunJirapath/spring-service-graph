package io.github.sunjirapath.ssg.output;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputEdgeCasesTest {

    @Test
    void graphJsonRoundTripsFromDirectoryAndFilePath(@TempDir Path tmp) throws IOException {
        Graph graph = completeGraph();
        JsonOutput.write(graph, tmp);

        assertThat(JsonOutput.read(tmp)).isEqualTo(graph);
        assertThat(JsonOutput.read(tmp.resolve("graph.json"))).isEqualTo(graph);
    }

    @Test
    void graphJsonUsesOnlyLfAndEndsWithNewline(@TempDir Path tmp) throws IOException {
        JsonOutput.write(completeGraph(), tmp);

        byte[] bytes = Files.readAllBytes(tmp.resolve("graph.json"));
        assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain("\r").endsWith("\n");
        assertThat(bytes[bytes.length - 1]).isEqualTo((byte) '\n');
    }

    @Test
    void graphJsonContainsEveryNodeKindDiscriminator(@TempDir Path tmp) throws IOException {
        JsonOutput.write(completeGraph(), tmp);
        String json = Files.readString(tmp.resolve("graph.json"));

        assertThat(json).contains("\"kind\" : \"service\"", "\"kind\" : \"endpoint\"",
                "\"kind\" : \"topic\"", "\"kind\" : \"table\"", "\"kind\" : \"external\"");
    }

    @Test
    void markdownEscapesPipesInCellsAndAngleBracketsInFindings(@TempDir Path tmp) throws IOException {
        var service = new Node.Service(Node.Service.idOf("order|api"), "order|api", "repo|one", "module|one");
        var finding = new Finding(Finding.Kind.PARSE_ERROR, "order|api", "bad <T> > value", List.of(ev(1)));
        MarkdownOutput.write(new Graph(List.of(service), List.of(), List.of(finding)), Map.of(), tmp);

        String index = Files.readString(tmp.resolve("index.md"));
        String page = Files.readString(tmp.resolve("services/order_api.md"));
        assertThat(index).contains("order\\|api", "repo\\|one", "module\\|one", "bad &lt;T&gt; &gt; value");
        assertThat(page).contains("bad &lt;T&gt; &gt; value");
    }

    @Test
    void serviceFileNamesSanitizeSlashesAndSpaces(@TempDir Path tmp) throws IOException {
        var service = new Node.Service(Node.Service.idOf("sales/api v2"), "sales/api v2", "repo", ".");
        MarkdownOutput.write(new Graph(List.of(service), List.of(), List.of()), Map.of(), tmp);
        DiagramOutput.write(new Graph(List.of(service), List.of(), List.of()), tmp);

        assertThat(tmp.resolve("services/sales_api_v2.md")).isRegularFile();
        assertThat(tmp.resolve("diagrams/sales_api_v2.mmd")).isRegularFile();
    }

    @ParameterizedTest
    @CsvSource({"orders:blue, orders_blue, orders_blue-2, orders_blue",
            "Orders, orders, Orders, orders-2", "café, cafè, caf_-2, caf_",
            "CON, CON-2, CON-3, CON-2", "aUx.log, aux-2.log, aUx-3.log, aux-2.log",
            "com1, com1-2, com1-3, com1-2", "LPT9, lpt9-2, LPT9-3, lpt9-2"})
    void collidingServiceNamesKeepSeparatePagesDiagramsAndLinks(String first, String second,
                                                               String firstFile, String secondFile,
                                                               @TempDir Path tmp) throws IOException {
        var graph = new Graph(List.of(service(first), service(second)), List.of(), List.of());
        var reversed = new GraphView(new Graph(List.of(service(second), service(first)), List.of(), List.of()));
        assertThat(reversed.fileName(first)).isEqualTo(firstFile);
        assertThat(reversed.fileName(second)).isEqualTo(secondFile);
        var expected = Map.of(first, firstFile, second, secondFile);
        Path one = tmp.resolve("one");
        Path two = tmp.resolve("two");

        for (Path out : List.of(one, two)) {
            writeAll(graph, out);
            try (var pages = Files.list(out.resolve("services"))) {
                assertThat(pages.map(p -> p.getFileName().toString()))
                        .containsExactlyInAnyOrder(firstFile + ".md", secondFile + ".md");
            }
            try (var diagrams = Files.list(out.resolve("diagrams"))) {
                assertThat(diagrams.map(p -> p.getFileName().toString()))
                        .containsExactlyInAnyOrder("fleet.puml", firstFile + ".mmd", secondFile + ".mmd");
            }
            String index = Files.readString(out.resolve("index.md"));
            for (var entry : expected.entrySet()) {
                assertThat(index).contains("[" + entry.getKey() + "](services/" + entry.getValue() + ".md)");
                assertThat(Files.readString(out.resolve("services/" + entry.getValue() + ".md")))
                        .contains("# " + entry.getKey() + "\n");
                assertThat(Files.readString(out.resolve("diagrams/" + entry.getValue() + ".mmd")))
                        .contains("[\"" + entry.getKey() + "\"]");
            }
        }
        try (var files = Files.walk(one)) {
            for (Path relative : files.filter(Files::isRegularFile).map(one::relativize).toList()) {
                assertThat(Files.readAllBytes(two.resolve(relative))).as(relative.toString())
                        .isEqualTo(Files.readAllBytes(one.resolve(relative)));
            }
        }
    }

    @Test
    void collisionSuffixesDoNotTakeExistingServiceNames() {
        var graph = new Graph(List.of(service("orders:blue"), service("orders_blue"),
                service("orders_blue-2"), service("ORDERS_BLUE-3")), List.of(), List.of());
        var view = new GraphView(graph);

        assertThat(view.fileName("orders_blue")).isEqualTo("orders_blue");
        assertThat(view.fileName("orders_blue-2")).isEqualTo("orders_blue-2");
        assertThat(view.fileName("ORDERS_BLUE-3")).isEqualTo("ORDERS_BLUE-3");
        assertThat(view.fileName("orders:blue")).isEqualTo("orders_blue-4");
    }

    @Test
    void evidenceLinksAreRelativeAndEncodeSpaces(@TempDir Path tmp) throws IOException {
        Path repo = tmp.resolve("repo root");
        Files.createDirectories(repo.resolve("src/main/java/my package"));
        var service = service("orders");
        var endpoint = endpoint("orders", "GET", "/orders");
        var exposes = edge(Edge.Kind.EXPOSES, service.id(), endpoint.id(), ev("src/main/java/my package/Orders.java", 7),
                "source", "controller");
        Path out = tmp.resolve("out");

        MarkdownOutput.write(new Graph(List.of(service, endpoint), List.of(exposes), List.of()), Map.of("repo", repo), out);

        String page = Files.readString(out.resolve("services/orders.md"));
        assertThat(page).contains("repo/src/main/java/my package/Orders.java:7")
                .contains("repo%20root/src/main/java/my%20package/Orders.java#L7")
                .doesNotContain(repo.toAbsolutePath().toString());
    }

    @Test
    void serviceWithNoRelationshipsShowsNoneForEverySection() {
        String page = MarkdownOutput.servicePage(new Graph(List.of(service("empty")), List.of(), List.of()), "empty");

        assertThat(page).contains("## Exposes", "## Calls", "## Publishes", "## Consumes", "## Data", "## Findings");
        assertThat(count(page, "_None._")).isEqualTo(6);
    }

    @Test
    void indexReportsFleetCounts(@TempDir Path tmp) throws IOException {
        MarkdownOutput.write(completeGraph(), Map.of(), tmp);

        assertThat(Files.readString(tmp.resolve("index.md")))
                .contains("1 services, 1 endpoints, 1 topics, 1 tables, 1 findings.");
    }

    @Test
    void fleetPlantUmlUsesUniqueAliasesAndOmitsTables(@TempDir Path tmp) throws IOException {
        DiagramOutput.write(completeGraph(), tmp);
        String puml = Files.readString(tmp.resolve("diagrams/fleet.puml"));

        assertThat(puml).contains(" as n0", " as n1", " as n2").doesNotContain("orders_table");
        assertThat(puml.lines().filter(line -> line.matches(".* as n\\d+$")).map(line -> line.replaceAll(".* as ", "")))
                .doesNotHaveDuplicates();
    }

    @Test
    void perServiceMermaidQuotesLabelsAndEscapesEmbeddedQuotes(@TempDir Path tmp) throws IOException {
        var service = service("orders");
        var external = new Node.External(Node.External.idOf("vendor \"prime\""), "vendor \"prime\"");
        var call = edge(Edge.Kind.CALLS, service.id(), external.id(), ev(1),
                "client", "rest-client", "method", "GET", "path", "/");
        DiagramOutput.write(new Graph(List.of(service, external), List.of(call), List.of()), tmp);

        String mmd = Files.readString(tmp.resolve("diagrams/orders.mmd"));
        assertThat(mmd).contains("[\"orders\"]", "{{\"vendor #quot;prime#quot;\"}}");
    }

    @Test
    void servicePageRejectsUnknownService() {
        assertThatThrownBy(() -> MarkdownOutput.servicePage(new Graph(List.of(service("orders")), List.of(), List.of()), "missing"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("unknown service: missing");
    }

    @Test
    void allOutputsAreByteIdenticalAcrossRuns(@TempDir Path tmp) throws IOException {
        Path one = tmp.resolve("one");
        Path two = tmp.resolve("two");
        Graph graph = completeGraph();
        writeAll(graph, one);
        writeAll(graph, two);

        List<Path> relativeFiles;
        try (var files = Files.walk(one)) {
            relativeFiles = files.filter(Files::isRegularFile).map(one::relativize).sorted().toList();
        }
        assertThat(relativeFiles).containsExactly(Path.of("diagrams/fleet.puml"), Path.of("diagrams/orders.mmd"),
                Path.of("graph.json"), Path.of("index.md"), Path.of("services/orders.md"));
        for (Path relative : relativeFiles) {
            assertThat(Files.readAllBytes(two.resolve(relative))).as(relative.toString())
                    .isEqualTo(Files.readAllBytes(one.resolve(relative)));
        }
    }

    @Test
    void textFileNormalizesWindowsLineEndings(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("nested/out.txt");
        TextFile.write(file, "a\r\nb\r\n");

        assertThat(Files.readString(file)).isEqualTo("a\nb\n");
    }

    private static void writeAll(Graph graph, Path out) throws IOException {
        JsonOutput.write(graph, out);
        MarkdownOutput.write(graph, Map.of(), out);
        DiagramOutput.write(graph, out);
    }

    private static Graph completeGraph() {
        var service = service("orders");
        var endpoint = endpoint("orders", "GET", "/orders");
        var topic = new Node.Topic(Node.Topic.idOf("orders.created"), "orders.created");
        var table = new Node.Table(Node.Table.idOf("orders", "orders_table"), "orders", "orders_table", "sql");
        var external = new Node.External(Node.External.idOf("payments"), "payments");
        var edges = List.of(
                edge(Edge.Kind.EXPOSES, service.id(), endpoint.id(), ev(1), "source", "controller"),
                edge(Edge.Kind.CALLS, service.id(), external.id(), ev(2), "client", "rest-client", "method", "POST", "path", "/pay"),
                edge(Edge.Kind.PRODUCES, service.id(), topic.id(), ev(3), "via", "kafka-template"),
                edge(Edge.Kind.USES, service.id(), table.id(), ev(4)));
        var finding = new Finding(Finding.Kind.UNRESOLVED, "orders", "dynamic target", List.of(ev(5)));
        return new Graph(List.of(service, endpoint, topic, table, external), edges, List.of(finding));
    }

    private static Node.Service service(String name) {
        return new Node.Service(Node.Service.idOf(name), name, "repo", ".");
    }

    private static Node.Endpoint endpoint(String service, String method, String path) {
        return new Node.Endpoint(Node.Endpoint.idOf(service, method, path), service, method, path);
    }

    private static Edge edge(Edge.Kind kind, String from, String to, Evidence evidence, String... attrs) {
        var map = new TreeMap<String, String>();
        for (int i = 0; i < attrs.length; i += 2) map.put(attrs[i], attrs[i + 1]);
        return new Edge(kind, from, to, Confidence.EXACT, map, List.of(evidence));
    }

    private static Evidence ev(int line) {
        return ev("src/main/java/Orders.java", line);
    }

    private static Evidence ev(String file, int line) {
        return new Evidence("repo", file, line);
    }

    private static int count(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }
}
