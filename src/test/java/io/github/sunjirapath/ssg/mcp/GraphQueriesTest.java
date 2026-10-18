package io.github.sunjirapath.ssg.mcp;

import io.github.sunjirapath.ssg.TestFleet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphQueriesTest {

    private final GraphQueries queries = new GraphQueries(TestFleet.graph());

    @Test
    void resolvesSupportedTargetForms() {
        assertThat(queries.resolve("account")).containsExactly("service:account");
        assertThat(queries.resolve("account.created")).containsExactly("topic:account.created");
        assertThat(queries.resolve("POST /api/accounts")).containsExactly("endpoint:account:POST /api/accounts");
        assertThat(queries.resolve("account POST /api/accounts")).containsExactly("endpoint:account:POST /api/accounts");
        assertThat(queries.resolve("accounts")).containsExactly("table:account/accounts");
        assertThatThrownBy(() -> queries.resolve("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void whoCallsTopicIncludesMessagingAndReachEvidence() {
        var result = queries.whoCalls("account.created");
        var incoming = (List<Map<String, Object>>) result.get("incoming");

        assertThat(incoming).extracting(e -> e.get("kind")).contains("PRODUCES", "CONSUMES", "REACHES");
        assertThat(incoming).allSatisfy(e -> assertThat((List<String>) e.get("evidence")).isNotEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void whoCallsServiceIncludesGatewayCallsToItsEndpoints() {
        var incoming = (List<Map<String, Object>>) queries.whoCalls("account").get("incoming");

        assertThat(incoming).anySatisfy(e -> {
            assertThat(e.get("kind")).isEqualTo("CALLS");
            assertThat(e.get("from")).isEqualTo("service:gateway");
            assertThat((String) e.get("to")).startsWith("endpoint:account:");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void impactOfTopicWalksBackThroughEndpointToGateway() {
        var result = queries.impactOf("account.created", 3);
        var affected = (List<Map<String, Object>>) result.get("affected");

        assertThat((Iterable<String>) result.get("affectedServices")).contains("account", "gateway", "notification");
        assertThat(affected).anySatisfy(e -> {
            assertThat(e.get("node")).isEqualTo("service:gateway");
            assertThat(e.get("depth")).isEqualTo(2);
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void shallowImpactDoesNotReachGateway() {
        var result = queries.impactOf("account.created", 1);

        assertThat((Iterable<String>) result.get("affectedServices")).doesNotContain("gateway");
    }

    @Test
    void serviceCardUsesPlainEvidenceRatherThanMarkdownLinks() {
        var card = queries.serviceCard("account");

        assertThat(card).contains("# account", "account.created").doesNotContain("](");
    }
}
