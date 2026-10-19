package io.github.sunjirapath.ssg.diff;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class GraphDiffCompareTest {

    @Test
    void compareReturnsNoBreakingChangesForIdenticalGraphs() {
        var orders = service("orders");
        var endpoint = endpoint("orders", "GET", "/orders");
        var graph = graph(List.of(orders, endpoint), List.of(edge(Edge.Kind.EXPOSES, orders.id(), endpoint.id())));

        var result = GraphDiff.compare(graph, graph);

        assertThat(result.breaking()).isEmpty();
        assertThat(result.markdown()).contains("_None found._", "No changes.");
    }

    @Test
    void compareReportsRemovedEndpointStillCalledByAnotherLiveServiceAsBreaking() {
        var orders = service("orders");
        var gateway = service("gateway");
        var endpoint = endpoint("orders", "GET", "/orders");
        var base = graph(List.of(orders, gateway, endpoint), List.of(edge(Edge.Kind.CALLS, gateway.id(), endpoint.id())));
        var head = graph(List.of(orders, gateway), List.of());

        var result = GraphDiff.compare(base, head);
        var expected = "Removed `orders GET /orders` is still called by gateway";

        assertThat(result.breaking()).containsExactly(expected);
        assertThat(result.markdown()).contains("- " + expected);
    }

    @Test
    void compareSortsAllBreakingCausesAndRendersThemInTheSameOrder() {
        var orders = service("orders");
        var gateway = service("gateway");
        var billing = service("billing");
        var endpoint = endpoint("orders", "GET", "/orders");
        var topic = topic("orders.created");
        var base = graph(List.of(orders, gateway, billing, endpoint, topic), List.of(
                edge(Edge.Kind.CALLS, gateway.id(), endpoint.id()),
                edge(Edge.Kind.PRODUCES, orders.id(), topic.id()),
                edge(Edge.Kind.CONSUMES, billing.id(), topic.id())));
        var drift = new Finding(Finding.Kind.CONTRACT_DRIFT, "gateway", "GET /legacy", List.of(evidence()));
        var head = graph(List.of(orders, gateway, billing, topic),
                List.of(edge(Edge.Kind.CONSUMES, billing.id(), topic.id())), List.of(drift));
        var driftLine = "New contract drift in gateway: GET /legacy — `repo/App.java:1`";
        var endpointLine = "Removed `orders GET /orders` is still called by gateway";
        var topicLine = "Topic `orders.created` has no producer any more but is still consumed by billing";

        var result = GraphDiff.compare(base, head);

        assertThat(result.breaking()).containsExactly(driftLine, endpointLine, topicLine);
        assertThat(result.markdown().indexOf("- " + driftLine))
                .isLessThan(result.markdown().indexOf("- " + endpointLine));
        assertThat(result.markdown().indexOf("- " + endpointLine))
                .isLessThan(result.markdown().indexOf("- " + topicLine));
    }

    @Test
    void reportDelegatesToCompareMarkdown() {
        var graph = graph(List.of(service("orders")), List.of());

        assertThat(GraphDiff.report(graph, graph)).isEqualTo(GraphDiff.compare(graph, graph).markdown());
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

    private static Edge edge(Edge.Kind kind, String from, String to) {
        return new Edge(kind, from, to, Confidence.EXACT, new TreeMap<>(), List.of(evidence()));
    }

    private static Evidence evidence() {
        return new Evidence("repo", "App.java", 1);
    }
}
