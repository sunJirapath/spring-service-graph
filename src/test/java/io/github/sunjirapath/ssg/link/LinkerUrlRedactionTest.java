package io.github.sunjirapath.ssg.link;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import io.github.sunjirapath.ssg.model.Value;
import io.github.sunjirapath.ssg.output.MarkdownOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LinkerUrlRedactionTest {

    private static final String AUTHORITY = "demo-user:FAKE_PASSWORD_CANARY@";
    private static final String QUERY = "?token=FAKE_TOKEN_CANARY#FAKE_FRAGMENT_CANARY";
    private static final Evidence CALL = new Evidence("repo", "Calls.java", 7);

    @Test
    void exactUrlDoesNotExposeUserInfoQueryOrFragment() throws Exception {
        Graph graph = link(Value.exact("https://" + AUTHORITY + "api.example/orders" + QUERY));

        assertThat(graph.edges()).singleElement().satisfies(e -> {
            assertThat(e.to()).isEqualTo(Node.External.idOf("api.example"));
            assertThat(e.attrs()).containsEntry("path", "/orders").doesNotContainKey("raw");
            assertThat(e.confidence()).isEqualTo(Confidence.EXACT);
        });
        assertSafeOutputs(graph);
    }

    @Test
    void partialUrlKeepsUnknownPathAndSafeRawExpression() throws Exception {
        Value url = new Value("https://" + AUTHORITY + "api.example/orders/{?}" + QUERY,
                Confidence.UNRESOLVED,
                "\"https://" + AUTHORITY + "api.example/orders/\" + id + \"" + QUERY + "\"", List.of());
        Graph graph = link(url);

        assertThat(graph.edges()).singleElement().satisfies(e -> {
            assertThat(e.to()).isEqualTo(Node.External.idOf("api.example"));
            assertThat(e.attrs()).containsEntry("path", "/orders/{?}");
            assertThat(e.attrs().get("raw")).contains("api.example/orders/", " + id + ", "[redacted]");
            assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED);
            assertThat(e.evidence()).containsExactly(CALL);
        });
        assertThat(graph.findings()).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(Finding.Kind.UNRESOLVED);
            assertThat(f.message()).contains("api.example/orders/{?}", " + id + ", "[redacted]");
        });
        assertSafeOutputs(graph);
    }

    @Test
    void unknownHostAndUnknownSchemeDoNotBypassRedaction() throws Exception {
        for (String scheme : List.of("https", "ht{?}tp")) {
            Value url = new Value(scheme + "://" + AUTHORITY + "{?}.example/orders/{?}" + QUERY,
                    Confidence.UNRESOLVED,
                    "\"" + scheme + "://" + AUTHORITY + "\" + tenant + \".example/orders/\" + id + \"" + QUERY + "\"",
                    List.of());
            Graph graph = link(url);

            assertThat(graph.edges()).singleElement().satisfies(e -> {
                assertThat(e.to()).isEqualTo(Linker.UNRESOLVED_TARGET);
                assertThat(e.attrs().get("path")).contains("/orders/{?}");
                assertThat(e.attrs().get("raw")).contains(" + tenant + ", " + id + ", "[redacted]");
                assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED);
            });
            assertSafeOutputs(graph);
        }
    }

    @Test
    void relativeUrlRedactsQueryValuesInLaterConcatenationOperands() throws Exception {
        Value url = new Value("/orders/{?}" + QUERY, Confidence.UNRESOLVED,
                "\"/orders/\" + id + \"?token=\" + \"FAKE_TOKEN_CANARY\" + \"#FAKE_FRAGMENT_CANARY\"", List.of());
        Graph graph = link(url);

        assertThat(graph.edges()).singleElement().satisfies(e -> {
            assertThat(e.to()).isEqualTo(Linker.UNRESOLVED_TARGET);
            assertThat(e.attrs()).containsEntry("path", "/orders/{?}");
            assertThat(e.attrs().get("raw")).contains(" + id + ", "[redacted]");
        });
        assertSafeOutputs(graph);
    }

    @Test
    void fullyUnknownUrlRedactsQueryOnlyRawExpression() throws Exception {
        Graph graph = link(Value.unresolved("baseUrl + \"?token=\" + \"FAKE_TOKEN_CANARY\" + \"#FAKE_FRAGMENT_CANARY\""));

        assertThat(graph.edges()).singleElement().satisfies(e -> {
            assertThat(e.to()).isEqualTo(Linker.UNRESOLVED_TARGET);
            assertThat(e.attrs()).containsEntry("path", Value.UNKNOWN);
            assertThat(e.attrs().get("raw")).contains("baseUrl + ", "[redacted]");
            assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED);
            assertThat(e.evidence()).containsExactly(CALL);
        });
        assertSafeOutputs(graph);
    }

    private static Graph link(Value url) {
        var service = new Linker.ServiceFacts("caller", "repo", ".",
                List.of(new Fact.Call("rest-template", "GET", url, CALL)));
        return Linker.link(List.of(service), Map.of(), List.of());
    }

    private static void assertSafeOutputs(Graph graph) throws Exception {
        for (String output : List.of(new ObjectMapper().writeValueAsString(graph),
                MarkdownOutput.servicePage(graph, "caller"))) {
            assertThat(output).doesNotContain("demo-user", "FAKE_PASSWORD_CANARY", "FAKE_TOKEN_CANARY", "FAKE_FRAGMENT_CANARY");
        }
    }
}
