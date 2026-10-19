package io.github.sunjirapath.ssg.diff;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class DiffCoverageTest {

    @Test
    void addedEndpointListsCallersFromHeadGraph() {
        var target = service("target");
        var caller = service("caller");
        var endpoint = endpoint("target", "GET", "/new");
        Graph base = graph(List.of(target, caller), List.of());
        Graph head = graph(List.of(target, caller, endpoint),
                List.of(edge(Edge.Kind.CALLS, caller.id(), endpoint.id())));

        assertThat(GraphDiff.report(base, head))
                .contains("## Endpoints added", "`target GET /new`", "called by caller");
    }

    @Test
    void summaryCountsEveryNodeKind() {
        var nodes = List.<Node>of(
                service("orders"),
                endpoint("orders", "GET", "/items"),
                new Node.Topic(Node.Topic.idOf("created"), "created"),
                new Node.Table(Node.Table.idOf("orders", "items"), "orders", "items", "sql"),
                new Node.External(Node.External.idOf("stripe"), "stripe"));

        String report = GraphDiff.report(graph(), graph(nodes, List.of()));

        assertThat(report).contains(
                "| services | 0 | 1 |",
                "| endpoints | 0 | 1 |",
                "| topics | 0 | 1 |",
                "| tables | 0 | 1 |",
                "| externals | 0 | 1 |");
    }

    @Test
    void danglingEdgeUsesRawNodeIdAsLabel() {
        var caller = service("caller");
        Graph head = graph(List.of(caller), List.of(edge(Edge.Kind.CALLS, caller.id(), "missing:target")));

        assertThat(GraphDiff.report(graph(), head)).contains("**calls** `missing:target`");
    }

    @Test
    void reachSectionOmitsChainFromHumanReadableDetails() {
        var endpoint = endpoint("orders", "GET", "/items");
        var topic = new Node.Topic(Node.Topic.idOf("created"), "created");
        var attrs = new TreeMap<String, String>();
        attrs.put("chain", "Controller.get to Events.publish");
        var reach = new Edge(Edge.Kind.REACHES, endpoint.id(), topic.id(), Confidence.EXACT, attrs, List.of(ev(1)));

        String report = GraphDiff.report(graph(), graph(List.of(endpoint, topic), List.of(reach)));

        assertThat(report).contains("## Reach added", "**reaches** topic `created`")
                .doesNotContain("(chain=");
    }

    @Test
    void evidenceRenderingSkipsNullAndLimitsOutputToThreeEntries() {
        var caller = service("caller");
        var external = new Node.External(Node.External.idOf("vendor"), "vendor");
        var evidence = new ArrayList<Evidence>();
        evidence.add(null);
        evidence.add(ev(1));
        evidence.add(ev(2));
        evidence.add(ev(3));
        evidence.add(ev(4));
        var call = new Edge(Edge.Kind.CALLS, caller.id(), external.id(), Confidence.EXACT,
                new TreeMap<>(), evidence);

        String report = GraphDiff.report(graph(), graph(List.of(caller, external), List.of(call)));

        assertThat(report).contains("Source.java:1", "Source.java:2", "Source.java:3")
                .doesNotContain("Source.java:4");
    }

    private static Graph graph() {
        return graph(List.of(), List.of());
    }

    private static Graph graph(List<Node> nodes, List<Edge> edges) {
        return new Graph(nodes, edges, List.of());
    }

    private static Node.Service service(String name) {
        return new Node.Service(Node.Service.idOf(name), name, name, ".");
    }

    private static Node.Endpoint endpoint(String service, String method, String path) {
        return new Node.Endpoint(Node.Endpoint.idOf(service, method, path), service, method, path);
    }

    private static Edge edge(Edge.Kind kind, String from, String to) {
        return new Edge(kind, from, to, Confidence.EXACT, new TreeMap<>(), List.of(ev(1)));
    }

    private static Evidence ev(int line) {
        return new Evidence("repo", "Source.java", line);
    }
}
