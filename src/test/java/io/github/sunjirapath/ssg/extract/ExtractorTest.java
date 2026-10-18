package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TestFleet;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.scan.Discovery;
import io.github.sunjirapath.ssg.scan.JavaIndex;
import io.github.sunjirapath.ssg.scan.Repo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Each extractor runs alone on one service and sees only its own concern. */
class ExtractorTest {

    @Test
    void inboundHttp() {
        var facts = new InboundHttpExtractor().extract(TestFleet.service("account", "account"));
        assertThat(facts).allMatch(Fact.Endpoint.class::isInstance)
                .extracting(f -> ((Fact.Endpoint) f).method() + " " + ((Fact.Endpoint) f).path().text())
                .containsExactlyInAnyOrder("GET /api/accounts/{id}", "POST /api/accounts");
    }

    @Test
    void inboundHttpFallsBackToOpenApiSpec() {
        var facts = new InboundHttpExtractor().extract(TestFleet.service("account", "account-admin"));
        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.Endpoint.class,
                e -> assertThat(e.source()).isEqualTo("openapi"));
    }

    @Test
    void inboundHttpReportsMissingInterfaceWithoutSpec(@TempDir Path repo) throws IOException {
        Path src = repo.resolve("src/main/java/x");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "package x; @SpringBootApplication class App {}");
        Files.writeString(src.resolve("C.java"), "package x; @RestController class C implements GeneratedApi {}");
        var service = Discovery.discover(JavaIndex.build(Repo.of(repo)), "default", null).getFirst();
        assertThat(new InboundHttpExtractor().extract(service)).singleElement()
                .isInstanceOfSatisfying(Fact.Problem.class, p -> assertThat(p.kind()).isEqualTo("MISSING_INTERFACE"));
    }

    @Test
    void overloadedHandlerMethodsAreAllExposed(@TempDir Path repo) throws IOException {
        Path src = repo.resolve("src/main/java/x");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "package x; @SpringBootApplication class App {}");
        Files.writeString(src.resolve("C.java"), """
                package x;
                @RestController class C {
                    @GetMapping("pets/{id}/visits") Object read(@PathVariable int id) { return null; }
                    @GetMapping("pets/visits") Object read(@RequestParam java.util.List<Integer> ids) { return null; }
                }""");
        var service = Discovery.discover(JavaIndex.build(Repo.of(repo)), "default", null).getFirst();
        assertThat(new InboundHttpExtractor().extract(service))
                .extracting(f -> ((Fact.Endpoint) f).path().text())
                .containsExactlyInAnyOrder("/pets/{id}/visits", "/pets/visits");
    }

    @Test
    void outboundHttp() {
        var facts = new OutboundHttpExtractor().extract(TestFleet.service("gateway", "gateway"));
        assertThat(facts).allMatch(Fact.Call.class::isInstance)
                .extracting(f -> ((Fact.Call) f).client() + " " + ((Fact.Call) f).method() + " " + ((Fact.Call) f).url().text())
                .containsExactlyInAnyOrder(
                        "feign GET lb://account/api/accounts/{id}",
                        "feign POST lb://account/api/accounts/{id}/close",
                        "http-exchange POST http://notification:8080/notifications",
                        "rest-template GET {?}/reports/{?}");
    }

    @Test
    void kafka() {
        var facts = new KafkaExtractor().extract(TestFleet.service("notification", "notification"));
        assertThat(facts).allMatch(Fact.TopicUse.class::isInstance)
                .extracting(f -> ((Fact.TopicUse) f).topic().text()).containsExactlyInAnyOrder("account.created", "audit.events");
    }

    @Test
    void data() {
        var facts = new DataExtractor().extract(TestFleet.service("account", "account"));
        assertThat(facts).allMatch(Fact.TableUse.class::isInstance)
                .extracting(f -> (((Fact.TableUse) f).owns() ? "owns " : "uses ") + ((Fact.TableUse) f).table().text())
                .containsExactlyInAnyOrder("owns accounts", "uses accounts", "uses accounts");
    }

    @Test
    void gateway() {
        var facts = new GatewayExtractor().extract(TestFleet.service("gateway", "gateway"));
        assertThat(facts).hasSize(2);
        assertThat(facts).filteredOn(Fact.Call.class::isInstance)
                .extracting(f -> ((Fact.Call) f).url().text()).containsExactly("lb://account/**");
    }

    @Test
    void gatewayFiltersRewriteThePath() {
        assertThat(GatewayExtractor.rewrite("/api/customer/**", List.of(def("StripPrefix", "2"))).text()).isEqualTo("/**");
        assertThat(GatewayExtractor.rewrite("/api/x/**", List.of(def("RewritePath", "/api/(?<seg>.*)", "/$\\{seg}"))).text())
                .isEqualTo("/x/**");
        assertThat(GatewayExtractor.rewrite("/x/**", List.of(def("PrefixPath", "/v1"))).text()).isEqualTo("/v1/x/**");
    }

    private static GatewayExtractor.Def def(String name, String... args) {
        return new GatewayExtractor.Def(name, List.of(args), null);
    }
}
