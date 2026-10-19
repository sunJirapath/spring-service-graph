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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LinkCoverageTest {

    @Test
    void endpointDoubleStarFailsWhenRequiredSuffixNeverMatches() {
        assertThat(PathMatch.matches("/api/orders/42", "/api/**/status")).isFalse();
    }

    @Test
    void twoTemplatedSegmentsRequireCompatiblePrefixesAndSuffixes() {
        assertThat(PathMatch.matches("/a-{left}-z", "/a-{right}-z")).isTrue();
        // both match "a-long-x-z" (left = "long-x", right = "x")
        assertThat(PathMatch.matches("/a-{left}-z", "/a-long-{right}-z")).isTrue();
        assertThat(PathMatch.matches("/a-{left}-z", "/a-{right}-q")).isFalse();
        // "prefix-" needs an f where "pre-" needs a dash: no path can match both
        assertThat(PathMatch.matches("/prefix-{left}-z", "/pre-{right}-long-z")).isFalse();
        assertThat(PathMatch.matches("/pre-{left}-z", "/pre-fix-{right}-long-z")).isTrue();
    }

    @Test
    void unresolvedEndpointCreatesFindingAndUnresolvedExposeEdge() {
        Value path = new Value("/items/{?}", Confidence.UNRESOLVED, "\"/items/\" + suffix", List.of());
        Graph graph = link(service("api", new Fact.Endpoint("GET", path, ev("Api.java", 4), "controller", ev("Api.java", 4))));

        assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.EXPOSES).singleElement()
                .satisfies(e -> assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED));
        assertThat(graph.findings()).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(Finding.Kind.UNRESOLVED);
            assertThat(f.message()).contains("endpoint GET /items/{?}", "\"/items/\" + suffix");
        });
    }

    @Test
    void unresolvedConsumedTopicUsesConsumerWording() {
        Value topic = Value.unresolved("topicFor(tenant)");
        Graph graph = link(service("worker", new Fact.TopicUse(false, topic, ev(2), "KafkaListener")));

        assertThat(graph.findings()).singleElement().satisfies(f ->
                assertThat(f.message()).startsWith("consumed topic").contains("topicFor(tenant)"));
        assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.CONSUMES).hasSize(1);
    }

    @Test
    void unresolvedTableCreatesFindingWithoutDroppingUseEdge() {
        Value table = Value.unresolved("tableName()");
        Graph graph = link(service("legacy", new Fact.TableUse(false, table, "sql", ev(3), "@Entity")));

        assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.USES).singleElement()
                .satisfies(e -> assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED));
        assertThat(graph.findings()).singleElement().satisfies(f -> assertThat(f.message()).contains("table", "tableName()"));
    }

    @Test
    void otherwiseIdenticalFindingsAreSortedByEvidence() {
        var later = new Finding(Finding.Kind.PARSE_ERROR, "svc", "same", List.of(ev("Z.java", 9)));
        var earlier = new Finding(Finding.Kind.PARSE_ERROR, "svc", "same", List.of(ev("A.java", 1)));

        Graph graph = Linker.link(List.of(service("svc")), Map.of(), List.of(later, earlier));

        assertThat(graph.findings()).extracting(f -> f.evidence().getFirst().file())
                .containsExactly("A.java", "Z.java");
    }

    @Test
    void emptyAuthorityIsAnUnresolvedTarget() {
        Graph graph = link(service("caller", new Fact.Call("rest-template", "GET", Value.exact("http:///items"), ev(1))));

        assertThat(calls(graph)).singleElement().satisfies(e -> {
            assertThat(e.to()).isEqualTo(Linker.UNRESOLVED_TARGET);
            assertThat(e.attrs()).containsEntry("path", "/items");
        });
    }

    @Test
    void unknownSchemePreventsHostInference() {
        Value url = new Value("ht{?}tp://orders/items", Confidence.UNRESOLVED, "scheme + rest", List.of());
        Graph graph = link(service("caller", new Fact.Call("rest-client", "GET", url, ev(1))));

        assertThat(calls(graph)).extracting(Edge::to).containsExactly(Linker.UNRESOLVED_TARGET);
    }

    @Test
    void fragmentWithoutQueryIsRemovedFromParsedPath() {
        Linker.Url url = Linker.parse("https://orders/items#details");

        assertThat(url.host()).isEqualTo("orders");
        assertThat(url.path()).isEqualTo("/items");
    }

    @Test
    void emptyRelativeUrlNormalizesToRoot() {
        Linker.Url url = Linker.parse("");

        assertThat(url.host()).isNull();
        assertThat(url.path()).isEqualTo("/");
    }

    private static Graph link(Linker.ServiceFacts... services) {
        return Linker.link(List.of(services), Map.of(), List.of());
    }

    private static Linker.ServiceFacts service(String name, Fact... facts) {
        return new Linker.ServiceFacts(name, name + "-repo", ".", List.of(facts));
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
