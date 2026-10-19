package io.github.sunjirapath.ssg.mcp;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointLookupTest {

    @Test
    void differentlyNamedPathVariablesMatch() {
        var endpoint = endpoint("orders", "GET", "/x/{orderId}");

        assertThat(queries(endpoint).resolve("/x/{id}")).containsExactly(endpoint.id());
    }

    @Test
    void concretePathWithMethodMatchesTemplate() {
        var endpoint = endpoint("orders", "GET", "/x/{orderId}");

        assertThat(queries(endpoint).resolve("GET /x/123")).containsExactly(endpoint.id());
    }

    @Test
    void exactPathIsPreferredOverTemplateMatch() {
        var exact = endpoint("orders", "GET", "/x/123");
        var template = endpoint("orders", "GET", "/x/{orderId}");

        assertThat(queries(exact, template).resolve("GET /x/123")).containsExactly(exact.id());
    }

    @Test
    void catchAllIsReturnedOnlyWhenNoSpecificEndpointMatches() {
        var specific = endpoint("gateway", "*", "/api/orders/{id}");
        var catchAll = endpoint("gateway", "*", "/api/**");
        var queries = queries(specific, catchAll);

        assertThat(queries.resolve("GET /api/orders/7")).containsExactly(specific.id());
        assertThat(queries.resolve("GET /api/unknown/7")).containsExactly(catchAll.id());
    }

    @Test
    void methodFilterNarrowsTemplateMatches() {
        var get = endpoint("orders", "GET", "/x/{id}");
        var post = endpoint("orders", "POST", "/x/{id}");

        assertThat(queries(get, post).resolve("POST /x/1")).containsExactly(post.id());
    }

    @Test
    void optionalServiceNameNarrowsTemplateMatchesCaseInsensitively() {
        var orders = endpoint("orders", "GET", "/x/{id}");
        var billing = endpoint("billing", "GET", "/x/{id}");

        assertThat(queries(orders, billing).resolve("ORDERS GET /x/1")).containsExactly(orders.id());
    }

    @Test
    @SuppressWarnings("unchecked")
    void whoCallsUsesTemplateResolution() {
        var target = endpoint("orders", "GET", "/x/{orderId}");
        var caller = service("gateway");
        var edge = edge(Edge.Kind.CALLS, caller.id(), target.id());
        var graph = new Graph(List.of(caller, target), List.of(edge), List.of());

        var incoming = (List<Map<String, Object>>) new GraphQueries(graph).whoCalls("GET /x/123").get("incoming");

        assertThat(incoming).singleElement().satisfies(row -> {
            assertThat(row.get("from")).isEqualTo(caller.id());
            assertThat(row.get("to")).isEqualTo(target.id());
        });
    }

    @Test
    void impactRowsContainDepthAndNormalDepthHasNoNote() {
        var result = new GraphQueries(chainGraph(4)).impactOf("target", 3);

        assertThat(result).containsEntry("depth", 3).doesNotContainKey("note");
        assertThat((List<?>) result.get("affected")).hasSize(3).allSatisfy(row ->
                assertThat(((Map<?, ?>) row).containsKey("depth")).isTrue());
    }

    @Test
    void depthZeroIsClampedToOneWithNote() {
        var result = new GraphQueries(chainGraph(4)).impactOf("target", 0);

        assertThat(result).containsEntry("depth", 1)
                .containsEntry("note", "depth 0 is outside 1-10; used 1");
        assertThat((List<?>) result.get("affected")).hasSize(1);
    }

    @Test
    void depthElevenIsClampedToTenWithNote() {
        var result = new GraphQueries(chainGraph(12)).impactOf("target", 11);

        assertThat(result).containsEntry("depth", 10)
                .containsEntry("note", "depth 11 is outside 1-10; used 10");
        assertThat((List<?>) result.get("affected")).hasSize(10);
    }

    private static GraphQueries queries(Node.Endpoint... endpoints) {
        return new GraphQueries(new Graph(List.of(endpoints), List.of(), List.of()));
    }

    private static Graph chainGraph(int length) {
        var nodes = new ArrayList<Node>();
        var edges = new ArrayList<Edge>();
        var target = new Node.Topic(Node.Topic.idOf("target"), "target");
        nodes.add(target);
        String previous = target.id();
        for (int i = 1; i <= length; i++) {
            var endpoint = endpoint("service-" + i, "GET", "/" + i);
            nodes.add(endpoint);
            edges.add(edge(Edge.Kind.REACHES, endpoint.id(), previous));
            previous = endpoint.id();
        }
        return new Graph(nodes, edges, List.of());
    }

    private static Node.Service service(String name) {
        return new Node.Service(Node.Service.idOf(name), name, name, ".");
    }

    private static Node.Endpoint endpoint(String service, String method, String path) {
        return new Node.Endpoint(Node.Endpoint.idOf(service, method, path), service, method, path);
    }

    private static Edge edge(Edge.Kind kind, String from, String to) {
        return new Edge(kind, from, to, Confidence.EXACT, new TreeMap<>(),
                List.of(new Evidence("repo", "Source.java", 1)));
    }

}
