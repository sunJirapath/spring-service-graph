package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.scan.Discovery;
import io.github.sunjirapath.ssg.scan.JavaIndex;
import io.github.sunjirapath.ssg.scan.Repo;
import io.github.sunjirapath.ssg.scan.Service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayJavaDslTest {

    @Test
    void extractsEndpointsAndCallsFromJavaRouteDsl(@TempDir Path repo) throws IOException {
        var service = service(repo, routes());
        var facts = new GatewayExtractor().extract(service);

        assertThat(facts).filteredOn(Fact.Endpoint.class::isInstance)
                .extracting(f -> ((Fact.Endpoint) f).method() + " " + ((Fact.Endpoint) f).path().text())
                .containsExactlyInAnyOrder("* /acc-api/**", "* /v1/orders/**");
        assertThat(facts).filteredOn(Fact.Call.class::isInstance)
                .extracting(f -> ((Fact.Call) f).client() + " " + ((Fact.Call) f).url().text())
                .containsExactlyInAnyOrder("gateway lb://account/**", "gateway http://orders:8080/orders/**");
    }

    @Test
    void outboundHttpExtractorSkipsGatewayDsl(@TempDir Path repo) throws IOException {
        var service = service(repo, """
                package x;
                @Configuration class Routes {
                    @Bean RouteLocator routes(RouteLocatorBuilder b) {
                        return b.routes().route(r -> r.path("/x/**").and().method("GET").uri("lb://x")).build();
                    }
                }""");

        assertThat(new OutboundHttpExtractor().extract(service)).noneMatch(Fact.Call.class::isInstance);
    }

    @ParameterizedTest
    @ValueSource(strings = {"count", "-1"})
    void invalidStripPrefixKeepsJavaRouteAsUnresolved(String count, @TempDir Path repo) throws IOException {
        var service = service(repo, """
                package x;
                @Configuration class Routes {
                    @Bean RouteLocator routes(RouteLocatorBuilder b, int count) {
                        return b.routes().route(r -> r.path("/api/orders/**")
                            .filters(f -> f.stripPrefix(%s)).uri("lb://orders")).build();
                    }
                }""".formatted(count));

        var facts = new GatewayExtractor().extract(service);

        assertThat(facts).filteredOn(Fact.Endpoint.class::isInstance).singleElement()
                .isInstanceOfSatisfying(Fact.Endpoint.class,
                        e -> assertThat(e.path().text()).isEqualTo("/api/orders/**"));
        assertThat(facts).filteredOn(Fact.Call.class::isInstance).singleElement()
                .isInstanceOfSatisfying(Fact.Call.class, c -> {
                    assertThat(c.url().text()).isEqualTo("lb://orders/{?}");
                    assertThat(c.url().confidence()).isEqualTo(Confidence.UNRESOLVED);
                    assertThat(c.url().raw()).contains("StripPrefix=");
                });
    }

    @Test
    void endpointEvidencePointsAtPathCall(@TempDir Path repo) throws IOException {
        var service = service(repo, routes());

        assertThat(new GatewayExtractor().extract(service)).filteredOn(Fact.Endpoint.class::isInstance)
                .filteredOn(f -> ((Fact.Endpoint) f).path().text().equals("/acc-api/**"))
                .singleElement().isInstanceOfSatisfying(Fact.Endpoint.class,
                        e -> assertThat(e.evidence().line()).isEqualTo(4));
    }

    @Test
    void appliesMethodPredicatesAndReportsUnresolvedUri(@TempDir Path repo) throws IOException {
        var facts = new GatewayExtractor().extract(service(repo, """
                package x;
                @Configuration class Routes {
                    @Bean RouteLocator routes(RouteLocatorBuilder b, Urls u) {
                        return b.routes()
                            .route("a", r -> r.path("/a/**").and().method("post").uri("lb://a"))
                            .route("b", r -> r.method(HttpMethod.PUT).and().path("/b/**").uri("lb://b"))
                            .route("c", r -> r.path("/c/**").uri(u.getC())).build();
                    }
                }"""));

        assertThat(facts).filteredOn(Fact.Call.class::isInstance)
                .extracting(f -> ((Fact.Call) f).method() + " " + ((Fact.Call) f).url().text())
                .containsExactlyInAnyOrder("POST lb://a/a/**", "PUT lb://b/b/**");
        assertThat(facts).filteredOn(Fact.Problem.class::isInstance).singleElement()
                .isInstanceOfSatisfying(Fact.Problem.class, p -> {
                    assertThat(p.kind()).isEqualTo("UNRESOLVED");
                    assertThat(p.evidence().line()).isEqualTo(7);
                });
    }

    private static Service service(Path repo, String routes) throws IOException {
        Path src = repo.resolve("src/main/java/x");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "package x; @SpringBootApplication class App {}");
        Files.writeString(src.resolve("Routes.java"), routes);
        return Discovery.discover(JavaIndex.build(Repo.of(repo)), "default", null).getFirst();
    }

    private static String routes() {
        return """
                package x;
                @Configuration class Routes {
                    @Bean RouteLocator routes(RouteLocatorBuilder b) {
                        return b.routes().route("acc", r -> r.path("/acc-api/**").filters(f -> f.stripPrefix(1)).uri("lb://account")).route("rw", r -> r.path("/v1/orders/**").filters(f -> f.rewritePath("/v1/(?<s>.*)", "/${s}")).uri("http://orders:8080")).build();
                    }
                }""";
    }
}
