package io.github.sunjirapath.ssg.diff;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class GraphDiffTest {

    @Test
    void reportsNoChangesForIdenticalGraphsOrMovedEvidence() {
        var a = service("a");
        var endpoint = endpoint("a", "GET", "/x");
        var base = graph(List.of(a, endpoint), List.of(edge(Edge.Kind.EXPOSES, a.id(), endpoint.id(), Confidence.EXACT, 1)));
        var moved = graph(List.of(a, endpoint), List.of(edge(Edge.Kind.EXPOSES, a.id(), endpoint.id(), Confidence.EXACT, 99)));

        assertThat(GraphDiff.report(base, base)).contains("No changes.");
        assertThat(GraphDiff.report(base, moved)).contains("No changes.");
    }

    @Test
    void reportsRemovedEndpointStillCalledByLiveService() {
        var a = service("a"); var b = service("b"); var endpoint = endpoint("a", "GET", "/x");
        var base = graph(List.of(a, b, endpoint), List.of(edge(Edge.Kind.CALLS, b.id(), endpoint.id(), Confidence.EXACT, 1)));
        var head = graph(List.of(a, b), List.of(new Edge(Edge.Kind.CALLS, b.id(), a.id(), Confidence.EXACT,
                new TreeMap<>(Map.of("method", "GET", "path", "/x")), List.of(evidence(1)))));

        var report = GraphDiff.report(base, head);
        assertThat(report).contains("## Breaking changes", "Removed `a GET /x` is still called by b")
                .contains("## Endpoints removed", "`a GET /x` — called by b");
    }

    @Test
    void reportsTopicThatLostItsOnlyProducer() {
        var a = service("a"); var b = service("b"); var topic = topic("t");
        var base = graph(List.of(a, b, topic), List.of(edge(Edge.Kind.PRODUCES, a.id(), topic.id(), Confidence.EXACT, 1), edge(Edge.Kind.CONSUMES, b.id(), topic.id(), Confidence.EXACT, 2)));
        var head = graph(List.of(a, b, topic), List.of(edge(Edge.Kind.CONSUMES, b.id(), topic.id(), Confidence.EXACT, 2)));

        assertThat(GraphDiff.report(base, head)).contains("Topic `t` has no producer any more but is still consumed by b");
    }

    @Test
    void reportsNewAndResolvedFindings() {
        var a = service("a");
        var baseFinding = new Finding(Finding.Kind.UNRESOLVED, "a", "old", List.of(evidence(1)));
        var drift = new Finding(Finding.Kind.CONTRACT_DRIFT, "a", "new", List.of(evidence(2)));

        var report = GraphDiff.report(graph(List.of(a), List.of(), List.of(baseFinding)), graph(List.of(a), List.of(), List.of(drift)));
        assertThat(report).contains("New contract drift in a: new", "## New findings", "## Resolved findings");
    }

    @Test
    void reportsConfidenceChanges() {
        var a = service("a"); var topic = topic("t");
        var base = graph(List.of(a, topic), List.of(edge(Edge.Kind.PRODUCES, a.id(), topic.id(), Confidence.EXACT, 1)));
        var head = graph(List.of(a, topic), List.of(edge(Edge.Kind.PRODUCES, a.id(), topic.id(), Confidence.UNRESOLVED, 1)));

        assertThat(GraphDiff.report(base, head)).contains("## Confidence changes", "EXACT → UNRESOLVED");
    }

    @Test
    void ignoresReachChainChanges() {
        var endpoint = endpoint("a", "GET", "/x"); var topic = topic("t");
        var first = edge(Edge.Kind.REACHES, endpoint.id(), topic.id(), Confidence.EXACT, 1, "chain", "A.one");
        var second = edge(Edge.Kind.REACHES, endpoint.id(), topic.id(), Confidence.EXACT, 1, "chain", "A.two");

        var report = GraphDiff.report(graph(List.of(endpoint, topic), List.of(first)), graph(List.of(endpoint, topic), List.of(second)));
        assertThat(report).doesNotContain("## Reach added", "## Reach removed").contains("No changes.");
    }

    private static Graph graph(List<Node> nodes, List<Edge> edges) { return graph(nodes, edges, List.of()); }
    private static Graph graph(List<Node> nodes, List<Edge> edges, List<Finding> findings) { return new Graph(nodes, edges, findings); }
    private static Node.Service service(String name) { return new Node.Service(Node.Service.idOf(name), name, name, "."); }
    private static Node.Endpoint endpoint(String service, String method, String path) { return new Node.Endpoint(Node.Endpoint.idOf(service, method, path), service, method, path); }
    private static Node.Topic topic(String name) { return new Node.Topic(Node.Topic.idOf(name), name); }
    private static Evidence evidence(int line) { return new Evidence("repo", "App.java", line); }
    private static Edge edge(Edge.Kind kind, String from, String to, Confidence confidence, int line, String... attrs) {
        var map = new TreeMap<String, String>();
        for (int i = 0; i < attrs.length; i += 2) map.put(attrs[i], attrs[i + 1]);
        return new Edge(kind, from, to, confidence, map, List.of(evidence(line)));
    }
}
