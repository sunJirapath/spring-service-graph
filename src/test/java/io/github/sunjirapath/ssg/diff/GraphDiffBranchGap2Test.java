package io.github.sunjirapath.ssg.diff;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class GraphDiffBranchGap2Test {
    @Test
    void removedProducerIsBreakingOnlyWhenHeadStillHasConsumerAndCallsNeedMatchingCallerAndAttributes() {
        var producer = service("producer");
        var caller = service("caller");
        var other = service("other");
        var endpoint = endpoint("producer", "GET", "/items");
        var topic = new Node.Topic(Node.Topic.idOf("rabbit", "events"), "events", "rabbit");
        var base = new Graph(List.of(producer, caller, other, endpoint, topic), List.of(
                edge(Edge.Kind.CALLS, caller.id(), endpoint.id(), Map.of("method", "GET", "path", "/items")),
                edge(Edge.Kind.CALLS, other.id(), endpoint.id(), Map.of("method", "GET", "path", "/items")),
                edge(Edge.Kind.PRODUCES, producer.id(), topic.id(), Map.of()),
                edge(Edge.Kind.CONSUMES, caller.id(), topic.id(), Map.of())), List.of());
        var head = new Graph(List.of(producer, caller, other, topic), List.of(
                edge(Edge.Kind.CALLS, other.id(), producer.id(), Map.of("method", "GET", "path", "/items")),
                edge(Edge.Kind.CALLS, caller.id(), producer.id(), Map.of("method", "GET")),
                edge(Edge.Kind.CALLS, caller.id(), producer.id(), Map.of("path", "/items")),
                edge(Edge.Kind.CONSUMES, caller.id(), topic.id(), Map.of())), List.of());

        assertThat(GraphDiff.compare(base, head).breaking())
                .containsExactly("Removed `producer GET /items` is still called by other",
                        "Topic `events` (rabbit) has no producer any more but is still consumed by caller");
    }

    @Test
    void diffLabelsQualifiedTablesTopicsAndUnknownEdgeEndpoints() {
        var service = service("store");
        var caller = service("caller");
        var table = new Node.Table(Node.Table.idOf("store", "orders", "sql"), "store", "orders", "sql");
        var topic = new Node.Topic(Node.Topic.idOf("kafka", "updates"), "updates", "kafka");
        var abandoned = new Node.Topic(Node.Topic.idOf("kafka", "abandoned"), "abandoned", "kafka");
        var removed = endpoint("store", "GET", "/gone");
        var base = new Graph(List.of(service, caller, table, topic, abandoned, removed), List.of(
                edge(Edge.Kind.OWNS, service.id(), table.id(), Map.of()),
                edge(Edge.Kind.PRODUCES, service.id(), topic.id(), Map.of()),
                edge(Edge.Kind.PRODUCES, service.id(), abandoned.id(), Map.of()),
                edge(Edge.Kind.CALLS, table.id(), removed.id(), Map.of()),
                edge(Edge.Kind.CALLS, topic.id(), removed.id(), Map.of()),
                edge(Edge.Kind.CALLS, "missing-owner", removed.id(), Map.of()),
                edge(Edge.Kind.CALLS, caller.id(), removed.id(), Map.of())), List.of());
        var head = new Graph(List.of(service, caller, topic, abandoned), List.of(
                edge(Edge.Kind.CONSUMES, caller.id(), topic.id(), Map.of()),
                edge(Edge.Kind.CALLS, "missing-source", "missing-target", Map.of())), List.of());
        var markdown = GraphDiff.compare(base, head).markdown();

        assertThat(markdown).contains("table `store/orders` (sql)", "topic `updates` (kafka)", "`missing-source`", "`missing-target`");
    }

    @Test
    void legacyResourceLabelsOmitBackend() {
        var service = service("legacy");
        var table = new Node.Table(Node.Table.idOf("legacy", "items"), "legacy", "items", "sql");
        var topic = new Node.Topic(Node.Topic.idOf("events"), "events");
        var before = new Graph(List.of(service), List.of(), List.of());
        var after = new Graph(List.of(service, table, topic), List.of(
                edge(Edge.Kind.OWNS, service.id(), table.id(), Map.of()),
                edge(Edge.Kind.PRODUCES, service.id(), topic.id(), Map.of())), List.of());

        assertThat(GraphDiff.compare(before, after).markdown()).contains("table `legacy/items`", "topic `events`");
    }

    private static Node.Service service(String name) { return new Node.Service(Node.Service.idOf(name), name, "repo", "."); }
    private static Node.Endpoint endpoint(String service, String method, String path) {
        return new Node.Endpoint(Node.Endpoint.idOf(service, method, path), service, method, path);
    }
    private static Edge edge(Edge.Kind kind, String from, String to, Map<String, String> attrs) {
        return new Edge(kind, from, to, Confidence.EXACT, new TreeMap<>(attrs), List.of(new Evidence("repo", "App.java", 1)));
    }
}
