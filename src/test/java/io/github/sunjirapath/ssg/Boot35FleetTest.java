package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic Java-only sources declare Boot 3.5.16; no framework jars or runtime are loaded. */
class Boot35FleetTest {
    private static final Path ROOT = Path.of("src/test/resources/boot3.5-fleet");
    private static final Graph GRAPH = scan();

    private static Graph scan() {
        try {
            return Pipeline.scan(new Pipeline.Options(List.of(ROOT.resolve("api"), ROOT.resolve("clients"),
                    ROOT.resolve("gateway-legacy"), ROOT.resolve("gateway-modern")), "prod", null, null)).graph();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void declaresFourBoot3516JavaServicesAndJakartaControllerEndpoint() {
        assertThat(GRAPH.nodes()).filteredOn(Node.Service.class::isInstance).extracting(Node::id)
                .containsExactly("service:boot3-api", "service:boot3-clients", "service:boot3-legacy-gateway", "service:boot3-modern-gateway");
        assertThat(GRAPH.edges()).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo(Edge.Kind.EXPOSES);
            assertThat(e.from()).isEqualTo("service:boot3-api");
            assertThat(e.to()).isEqualTo("endpoint:boot3-api:GET /items");
            assertThat(e.evidence()).anyMatch(ev -> ev.file().endsWith("ItemsController.java"));
        });
        assertThat(GRAPH.edges()).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo(Edge.Kind.EXPOSES);
            assertThat(e.from()).isEqualTo("service:boot3-api");
            assertThat(e.to()).isEqualTo("endpoint:boot3-api:GET /jaxrs/health");
        });
    }

    @Test
    void bothGatewayPropertyGenerationsLinkWithEvidenceAndConfidence() {
        gateway("boot3-legacy-gateway", "/legacy/items", "gateway-legacy", "src/main/resources/application.properties");
        gateway("boot3-modern-gateway", "/modern/items", "gateway-modern", "src/main/resources/application.yml");
    }

    private static void gateway(String service, String inboundPath, String repo, String config) {
        assertThat(GRAPH.edges()).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo(Edge.Kind.EXPOSES);
            assertThat(e.from()).isEqualTo("service:" + service);
            assertThat(e.to()).isEqualTo("endpoint:" + service + ":* " + inboundPath);
            assertThat(e.confidence()).isEqualTo(Confidence.EXACT);
            assertThat(e.evidence()).anyMatch(ev -> ev.repo().equals(repo) && ev.file().equals(config));
        });
        assertThat(GRAPH.edges()).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo(Edge.Kind.CALLS);
            assertThat(e.from()).isEqualTo("service:" + service);
            assertThat(e.to()).isEqualTo("endpoint:boot3-api:GET /items");
            assertThat(e.confidence()).isNotEqualTo(Confidence.UNRESOLVED);
            assertThat(e.evidence()).anyMatch(ev -> ev.repo().equals(repo) && ev.file().equals(config));
        });
    }

    @Test
    void allSupportedHttpClientFormsLinkToJakartaController() {
        assertCall("feign");
        assertCall("http-exchange");
        assertCall("restclient");
        assertCall("webclient");
        assertCall("rest-template");
    }

    private static void assertCall(String client) {
        assertThat(GRAPH.edges()).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo(Edge.Kind.CALLS);
            assertThat(e.from()).isEqualTo("service:boot3-clients");
            assertThat(e.to()).isEqualTo("endpoint:boot3-api:GET /items");
            assertThat(e.attrs()).containsEntry("client", client);
            assertThat(e.confidence()).isNotEqualTo(Confidence.UNRESOLVED);
            assertThat(e.evidence()).anyMatch(ev -> ev.repo().equals("clients") && ev.file().startsWith("src/main/java/"));
        });
    }
}
