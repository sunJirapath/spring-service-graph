package io.github.sunjirapath.ssg.output;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class OutputCoverageTest {

    @Test
    void graphViewReturnsOwnersForEveryNodeKindAndUnknownId() {
        var service = service("orders");
        var endpoint = endpoint("orders", "GET", "/items");
        var table = new Node.Table(Node.Table.idOf("orders", "items"), "orders", "items", "sql");
        var topic = new Node.Topic(Node.Topic.idOf("created"), "created");
        var external = new Node.External(Node.External.idOf("stripe"), "stripe");
        var view = new GraphView(new Graph(List.of(service, endpoint, table, topic, external), List.of(), List.of()));

        assertThat(view.owner(service.id())).isEqualTo("orders");
        assertThat(view.owner(endpoint.id())).isEqualTo("orders");
        assertThat(view.owner(table.id())).isEqualTo("orders");
        assertThat(view.owner(topic.id())).isEqualTo("created");
        assertThat(view.owner(external.id())).isEqualTo("stripe");
        assertThat(view.owner("missing:id")).isEqualTo("missing:id");
    }

    @Test
    void graphViewReturnsLabelsForEveryNodeKindAndUnknownId() {
        var service = service("orders");
        var endpoint = endpoint("orders", "GET", "/items");
        var table = new Node.Table(Node.Table.idOf("orders", "items"), "orders", "items", "sql");
        var topic = new Node.Topic(Node.Topic.idOf("created"), "created");
        var external = new Node.External(Node.External.idOf("stripe"), "stripe");
        var view = new GraphView(new Graph(List.of(service, endpoint, table, topic, external), List.of(), List.of()));

        assertThat(view.label(service.id())).isEqualTo("orders");
        assertThat(view.label(endpoint.id())).isEqualTo("orders GET /items");
        assertThat(view.label(table.id())).isEqualTo("items");
        assertThat(view.label(topic.id())).isEqualTo("created");
        assertThat(view.label(external.id())).isEqualTo("stripe");
        assertThat(view.label("missing:id")).isEqualTo("missing:id");
    }

    @Test
    void topicBackendCollisionsAreVisibleInMarkdownAndDiagrams(@TempDir Path tmp) throws Exception {
        var service = service("publisher");
        var endpoint = endpoint("publisher", "POST", "/publish");
        var kafka = new Node.Topic(Node.Topic.idOf("kafka", "orders"), "orders", "kafka");
        var rabbit = new Node.Topic(Node.Topic.idOf("rabbit", "orders"), "orders", "rabbit");
        var unknown = new Node.Topic(Node.Topic.idOf("unknown", "orders"), "orders");
        var audit = new Node.Topic(Node.Topic.idOf("kafka", "audit"), "audit", "kafka");
        var graph = new Graph(List.of(service, endpoint, kafka, rabbit, unknown, audit), List.of(
                edge(Edge.Kind.EXPOSES, service.id(), endpoint.id(), new TreeMap<>()),
                edge(Edge.Kind.PRODUCES, service.id(), kafka.id(), new TreeMap<>()),
                edge(Edge.Kind.PRODUCES, service.id(), rabbit.id(), new TreeMap<>()),
                edge(Edge.Kind.PRODUCES, service.id(), unknown.id(), new TreeMap<>()),
                edge(Edge.Kind.PRODUCES, service.id(), audit.id(), new TreeMap<>()),
                edge(Edge.Kind.CONSUMES, service.id(), rabbit.id(), new TreeMap<>()),
                edge(Edge.Kind.REACHES, endpoint.id(), kafka.id(), new TreeMap<>()),
                edge(Edge.Kind.REACHES, endpoint.id(), rabbit.id(), new TreeMap<>())), List.of());

        MarkdownOutput.write(graph, Map.of(), tmp);
        DiagramOutput.write(graph, tmp);

        for (Path file : List.of(tmp.resolve("index.md"), tmp.resolve("services/publisher.md"),
                tmp.resolve("diagrams/fleet.puml"), tmp.resolve("diagrams/publisher.mmd"))) {
            assertThat(Files.readString(file)).as(file.toString())
                    .contains("orders [kafka]", "orders [rabbit]", "orders [unknown]")
                    .doesNotContain("audit [kafka]");
        }
        assertThat(MarkdownOutput.servicePage(graph, "publisher"))
                .contains("`orders [kafka]`<br>`orders [rabbit]`", "`audit`");
    }

    @Test
    void legacyAndCurrentTopicsWithTheSameBrokerKeepTheirLabel() {
        var legacy = new Node.Topic(Node.Topic.idOf("orders"), "orders");
        var current = new Node.Topic(Node.Topic.idOf("unknown", "orders"), "orders");
        var view = new GraphView(new Graph(List.of(legacy, current), List.of(), List.of()));

        assertThat(view.label(legacy.id())).isEqualTo("orders");
        assertThat(view.label(current.id())).isEqualTo("orders");
    }

    @Test
    void markdownRendersMissingOptionalEdgeAttributesAsEmptyCells() {
        var service = service("orders");
        var external = new Node.External(Node.External.idOf("vendor"), "vendor");
        var call = edge(Edge.Kind.CALLS, service.id(), external.id(), new TreeMap<>());
        String page = MarkdownOutput.servicePage(new Graph(List.of(service, external), List.of(call), List.of()), "orders");

        assertThat(page).contains("| vendor |  |  |  | - | EXACT | `repo/Source.java:1` |");
    }

    @Test
    void relativeRepoRootUsesAbsoluteFileUriForEvidenceLink(@TempDir Path tmp) throws Exception {
        var service = service("orders");
        var endpoint = endpoint("orders", "GET", "/items");
        var attrs = new TreeMap<String, String>();
        attrs.put("source", "controller");
        var exposes = edge(Edge.Kind.EXPOSES, service.id(), endpoint.id(), attrs);

        MarkdownOutput.write(new Graph(List.of(service, endpoint), List.of(exposes), List.of()),
                Map.of("repo", Path.of("relative-repo")), tmp);

        assertThat(Files.readString(tmp.resolve("services/orders.md")))
                .contains("repo/Source.java:1](file:", "#L1)");
    }

    @Test
    void fleetDiagramOmitsArrowWhoseSourceIsATable(@TempDir Path tmp) throws Exception {
        var service = service("orders");
        var table = new Node.Table(Node.Table.idOf("orders", "items"), "orders", "items", "sql");
        var reversedOwnership = edge(Edge.Kind.OWNS, table.id(), service.id(), new TreeMap<>());

        DiagramOutput.write(new Graph(List.of(service, table), List.of(reversedOwnership), List.of()), tmp);

        String fleet = Files.readString(tmp.resolve("diagrams/fleet.puml"));
        assertThat(fleet).contains("\"orders\"").doesNotContain("\"items\"", "-->");
    }

    private static Node.Service service(String name) {
        return new Node.Service(Node.Service.idOf(name), name, "repo", ".");
    }

    private static Node.Endpoint endpoint(String service, String method, String path) {
        return new Node.Endpoint(Node.Endpoint.idOf(service, method, path), service, method, path);
    }

    private static Edge edge(Edge.Kind kind, String from, String to, TreeMap<String, String> attrs) {
        return new Edge(kind, from, to, Confidence.EXACT, attrs,
                List.of(new Evidence("repo", "Source.java", 1)));
    }
}
