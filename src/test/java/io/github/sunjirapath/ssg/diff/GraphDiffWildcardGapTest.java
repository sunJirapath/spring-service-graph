package io.github.sunjirapath.ssg.diff;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class GraphDiffWildcardGapTest {

    // Pins GraphDiff.java:166: wildcard and case-insensitive call methods still match a removed endpoint.
    @ParameterizedTest
    @ValueSource(strings = {"*", "get"})
    void removedEndpointRemainsBreakingWhenTheRecordedCallStillMatches(String method) {
        var orders = service("orders");
        var caller = service("gateway");
        var endpoint = new Node.Endpoint(Node.Endpoint.idOf("orders", "GET", "/orders/{id}"),
                "orders", "GET", "/orders/{id}");
        Graph before = new Graph(List.of(orders, caller, endpoint), List.of(
                new Edge(Edge.Kind.CALLS, caller.id(), endpoint.id(), Confidence.EXACT,
                        new TreeMap<>(Map.of("method", method, "path", "/orders/{id}")), List.of(ev(2)))), List.of());
        Graph after = new Graph(List.of(orders, caller), List.of(
                new Edge(Edge.Kind.CALLS, caller.id(), orders.id(), Confidence.EXACT,
                        new TreeMap<>(Map.of("method", method, "path", "/orders/{id}")), List.of(ev(3)))), List.of());

        assertThat(GraphDiff.compare(before, after).breaking())
                .containsExactly("Removed `orders GET /orders/{id}` is still called by gateway");
    }

    private static Node.Service service(String name) {
        return new Node.Service(Node.Service.idOf(name), name, name, ".");
    }

    private static Evidence ev(int line) {
        return new Evidence("repo", "Source.java", line);
    }
}
