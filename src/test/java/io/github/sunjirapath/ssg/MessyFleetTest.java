package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import io.github.sunjirapath.ssg.output.DiagramOutput;
import io.github.sunjirapath.ssg.output.JsonOutput;
import io.github.sunjirapath.ssg.output.MarkdownOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MessyFleetTest {

    private static final Path ROOT = Path.of("src/test/resources/messy");
    private static final List<String> REPOS = List.of(
            "legacy-monolith", "modern-svc", "broken-svc", "gateway-edge", "events-svc");

    private static Pipeline.Result cached;

    private final Graph graph = scan().graph();

    @Test
    void discoversEveryMainApplicationAndIgnoresTestAndNodeModulesApps() {
        assertThat(graph.nodes()).filteredOn(Node.Service.class::isInstance).extracting(Node::id)
                .containsExactly("service:broken-svc", "service:events-svc", "service:gateway-edge",
                        "service:legacy-admin", "service:legacy-api", "service:modern-svc");
        assertThat(endpointIds()).noneMatch(id -> id.contains("must-not-exist") || id.contains("test-only")
                || id.contains("node-only") || id.contains("vendor"));
    }

    @Test
    void deeplyNestedApplicationKeepsItsRepoRelativeModulePath() {
        assertThat(graph.nodes()).filteredOn(Node.Service.class::isInstance)
                .filteredOn(node -> node.id().equals("service:modern-svc"))
                .singleElement().isInstanceOfSatisfying(Node.Service.class,
                        service -> assertThat(service.path()).isEqualTo("a/b/c/service"));
    }

    @Test
    void legacyFeignClientThroughSharedInterfaceLinksToModernEndpoint() {
        assertThat(edges(Edge.Kind.CALLS, "service:legacy-api", "endpoint:modern-svc:GET /modern/items/{id}"))
                .filteredOn(e -> e.attrs().get("client").equals("feign"))
                .singleElement().satisfies(e -> {
                    assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
                    assertThat(e.evidence()).extracting(ev -> ev.file())
                            .anyMatch(file -> file.endsWith("SharedModernApi.java"))
                            .anyMatch(file -> file.endsWith("ModernPaths.java"))
                            .anyMatch(file -> file.endsWith("ServiceNames.java"));
                });
    }

    @Test
    void modernKafkaProducerAndEventsConsumerShareResolvedTopic() {
        assertThat(edges(Edge.Kind.PRODUCES, "service:modern-svc", "topic:orders.changed"))
                .singleElement().satisfies(e -> assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED));
        assertThat(edges(Edge.Kind.CONSUMES, "service:events-svc", "topic:orders.changed"))
                .anySatisfy(e -> {
                    assertThat(e.attrs()).containsEntry("via", "KafkaListener");
                    assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
                });
    }

    @Test
    void propertiesAndLongFormYamlGatewayRoutesLinkAcrossRepos() {
        assertThat(edges(Edge.Kind.CALLS, "service:gateway-edge", "endpoint:modern-svc:GET /modern/items/{id}"))
                .anySatisfy(e -> {
                    assertThat(e.attrs()).containsEntry("client", "gateway");
                    assertThat(e.confidence()).isEqualTo(Confidence.EXACT);
                });
        assertThat(edges(Edge.Kind.CALLS, "service:gateway-edge", "endpoint:legacy-api:GET /legacy/orders/{id}"))
                .anySatisfy(e -> {
                    assertThat(e.attrs()).containsEntry("client", "gateway");
                    assertThat(e.confidence()).isEqualTo(Confidence.EXACT);
                });
    }

    @Test
    void externalUnknownAndForwardGatewayRoutesRemainDistinct() {
        assertThat(edges(Edge.Kind.CALLS, "service:gateway-edge", "external:api.example.test"))
                .singleElement().satisfies(e -> assertThat(e.attrs()).containsEntry("path", "/api/**"));
        assertThat(edges(Edge.Kind.CALLS, "service:gateway-edge", "external:ghost-service")).hasSize(1);
        assertThat(edges(Edge.Kind.EXPOSES, "service:gateway-edge", "endpoint:gateway-edge:* /local/**")).hasSize(1);
        assertThat(graph.edges()).noneMatch(e -> e.kind() == Edge.Kind.CALLS
                && "forward".equals(e.attrs().get("client")));
    }

    @Test
    void knownLimitNonFinalFieldInitializerStillResolvesHost() {
        assertThat(edges(Edge.Kind.CALLS, "service:gateway-edge", "endpoint:legacy-api:GET /legacy/orders/{id}"))
                .filteredOn(e -> e.attrs().get("client").equals("webclient"))
                .singleElement().satisfies(e -> assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED));
    }

    @Test
    void mismatchedFeignMethodProducesContractDrift() {
        assertThat(graph.findings()).filteredOn(f -> f.kind() == Finding.Kind.CONTRACT_DRIFT)
                .anySatisfy(f -> {
                    assertThat(f.service()).isEqualTo("legacy-api");
                    assertThat(f.message()).contains("POST /modern/items/{id}/archive").contains("modern-svc");
                });
    }

    @Test
    void runtimeBuiltWebClientUrlIsKeptAsUnresolved() {
        assertThat(edges(Edge.Kind.CALLS, "service:gateway-edge", "external:unresolved"))
                .singleElement().satisfies(e -> {
                    assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED);
                    assertThat(e.attrs()).containsEntry("client", "webclient")
                            .containsEntry("path", "{?}/runtime/{?}");
                });
        assertThat(graph.findings())
                .filteredOn(f -> f.kind() == Finding.Kind.UNRESOLVED && f.service().equals("gateway-edge"))
                .anySatisfy(f -> assertThat(f.message()).contains("webclient call GET").contains("runtimeBase(tenant)"));
    }

    @Test
    void parseAndMissingInterfaceFindingsPointToTheirSourceFiles() {
        assertThat(graph.findings()).filteredOn(f -> f.kind() == Finding.Kind.PARSE_ERROR)
                .anySatisfy(f -> assertThat(f.evidence()).anySatisfy(ev -> {
                    assertThat(ev.file()).endsWith("BadMiddle.java");
                    assertThat(ev.line()).isEqualTo(6);
                }));
        assertThat(graph.findings()).filteredOn(f -> f.kind() == Finding.Kind.MISSING_INTERFACE)
                .anySatisfy(f -> {
                    assertThat(f.service()).isEqualTo("broken-svc");
                    assertThat(f.evidence()).singleElement().satisfies(ev -> {
                        assertThat(ev.file()).endsWith("MissingGeneratedController.java");
                        assertThat(ev.line()).isEqualTo(3);
                    });
                });
    }

    @Test
    void scannerContinuesAfterBrokenFileAndReadsGeneratedSources() {
        assertThat(endpointIds()).contains("endpoint:broken-svc:GET /after-broken",
                "endpoint:broken-svc:GET /generated/{id}");
    }

    @Test
    void kotlinSourceIsIgnoredWithoutProducingGraphEvidence() {
        assertThat(endpointIds()).noneMatch(id -> id.contains("/kotlin"));
        assertThat(allEvidenceFiles()).noneMatch(file -> file.endsWith("KotlinController.kt"));
    }

    @Test
    void bomAndCrLfSourcesRetainExactEvidenceLines() {
        assertEndpointEvidence("endpoint:broken-svc:GET /bom", "BomController.java", 5);
        assertEndpointEvidence("endpoint:broken-svc:GET /crlf", "CrLfController.java", 5);
    }

    @Test
    void malformedProfileYamlDoesNotAbortFleetScan() {
        Graph withBrokenProfile = scan("broken").graph();

        assertThat(withBrokenProfile.nodes()).filteredOn(Node.Service.class::isInstance).extracting(Node::id)
                .contains("service:broken-svc", "service:modern-svc", "service:events-svc");
        assertThat(withBrokenProfile.nodes()).filteredOn(Node.Endpoint.class::isInstance).extracting(Node::id)
                .contains("endpoint:broken-svc:GET /after-broken");
    }

    @Test
    void cloudStreamHandlesCommaSeparatedDestinationsAndReportsUnknownDirection() {
        assertThat(edges(Edge.Kind.PRODUCES, "service:events-svc", "topic:audit.events")).hasSize(1);
        assertThat(edges(Edge.Kind.PRODUCES, "service:events-svc", "topic:orphan.produced")).hasSize(2);
        assertThat(edges(Edge.Kind.CONSUMES, "service:events-svc", "topic:audit.events")).hasSize(1);
        assertThat(edges(Edge.Kind.CONSUMES, "service:events-svc", "topic:orphan.consumed")).hasSize(1);
        assertThat(graph.findings())
                .filteredOn(f -> f.kind() == Finding.Kind.UNRESOLVED && f.service().equals("events-svc"))
                .anySatisfy(f -> assertThat(f.message()).contains("legacyBinding").contains("direction is unknown"));
    }

    @Test
    void sqlAndMongoDataSurviveQuotedSchemaAndMultilineChangelog() {
        assertThat(graph.nodes()).filteredOn(Node.Table.class::isInstance).extracting(Node::id)
                .contains("table:modern-svc/order_item", "table:modern-svc/event_log",
                        "table:events-svc/event_outbox", "table:events-svc/audit_events");
        assertThat(edges(Edge.Kind.OWNS, "service:events-svc", "table:events-svc/event_outbox"))
                .singleElement().satisfies(e -> assertThat(e.evidence().getFirst().line()).isEqualTo(3));
        assertThat(edges(Edge.Kind.USES, "service:events-svc", "table:events-svc/audit_events"))
                .singleElement().satisfies(e -> assertThat(e.evidence()).hasSize(2));
    }

    @Test
    void everyEvidencePathUsesForwardSlashes() {
        assertThat(allEvidenceFiles()).allMatch(file -> !file.contains("\\"));
    }

    @Test
    void allOutputsAreGeneratedWithoutExceptions(@TempDir Path out) {
        Pipeline.Result result = scan();

        assertThatCode(() -> {
            JsonOutput.write(result.graph(), out);
            MarkdownOutput.write(result.graph(), result.repoRoots(), out);
            DiagramOutput.write(result.graph(), out);
        }).doesNotThrowAnyException();
        assertThat(out.resolve("graph.json")).isRegularFile();
        assertThat(out.resolve("index.md")).isRegularFile();
        assertThat(out.resolve("diagrams/fleet.puml")).isRegularFile();
        assertThat(out.resolve("services/modern-svc.md")).isRegularFile();
    }

    @Test
    void graphJsonIsDeterministicAcrossIndependentScans(@TempDir Path out) throws IOException {
        JsonOutput.write(scanFresh("default").graph(), out.resolve("first"));
        JsonOutput.write(scanFresh("default").graph(), out.resolve("second"));

        assertThat(Files.readString(out.resolve("first/graph.json")))
                .isEqualTo(Files.readString(out.resolve("second/graph.json")));
    }

    @Test
    void fakeSecretValuesNeverAppearInJsonOrMarkdown(@TempDir Path out) throws IOException {
        Pipeline.Result result = scan();
        JsonOutput.write(result.graph(), out);
        String markdown = result.graph().nodes().stream().filter(Node.Service.class::isInstance)
                .map(Node.Service.class::cast).map(Node.Service::name)
                .map(name -> MarkdownOutput.servicePage(result.graph(), name)).reduce("", String::concat);

        assertThat(Files.readString(out.resolve("graph.json"))).doesNotContain("fake-password-123");
        assertThat(markdown).doesNotContain("fake-password-123");
    }

    private static synchronized Pipeline.Result scan() {
        if (cached == null) cached = scanFresh("default");
        return cached;
    }

    private static Pipeline.Result scan(String profile) {
        return profile.equals("default") ? scan() : scanFresh(profile);
    }

    private static Pipeline.Result scanFresh(String profile) {
        try {
            return Pipeline.scan(new Pipeline.Options(
                    REPOS.stream().map(ROOT::resolve).toList(), profile, null, null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<Edge> edges(Edge.Kind kind, String from, String to) {
        return graph.edges().stream()
                .filter(e -> e.kind() == kind && e.from().equals(from) && e.to().equals(to)).toList();
    }

    private List<String> endpointIds() {
        return graph.nodes().stream().filter(Node.Endpoint.class::isInstance).map(Node::id).toList();
    }

    private List<String> allEvidenceFiles() {
        var files = new ArrayList<String>();
        graph.edges().forEach(e -> e.evidence().forEach(ev -> files.add(ev.file())));
        graph.findings().forEach(f -> f.evidence().forEach(ev -> files.add(ev.file())));
        return files;
    }

    private void assertEndpointEvidence(String endpoint, String file, int line) {
        assertThat(edges(Edge.Kind.EXPOSES, "service:broken-svc", endpoint))
                .singleElement().satisfies(e -> assertThat(e.evidence()).singleElement().satisfies(ev -> {
                    assertThat(ev.file()).endsWith(file);
                    assertThat(ev.line()).isEqualTo(line);
                }));
    }

}
