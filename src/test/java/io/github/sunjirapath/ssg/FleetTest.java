package io.github.sunjirapath.ssg;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The behaviours the synthetic fleet exists to pin down, one assertion group per pitfall. */
class FleetTest {

    private final Graph g = TestFleet.graph();

    private List<Edge> edges(Edge.Kind kind, String from, String to) {
        return g.edges().stream().filter(e -> e.kind() == kind && e.from().equals(from) && e.to().equals(to)).toList();
    }

    @Test
    void multiModuleRepoYieldsOneServicePerApplicationAndTestAppsAreIgnored() {
        assertThat(g.nodes()).filteredOn(Node.Service.class::isInstance).extracting(Node::id)
                .containsExactly("service:account", "service:account-admin", "service:gateway", "service:notification");
    }

    @Test
    void controllerInheritsMappingsFromGeneratedInterfaceAndClassPathGetsLeadingSlash() {
        assertThat(edges(Edge.Kind.EXPOSES, "service:account", "endpoint:account:GET /api/accounts/{id}"))
                .singleElement().satisfies(e -> assertThat(e.evidence().getFirst().file()).endsWith("AccountsApi.java"));
        assertThat(edges(Edge.Kind.EXPOSES, "service:account", "endpoint:account:POST /api/accounts")).hasSize(1);
    }

    @Test
    void missingGeneratedInterfaceFallsBackToOpenApiSpec() {
        assertThat(edges(Edge.Kind.EXPOSES, "service:account-admin", "endpoint:account-admin:DELETE /admin/accounts/{id}"))
                .singleElement().satisfies(e -> {
                    assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
                    assertThat(e.evidence().getFirst().file()).endsWith("openapi/admin-api.yaml");
                });
        assertThat(g.findings()).noneMatch(f -> f.kind() == Finding.Kind.MISSING_INTERFACE);
    }

    @Test
    void feignNameLinksToTargetServiceEndpoint() {
        assertThat(edges(Edge.Kind.CALLS, "service:gateway", "endpoint:account:GET /api/accounts/{id}"))
                .anySatisfy(e -> {
                    assertThat(e.attrs()).containsEntry("client", "feign");
                    assertThat(e.confidence()).isEqualTo(Confidence.EXACT);
                });
    }

    @Test
    void httpExchangeUrlFromPropertiesLinksToNotification() {
        assertThat(edges(Edge.Kind.CALLS, "service:gateway", "endpoint:notification:POST /notifications"))
                .singleElement().satisfies(e -> {
                    assertThat(e.attrs()).containsEntry("client", "http-exchange");
                    assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
                    assertThat(e.evidence()).anyMatch(ev -> ev.file().endsWith("application.yml"));
                });
    }

    @Test
    void gatewayRouteStripsPrefixAndReachesAccountEndpoints() {
        assertThat(edges(Edge.Kind.EXPOSES, "service:gateway", "endpoint:gateway:* /account-api/**")).hasSize(1);
        assertThat(g.edges()).filteredOn(e -> e.kind() == Edge.Kind.CALLS && "gateway".equals(e.attrs().get("client")))
                .extracting(Edge::to)
                .containsExactlyInAnyOrder("endpoint:account:GET /api/accounts/{id}", "endpoint:account:POST /api/accounts");
    }

    @Test
    void runtimeBuiltUrlIsKeptAsUnresolved() {
        assertThat(edges(Edge.Kind.CALLS, "service:gateway", "external:unresolved")).singleElement().satisfies(e -> {
            assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED);
            assertThat(e.attrs()).containsEntry("client", "rest-template").containsEntry("path", "{?}/reports/{?}");
        });
        assertThat(g.findings()).anyMatch(f -> f.kind() == Finding.Kind.UNRESOLVED && f.message().contains("rest-template"));
    }

    @Test
    void callToMissingEndpointIsContractDrift() {
        assertThat(g.findings()).filteredOn(f -> f.kind() == Finding.Kind.CONTRACT_DRIFT).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("POST /api/accounts/{id}/close").contains("account"));
    }

    @Test
    void topicFromConstantInAnotherModuleLinksProducerToConsumer() {
        assertThat(edges(Edge.Kind.PRODUCES, "service:account", "topic:kafka:account.created")).singleElement().satisfies(e -> {
            assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(e.evidence().getFirst().file()).endsWith("AccountEvents.java");
            assertThat(e.evidence()).anyMatch(ev -> ev.file().endsWith("account-api/src/main/java/demo/account/api/Topics.java"));
        });
        // ${topics.account-created:account.created} resolves through its default
        assertThat(edges(Edge.Kind.CONSUMES, "service:notification", "topic:kafka:account.created")).singleElement()
                .satisfies(e -> assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED));
    }

    @Test
    void cloudStreamBindingIsConsumed() {
        assertThat(edges(Edge.Kind.CONSUMES, "service:notification", "topic:unknown:audit.events")).singleElement()
                .satisfies(e -> assertThat(e.attrs()).containsEntry("via", "cloud-stream:audit-in-0"));
    }

    @Test
    void liquibaseChangesetOwnsTableAndRepositoryWithDerivedQueryUsesIt() {
        assertThat(edges(Edge.Kind.OWNS, "service:account", "table:sql:account/accounts")).singleElement()
                .satisfies(e -> assertThat(e.evidence().getFirst().file()).endsWith("db.changelog-master.yaml"));
        assertThat(edges(Edge.Kind.USES, "service:account", "table:sql:account/accounts")).singleElement()
                .satisfies(e -> assertThat(e.evidence()).extracting(ev -> ev.file().replaceAll(".*/", ""))
                        .containsExactly("AccountEntity.java", "AccountRepository.java"));
    }

    @Test
    void secretValuesNeverReachTheOutput() throws Exception {
        String json = new ObjectMapper().writeValueAsString(g);
        assertThat(json).doesNotContain("s3cr3t");
    }
}
