package io.github.sunjirapath.ssg.link;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import io.github.sunjirapath.ssg.model.Value;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LinkerEdgeCasesTest {

    @Test
    void loadBalancedNameLinksCaseInsensitivelyToService() {
        Graph graph = link(service("caller", call("feign", "GET", exact("lb://ORDERS/orders/1"), ev("Call.java", 3))),
                service("Orders", endpoint("GET", "/orders/{id}", ev("Orders.java", 4))));

        assertThat(calls(graph)).singleElement().satisfies(e -> assertThat(e.to())
                .isEqualTo(Node.Endpoint.idOf("Orders", "GET", "/orders/{id}")));
    }

    @Test
    void hostEqualToServiceNameLinksCaseInsensitively() {
        Graph graph = link(service("caller", call("rest-template", "GET", exact("http://oRdErS/orders"), ev(1))),
                service("Orders", endpoint("GET", "/orders", ev(2))));

        assertThat(calls(graph)).extracting(Edge::to).containsExactly(Node.Endpoint.idOf("Orders", "GET", "/orders"));
    }

    @Test
    void hostPortIsIgnoredWhenResolvingService() {
        Graph graph = link(service("caller", call("http-exchange", "GET", exact("http://orders:8080/orders"), ev(1))),
                service("orders", endpoint("GET", "/orders", ev(2))));

        assertThat(calls(graph)).extracting(Edge::to).containsExactly(Node.Endpoint.idOf("orders", "GET", "/orders"));
    }

    @Test
    void httpsHostLinksToService() {
        Graph graph = link(service("caller", call("rest-client", "GET", exact("https://orders/orders"), ev(1))),
                service("orders", endpoint("GET", "/orders", ev(2))));

        assertThat(calls(graph)).extracting(Edge::to).containsExactly(Node.Endpoint.idOf("orders", "GET", "/orders"));
    }

    @Test
    void urlUserInfoIsStrippedAndNeverStoredInAttributes() {
        Graph graph = link(service("caller", call("rest-template", "GET",
                exact("https://alice:secret@api.example:8443/orders?token=x#frag"), ev(1))));

        assertThat(calls(graph)).singleElement().satisfies(e -> {
            assertThat(e.to()).isEqualTo(Node.External.idOf("api.example"));
            assertThat(e.attrs()).containsEntry("path", "/orders");
            assertThat(e.attrs().values()).allSatisfy(v -> assertThat(v).doesNotContain("alice", "secret", "token=x"));
        });
    }

    @Test
    void aliasHostLinksToKnownService() {
        Graph graph = link(Map.of("orders.internal", "orders"),
                service("caller", call("rest-client", "GET", exact("http://orders.internal/orders"), ev(1))),
                service("orders", endpoint("GET", "/orders", ev(2))));

        assertThat(calls(graph)).extracting(Edge::to).containsExactly(Node.Endpoint.idOf("orders", "GET", "/orders"));
    }

    @Test
    void aliasToUnknownNameBecomesNamedExternal() {
        Graph graph = link(Map.of("api.stripe.com", "stripe"),
                service("billing", call("rest-client", "POST", exact("https://api.stripe.com/charges"), ev(1))));

        assertThat(calls(graph)).extracting(Edge::to).containsExactly(Node.External.idOf("stripe"));
        assertThat(graph.nodes()).contains(new Node.External(Node.External.idOf("stripe"), "stripe"));
    }

    @Test
    void unknownHostBecomesExternalNamedByHost() {
        Graph graph = link(service("caller", call("web-client", "GET", exact("https://weather.example/forecast"), ev(1))));

        assertThat(calls(graph)).extracting(Edge::to).containsExactly(Node.External.idOf("weather.example"));
    }

    @Test
    void relativeUrlBecomesUnresolvedExternal() {
        Graph graph = link(service("caller", call("rest-template", "GET", exact("/relative/path"), ev(1))));

        assertThat(calls(graph)).singleElement().satisfies(e -> {
            assertThat(e.to()).isEqualTo(Linker.UNRESOLVED_TARGET);
            assertThat(e.attrs()).containsEntry("path", "/relative/path").containsEntry("raw", "/relative/path");
        });
    }

    @Test
    void unresolvedUrlPreservesRawExpressionInAttributes() {
        Value url = new Value("{?}/reports/{?}", Confidence.UNRESOLVED, "baseUrl + id", List.of());
        Graph graph = link(service("caller", call("rest-template", "GET", url, ev(1))));

        assertThat(calls(graph)).singleElement().satisfies(e -> assertThat(e.attrs())
                .containsEntry("raw", "baseUrl + id").containsEntry("path", "{?}/reports/{?}"));
    }

    @Test
    void partiallyUnknownHostBecomesUnresolvedExternal() {
        Value url = new Value("https://reports-{?}.internal/report", Confidence.UNRESOLVED,
                "\"https://reports-\" + tenant + \".internal/report\"", List.of());
        Graph graph = link(service("caller", call("web-client", "GET", url, ev(1))));

        assertThat(calls(graph)).extracting(Edge::to).containsExactly(Linker.UNRESOLVED_TARGET);
    }

    @Test
    void matchingMethodAndTemplateLinksCallToEndpoint() {
        Graph graph = link(service("caller", call("feign", "GET", exact("lb://orders/orders/42"), ev(1))),
                service("orders", endpoint("GET", "/orders/{id}", ev(2))));

        assertThat(calls(graph)).extracting(Edge::to).containsExactly(Node.Endpoint.idOf("orders", "GET", "/orders/{id}"));
        assertThat(graph.findings()).noneMatch(f -> f.kind() == Finding.Kind.CONTRACT_DRIFT);
    }

    @Test
    void literalEndpointBeatsTemplateSibling() {
        Graph graph = link(service("caller", call("feign", "GET", exact("lb://brand/brand/listAll"), ev(1))),
                service("brand", endpoint("GET", "/brand/listAll", ev(2)), endpoint("GET", "/brand/{id}", ev(3))));

        assertThat(calls(graph)).extracting(Edge::to).containsExactly(Node.Endpoint.idOf("brand", "GET", "/brand/listAll"));
    }

    @Test
    void wildcardMethodOnEitherSideMatches() {
        Graph graph = link(service("caller",
                        call("gateway", "*", exact("lb://orders/orders"), ev("Routes.java", 1)),
                        call("rest-template", "POST", exact("lb://orders/status"), ev("Call.java", 2))),
                service("orders", endpoint("GET", "/orders", ev(3)), endpoint("*", "/status", ev(4))));

        assertThat(calls(graph)).extracting(Edge::to).containsExactlyInAnyOrder(
                Node.Endpoint.idOf("orders", "GET", "/orders"), Node.Endpoint.idOf("orders", "*", "/status"));
    }

    @Test
    void resolvedPathWithoutEndpointCreatesDriftAndServiceEdge() {
        Graph graph = link(service("caller", call("feign", "DELETE", exact("lb://orders/orders/1"), ev(1))),
                service("orders", endpoint("GET", "/orders/{id}", ev(2))));

        assertThat(calls(graph)).extracting(Edge::to).containsExactly(Node.Service.idOf("orders"));
        assertThat(graph.findings()).filteredOn(f -> f.kind() == Finding.Kind.CONTRACT_DRIFT).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("DELETE /orders/1", "orders"));
    }

    @Test
    void unknownPathWithoutEndpointDoesNotCreateContractDrift() {
        Value url = new Value("lb://orders/orders/{?}", Confidence.UNRESOLVED, "dynamic", List.of());
        Graph graph = link(service("caller", call("feign", "DELETE", url, ev(1))), service("orders"));

        assertThat(calls(graph)).extracting(Edge::to).containsExactly(Node.Service.idOf("orders"));
        assertThat(graph.findings()).noneMatch(f -> f.kind() == Finding.Kind.CONTRACT_DRIFT);
    }

    @Test
    void queryStringAndFragmentAreIgnoredForEndpointMatching() {
        Graph graph = link(service("caller", call("rest-client", "GET",
                        exact("http://orders/orders/1?verbose=true#details"), ev(1))),
                service("orders", endpoint("GET", "/orders/{id}", ev(2))));

        assertThat(calls(graph)).singleElement().satisfies(e -> {
            assertThat(e.to()).isEqualTo(Node.Endpoint.idOf("orders", "GET", "/orders/{id}"));
            assertThat(e.attrs()).containsEntry("path", "/orders/1");
        });
    }

    @Test
    void trailingSlashDifferenceStillMatches() {
        Graph graph = link(service("caller", call("feign", "GET", exact("lb://orders/orders/"), ev(1))),
                service("orders", endpoint("GET", "/orders", ev(2))));

        assertThat(calls(graph)).extracting(Edge::to).containsExactly(Node.Endpoint.idOf("orders", "GET", "/orders"));
    }

    @Test
    void gatewayCatchAllCanMatchSeveralEndpoints() {
        Graph graph = link(service("gateway", call("gateway", "*", exact("lb://orders/**"), ev(1))),
                service("orders", endpoint("GET", "/orders", ev(2)), endpoint("POST", "/orders", ev(3))));

        assertThat(calls(graph)).extracting(Edge::to).containsExactlyInAnyOrder(
                Node.Endpoint.idOf("orders", "GET", "/orders"), Node.Endpoint.idOf("orders", "POST", "/orders"));
    }

    @Test
    void duplicateEdgesMergeEvidenceWithCallSitesBeforeViaAndBestConfidence() {
        Evidence definition = ev("Config.java", 1);
        Value resolved = new Value("https://api.example/x", Confidence.RESOLVED, "URL", List.of(definition));
        Graph graph = link(service("caller",
                call("rest-client", "GET", resolved, ev("B.java", 20)),
                call("rest-client", "GET", exact("https://api.example/x"), ev("A.java", 10))));

        assertThat(calls(graph)).singleElement().satisfies(e -> {
            assertThat(e.confidence()).isEqualTo(Confidence.EXACT);
            assertThat(e.evidence()).containsExactly(ev("A.java", 10), ev("B.java", 20), definition);
        });
    }

    @Test
    void unresolvedTopicUsesQuestionMarkFollowedByRawExpression() {
        Value topic = new Value(Value.UNKNOWN, Confidence.UNRESOLVED, "topicFor(tenant)", List.of());
        Graph graph = link(service("events", new Fact.TopicUse(true, topic, ev(1), "kafka-template")));

        assertThat(graph.nodes()).contains(new Node.Topic(Node.Topic.idOf("?topicFor(tenant)"), "?topicFor(tenant)"));
    }

    @Test
    void migrationAndEntityCreateOwnsAndUsesEdgesForOneTableNode() {
        Graph graph = link(service("orders",
                table(true, "orders", "sql", ev("V1.sql", 1)),
                table(false, "orders", "sql", ev("Order.java", 2))));

        assertThat(graph.nodes()).filteredOn(Node.Table.class::isInstance).hasSize(1);
        assertThat(graph.edges()).filteredOn(e -> e.to().equals(Node.Table.idOf("orders", "orders")))
                .extracting(Edge::kind).containsExactlyInAnyOrder(Edge.Kind.OWNS, Edge.Kind.USES);
    }

    @Test
    void mongoStoreWinsWhenSqlAndMongoFactsMerge() {
        Graph graph = link(service("catalog",
                table(true, "products", "sql", ev("V1.sql", 1)),
                table(false, "products", "mongo", ev("Product.java", 2))));

        assertThat(graph.nodes()).filteredOn(Node.Table.class::isInstance).singleElement()
                .isEqualTo(new Node.Table(Node.Table.idOf("catalog", "products"), "catalog", "products", "mongo"));
    }

    @Test
    void problemFactsBecomeFindingsOfTheirDeclaredKind() {
        Graph graph = link(service("orders", new Fact.Problem("MISSING_INTERFACE", "GeneratedApi is absent", ev(7))));

        assertThat(graph.findings()).containsExactly(new Finding(Finding.Kind.MISSING_INTERFACE, "orders",
                "GeneratedApi is absent", List.of(ev(7))));
    }

    @Test
    void reachEdgesPointFromEndpointToEveryKindOfSinkTarget() {
        Evidence handler = ev("Controller.java", 1);
        Evidence http = ev("Controller.java", 2);
        Evidence topic = ev("Controller.java", 3);
        Evidence table = ev("Controller.java", 4);
        Evidence external = ev("Controller.java", 5);
        Graph graph = link(service("api",
                        new Fact.Endpoint("GET", exact("/aggregate"), ev(1), "controller", handler),
                        call("feign", "GET", exact("lb://orders/orders"), http),
                        new Fact.TopicUse(true, exact("events"), topic, "kafka-template"),
                        table(false, "accounts", "sql", table),
                        call("rest-client", "GET", exact("https://weather.example/today"), external),
                        reach("GET", "/aggregate", handler, http), reach("GET", "/aggregate", handler, topic),
                        reach("GET", "/aggregate", handler, table), reach("GET", "/aggregate", handler, external)),
                service("orders", endpoint("GET", "/orders", ev(9))));

        assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.REACHES).extracting(Edge::to)
                .containsExactlyInAnyOrder(Node.Endpoint.idOf("orders", "GET", "/orders"), Node.Topic.idOf("events"),
                        Node.Table.idOf("api", "accounts"), Node.External.idOf("weather.example"));
    }

    @Test
    void graphOrderingIsDeterministicAcrossServiceAndFactInputOrder() {
        var firstFacts = new ArrayList<Fact>(List.of(endpoint("GET", "/b", ev(2)), endpoint("GET", "/a", ev(1)),
                new Fact.Problem("PARSE_ERROR", "z problem", ev(8))));
        var secondFacts = new ArrayList<Fact>(List.of(call("feign", "GET", exact("lb://beta/a"), ev(3)),
                new Fact.TopicUse(true, exact("z"), ev(4), "kafka-template"),
                new Fact.Problem("UNRESOLVED", "a problem", ev(7))));
        Graph forward = Linker.link(List.of(sf("beta", firstFacts), sf("alpha", secondFacts)), Map.of(), List.of());
        Collections.reverse(firstFacts);
        Collections.reverse(secondFacts);
        Graph reversed = Linker.link(List.of(sf("alpha", secondFacts), sf("beta", firstFacts)), Map.of(), List.of());

        assertThat(reversed).isEqualTo(forward);
    }

    @Test
    void identicalEndpointPathsInDifferentServicesRemainSeparate() {
        Graph graph = link(service("caller",
                        call("feign", "GET", exact("lb://orders/health"), ev(1)),
                        call("feign", "GET", exact("lb://billing/health"), ev(2))),
                service("orders", endpoint("GET", "/health", ev(3))),
                service("billing", endpoint("GET", "/health", ev(4))));

        assertThat(graph.nodes()).filteredOn(Node.Endpoint.class::isInstance).extracting(Node::id)
                .containsExactlyInAnyOrder(Node.Endpoint.idOf("orders", "GET", "/health"),
                        Node.Endpoint.idOf("billing", "GET", "/health"));
        assertThat(calls(graph)).extracting(Edge::to).containsExactlyInAnyOrder(
                Node.Endpoint.idOf("orders", "GET", "/health"), Node.Endpoint.idOf("billing", "GET", "/health"));
    }

    private static Graph link(Linker.ServiceFacts... services) {
        return link(Map.of(), services);
    }

    private static Graph link(Map<String, String> aliases, Linker.ServiceFacts... services) {
        return Linker.link(List.of(services), aliases, List.of());
    }

    private static Linker.ServiceFacts service(String name, Fact... facts) {
        return new Linker.ServiceFacts(name, name + "-repo", ".", List.of(facts));
    }

    private static Linker.ServiceFacts sf(String name, List<Fact> facts) {
        return new Linker.ServiceFacts(name, name + "-repo", ".", facts);
    }

    private static Fact.Endpoint endpoint(String method, String path, Evidence evidence) {
        return new Fact.Endpoint(method, exact(path), evidence, "controller", evidence);
    }

    private static Fact.Call call(String client, String method, Value url, Evidence evidence) {
        return new Fact.Call(client, method, url, evidence);
    }

    private static Fact.TableUse table(boolean owns, String name, String store, Evidence evidence) {
        return new Fact.TableUse(owns, exact(name), store, evidence, owns ? "migration" : "entity");
    }

    private static Fact.Reach reach(String method, String path, Evidence handler, Evidence sink) {
        return new Fact.Reach(method, path, handler, sink, "Controller.handle");
    }

    private static Value exact(String text) {
        return Value.exact(text);
    }

    private static Evidence ev(int line) {
        return ev("Source.java", line);
    }

    private static Evidence ev(String file, int line) {
        return new Evidence("repo", file, line);
    }

    private static List<Edge> calls(Graph graph) {
        return graph.edges().stream().filter(e -> e.kind() == Edge.Kind.CALLS).toList();
    }
}
