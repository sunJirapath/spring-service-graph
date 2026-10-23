package io.github.sunjirapath.ssg.diff;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class GraphDiffEdgeCasesTest {

    @Test
    void emptyGraphsReportZeroCountsAndNoChanges() {
        String report = GraphDiff.report(graph(), graph());

        assertThat(report).contains("| services | 0 | 0 |", "| edges | 0 | 0 |", "| findings | 0 | 0 |",
                "## Breaking changes\n\n_None found._", "No changes.");
    }

    @Test
    void addedServiceIsReported() {
        String report = GraphDiff.report(graph(), graph(List.of(service("orders")), List.of()));

        assertThat(report).contains("## Services added", "- service `orders`").doesNotContain("## Services removed");
    }

    @Test
    void removedServiceStillCalledByLiveServiceIsBreaking() {
        var orders = service("orders");
        var gateway = service("gateway");
        Graph base = graph(List.of(orders, gateway), List.of(edge(Edge.Kind.CALLS, gateway.id(), orders.id(), 1)));
        var external = new Node.External("external:orders", "orders");
        Graph head = graph(List.of(gateway, external), List.of(edge(Edge.Kind.CALLS, gateway.id(), external.id(), 1)));

        assertThat(GraphDiff.report(base, head))
                .contains("Removed service `orders` is still called by gateway", "## Services removed");
    }

    @Test
    void removedEndpointCalledOnlyByItsOwnServiceIsNotBreaking() {
        var orders = service("orders");
        var endpoint = endpoint("orders", "GET", "/internal");
        Graph base = graph(List.of(orders, endpoint), List.of(edge(Edge.Kind.CALLS, orders.id(), endpoint.id(), 1)));
        Graph head = graph(List.of(orders), List.of());

        // EXPECTATION?: An in-service self-call is implementation detail, not a cross-service compatibility break.
        assertThat(GraphDiff.report(base, head)).contains("## Breaking changes\n\n_None found._");
    }

    @Test
    void removedEndpointWhoseCallerServiceWasAlsoRemovedIsNotBreaking() {
        var orders = service("orders");
        var gateway = service("gateway");
        var endpoint = endpoint("orders", "GET", "/orders");
        Graph base = graph(List.of(orders, gateway, endpoint), List.of(edge(Edge.Kind.CALLS, gateway.id(), endpoint.id(), 1)));
        Graph head = graph(List.of(orders), List.of());

        assertThat(GraphDiff.report(base, head)).contains("## Breaking changes\n\n_None found._")
                .doesNotContain("is still called by gateway");
    }

    @Test
    void topicGainingProducerIsReportedAsMessagingAdded() {
        var producer = service("producer");
        var topic = topic("orders.created");
        Graph base = graph(List.of(producer, topic), List.of());
        Graph head = graph(List.of(producer, topic), List.of(edge(Edge.Kind.PRODUCES, producer.id(), topic.id(), 2)));

        assertThat(GraphDiff.report(base, head)).contains("## Messaging added", "**produces** topic `orders.created`");
    }

    @Test
    void addedConsumerIsReportedAsMessagingAdded() {
        var consumer = service("consumer");
        var topic = topic("orders.created");
        Graph base = graph(List.of(consumer, topic), List.of());
        Graph head = graph(List.of(consumer, topic), List.of(edge(Edge.Kind.CONSUMES, consumer.id(), topic.id(), 2)));

        assertThat(GraphDiff.report(base, head)).contains("## Messaging added", "**consumes** topic `orders.created`");
    }

    @Test
    void tableOwnershipMovingServicesReportsRemovedAndAddedData() {
        var oldOwner = service("orders-v1");
        var newOwner = service("orders-v2");
        var oldTable = table("orders-v1", "orders");
        var newTable = table("orders-v2", "orders");
        Graph base = graph(List.of(oldOwner, newOwner, oldTable),
                List.of(edge(Edge.Kind.OWNS, oldOwner.id(), oldTable.id(), 1)));
        Graph head = graph(List.of(oldOwner, newOwner, newTable),
                List.of(edge(Edge.Kind.OWNS, newOwner.id(), newTable.id(), 2)));

        assertThat(GraphDiff.report(base, head)).contains("## Data added", "table `orders-v2/orders`",
                "## Data removed", "table `orders-v1/orders`");
    }

    @Test
    void findingWithOnlyChangedEvidenceIsNotNewOrResolved() {
        var service = service("orders");
        var before = finding(Finding.Kind.UNRESOLVED, "orders", "dynamic URL", 1);
        var after = finding(Finding.Kind.UNRESOLVED, "orders", "dynamic URL", 99);

        String report = GraphDiff.report(graph(List.of(service), List.of(), List.of(before)),
                graph(List.of(service), List.of(), List.of(after)));

        assertThat(report).contains("No changes.").doesNotContain("## New findings", "## Resolved findings");
    }

    @Test
    void breakingItemsAreSortedLexicographically() {
        var alpha = service("alpha");
        var beta = service("beta");
        var caller = service("caller");
        var zed = endpoint("alpha", "GET", "/zed");
        var able = endpoint("beta", "GET", "/able");
        Graph base = graph(List.of(alpha, beta, caller, zed, able), List.of(
                edge(Edge.Kind.CALLS, caller.id(), zed.id(), 1), edge(Edge.Kind.CALLS, caller.id(), able.id(), 2)));
        Graph head = graph(List.of(alpha, beta, caller), List.of(
                new Edge(Edge.Kind.CALLS, caller.id(), alpha.id(), Confidence.EXACT,
                        new TreeMap<>(Map.of("method", "GET", "path", "/zed")), List.of(ev(1))),
                new Edge(Edge.Kind.CALLS, caller.id(), beta.id(), Confidence.EXACT,
                        new TreeMap<>(Map.of("method", "GET", "path", "/able")), List.of(ev(2)))), List.of(
                finding(Finding.Kind.CONTRACT_DRIFT, "caller", "middle", 3)));

        List<String> breaking = sectionBullets(GraphDiff.report(base, head), "## Breaking changes");
        assertThat(breaking).hasSize(3);
        assertThat(breaking).containsExactlyElementsOf(breaking.stream().sorted().toList());
    }

    @Test
    void reportIsDeterministicRegardlessOfInputOrder() {
        var nodes = new ArrayList<Node>(List.of(service("z"), service("a"), topic("topic")));
        var edges = new ArrayList<Edge>(List.of(edge(Edge.Kind.PRODUCES, Node.Service.idOf("z"), Node.Topic.idOf("topic"), 2),
                edge(Edge.Kind.CONSUMES, Node.Service.idOf("a"), Node.Topic.idOf("topic"), 1)));
        var findings = new ArrayList<Finding>(List.of(finding(Finding.Kind.UNRESOLVED, "z", "z problem", 2),
                finding(Finding.Kind.PARSE_ERROR, "a", "a problem", 1)));
        Graph ordered = graph(List.copyOf(nodes), List.copyOf(edges), List.copyOf(findings));
        Collections.reverse(nodes);
        Collections.reverse(edges);
        Collections.reverse(findings);
        Graph reversed = graph(nodes, edges, findings);

        assertThat(GraphDiff.report(graph(), ordered)).isEqualTo(GraphDiff.report(graph(), reversed));
    }

    private static List<String> sectionBullets(String report, String heading) {
        String body = report.substring(report.indexOf(heading) + heading.length()).stripLeading();
        int next = body.indexOf("\n## ");
        if (next >= 0) body = body.substring(0, next);
        return body.lines().filter(line -> line.startsWith("- ")).toList();
    }

    private static Graph graph() {
        return graph(List.of(), List.of());
    }

    private static Graph graph(List<Node> nodes, List<Edge> edges) {
        return graph(nodes, edges, List.of());
    }

    private static Graph graph(List<Node> nodes, List<Edge> edges, List<Finding> findings) {
        return new Graph(nodes, edges, findings);
    }

    private static Node.Service service(String name) {
        return new Node.Service(Node.Service.idOf(name), name, name, ".");
    }

    private static Node.Endpoint endpoint(String service, String method, String path) {
        return new Node.Endpoint(Node.Endpoint.idOf(service, method, path), service, method, path);
    }

    private static Node.Topic topic(String name) {
        return new Node.Topic(Node.Topic.idOf(name), name);
    }

    private static Node.Table table(String service, String name) {
        return new Node.Table(Node.Table.idOf(service, name), service, name, "sql");
    }

    private static Finding finding(Finding.Kind kind, String service, String message, int line) {
        return new Finding(kind, service, message, List.of(ev(line)));
    }

    private static Edge edge(Edge.Kind kind, String from, String to, int line) {
        return new Edge(kind, from, to, Confidence.EXACT, new TreeMap<>(), List.of(ev(line)));
    }

    private static Evidence ev(int line) {
        return new Evidence("repo", "Source.java", line);
    }
}
