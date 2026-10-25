package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Finding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class GatewayEdgeCasesTest {

    @Test
    void recognizesAllGatewayConfigurationKeyGenerations(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("application.yml", """
                        spring:
                          cloud:
                            gateway:
                              routes:
                                - uri: lb://a
                                  predicates: [Path=/a/**]
                              server:
                                webflux:
                                  routes:
                                    - uri: lb://b
                                      predicates: [Path=/b/**]
                                webmvc:
                                  routes:
                                    - uri: lb://c
                                      predicates: [Path=/c/**]
                              mvc:
                                routes:
                                  - uri: lb://d
                                    predicates: [Path=/d/**]
                        """).facts(new GatewayExtractor());

        assertThat(endpoints(facts)).containsExactlyInAnyOrder("* /a/**", "* /b/**", "* /c/**", "* /d/**");
    }

    @Test
    void propertiesFormatRouteIsExtracted(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("application.properties", """
                        spring.cloud.gateway.routes[0].uri=lb://orders
                        spring.cloud.gateway.routes[0].predicates[0]=Path=/api/orders/**
                        """).facts(new GatewayExtractor());

        assertThat(endpoints(facts)).containsExactly("* /api/orders/**");
        assertThat(calls(facts)).containsExactly("* lb://orders/api/orders/**");
    }

    @Test
    void shortcutPathPredicateCreatesOneEndpointAndCallPerPattern(@TempDir Path tmp) {
        var facts = yamlRoute(tmp, "predicates: ['Path=/a/**,/b/**']\n", "").facts(new GatewayExtractor());

        assertThat(endpoints(facts)).containsExactlyInAnyOrder("* /a/**", "* /b/**");
        assertThat(calls(facts)).containsExactlyInAnyOrder("* lb://svc/a/**", "* lb://svc/b/**");
    }

    @Test
    void longFormPathAcceptsPatternAndPatternsArguments(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("application.yml", """
                        spring.cloud.gateway.routes:
                          - uri: lb://svc
                            predicates:
                              - name: Path
                                args:
                                  pattern: /one/**
                                  patterns: /two/**
                        """).facts(new GatewayExtractor());

        assertThat(endpoints(facts)).containsExactlyInAnyOrder("* /one/**", "* /two/**");
    }

    @Test
    void methodPredicateExpandsEveryPathForEveryVerb(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("application.properties", """
                        spring.cloud.gateway.routes[0].uri=lb://svc
                        spring.cloud.gateway.routes[0].predicates[0]=Path=/x/**
                        spring.cloud.gateway.routes[0].predicates[1]=Method=GET,POST
                        """).facts(new GatewayExtractor());

        assertThat(endpoints(facts)).containsExactlyInAnyOrder("GET /x/**", "POST /x/**");
        assertThat(calls(facts)).containsExactlyInAnyOrder("GET lb://svc/x/**", "POST lb://svc/x/**");
    }

    @Test
    void hostPredicateDoesNotCreateAnEndpoint(@TempDir Path tmp) {
        var facts = yamlRoute(tmp, "predicates: [Host=example.test]\n", "").facts(new GatewayExtractor());

        assertThat(facts).isEmpty();
    }

    @Test
    void stripPrefixDefaultsToOneAndAcceptsExplicitCount(@TempDir Path tmp) {
        assertThat(GatewayExtractor.rewrite("/api/orders/**", List.of(def("StripPrefix"))).text()).isEqualTo("/orders/**");
        assertThat(GatewayExtractor.rewrite("/api/v1/orders/**", List.of(def("StripPrefix", "2"))).text()).isEqualTo("/orders/**");
        assertThat(GatewayExtractor.rewrite("/api/orders/**", List.of(def("StripPrefix", "0"))).text()).isEqualTo("/api/orders/**");
    }

    @ParameterizedTest
    @ValueSource(strings = {"${STRIP_PREFIX}", "many", "-1", "2147483648"})
    void invalidStripPrefixKeepsTargetsAndReportsUnresolvedPaths(String parts, @TempDir Path tmp) {
        var repo = repo(tmp, "package x; class Empty {}")
                .resource("application.properties", """
                        spring.cloud.gateway.routes[0].uri=lb://shortcut
                        spring.cloud.gateway.routes[0].predicates[0]=Path=/api/orders/**
                        spring.cloud.gateway.routes[0].filters[0]=StripPrefix=%s
                        spring.cloud.gateway.routes[1].uri=lb://long-form
                        spring.cloud.gateway.routes[1].predicates[0]=Path=/api/orders/**
                        spring.cloud.gateway.routes[1].filters[0].name=StripPrefix
                        spring.cloud.gateway.routes[1].filters[0].args.parts=%s
                        spring.cloud.gateway.routes[2].uri=lb://healthy
                        spring.cloud.gateway.routes[2].predicates[0]=Path=/api/orders/**
                        spring.cloud.gateway.routes[2].filters[0]=StripPrefix=1
                        """.formatted(parts, parts));

        var graph = TempRepo.scan(repo);

        assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.CALLS).hasSize(3)
                .filteredOn(e -> !e.to().equals("external:healthy"))
                .extracting(Edge::to).containsExactlyInAnyOrder("external:shortcut", "external:long-form");
        assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.CALLS && !e.to().equals("external:healthy"))
                .allSatisfy(e -> {
                    assertThat(e.confidence()).isEqualTo(Confidence.UNRESOLVED);
                    assertThat(e.attrs()).containsEntry("path", "/{?}");
                    assertThat(e.attrs().get("raw")).contains("StripPrefix=");
                });
        assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.CALLS && e.to().equals("external:healthy"))
                .singleElement().satisfies(e -> {
                    assertThat(e.confidence()).isEqualTo(Confidence.EXACT);
                    assertThat(e.attrs()).containsEntry("path", "/orders/**");
                });
        assertThat(graph.findings()).filteredOn(f -> f.kind() == Finding.Kind.UNRESOLVED).hasSize(2)
                .allSatisfy(f -> assertThat(f.message()).contains("StripPrefix="));
    }

    @Test
    void pathFiltersApplyInDeclaredOrder(@TempDir Path tmp) {
        var filters = List.of(def("StripPrefix", "1"), def("PrefixPath", "/internal"), def("SetPath", "/fixed"),
                def("PrefixPath", "/v2"));

        assertThat(GatewayExtractor.rewrite("/api/orders/**", filters).text()).isEqualTo("/v2/fixed");
    }

    @Test
    void rewritePathSupportsEscapedSpringGroupSyntax(@TempDir Path tmp) {
        var rewritten = GatewayExtractor.rewrite("/red/blue/**",
                List.of(def("RewritePath", "/red/(?<segment>.*)", "/$\\{segment}")));

        assertThat(rewritten.text()).isEqualTo("/blue/**");
    }

    @Test
    void invalidRewriteRegexKeepsCallAsUnresolved(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("application.yml", """
                        spring.cloud.gateway.routes:
                          - uri: lb://svc
                            predicates: [Path=/x/**]
                            filters:
                              - name: RewritePath
                                args:
                                  regexp: '[broken'
                                  replacement: /x
                        """).facts(new GatewayExtractor());

        assertThat(facts).filteredOn(Fact.Call.class::isInstance).singleElement()
                .isInstanceOfSatisfying(Fact.Call.class, c -> {
                    assertThat(c.url().confidence()).isEqualTo(Confidence.UNRESOLVED);
                    assertThat(c.url().text()).contains("{?}");
                });
    }

    @Test
    void longFormFiltersAndUnknownFilterAreHandled(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("application.yml", """
                        spring.cloud.gateway.routes:
                          - uri: lb://svc
                            predicates: [Path=/api/x/**]
                            filters:
                              - name: StripPrefix
                                args: { parts: 1 }
                              - name: PrefixPath
                                args: { prefix: /v1 }
                              - name: CircuitBreaker
                                args: { name: cb }
                        """).facts(new GatewayExtractor());

        assertThat(calls(facts)).containsExactly("* lb://svc/v1/x/**");
    }

    @Test
    void lbAndHttpUrisCreateCalls(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("application.yml", """
                        spring.cloud.gateway.routes:
                          - uri: lb://orders
                            predicates: [Path=/orders/**]
                          - uri: http://api.example.test:8080
                            predicates: [Path=/external/**]
                        """).facts(new GatewayExtractor());

        assertThat(calls(facts)).containsExactlyInAnyOrder("* lb://orders/orders/**", "* http://api.example.test:8080/external/**");
    }

    @Test
    void localForwardAndNoOpUrisExposeWithoutCalling(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("application.yml", """
                        spring.cloud.gateway.routes:
                          - uri: forward:/local
                            predicates: [Path=/forward/**]
                          - uri: no://op
                            predicates: [Path=/disabled/**]
                        """).facts(new GatewayExtractor());

        assertThat(endpoints(facts)).containsExactlyInAnyOrder("* /forward/**", "* /disabled/**");
        assertThat(facts).noneMatch(Fact.Call.class::isInstance);
    }

    @Test
    void routeUriResolvesPlaceholder(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("application.properties", """
                        target=orders
                        spring.cloud.gateway.routes[0].uri=lb://${target}
                        spring.cloud.gateway.routes[0].predicates[0]=Path=/orders/**
                        """).facts(new GatewayExtractor());

        assertThat(facts).filteredOn(Fact.Call.class::isInstance).singleElement()
                .isInstanceOfSatisfying(Fact.Call.class, c -> {
                    assertThat(c.url().text()).isEqualTo("lb://orders/orders/**");
                    assertThat(c.url().confidence()).isEqualTo(Confidence.RESOLVED);
                });
    }

    @Test
    void routeWithoutUriIsIgnored(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("application.yml", "spring.cloud.gateway.routes:\n  - predicates: [Path=/orphan/**]\n")
                .facts(new GatewayExtractor());

        assertThat(facts).isEmpty();
    }

    @Test
    void defaultFiltersDoNotCrashOrBecomeRoutes(@TempDir Path tmp) {
        var service = repo(tmp, "package x; class Empty {}")
                .resource("application.yml", "spring.cloud.gateway.default-filters: [StripPrefix=1]\n").service();

        assertThatCode(() -> new GatewayExtractor().extract(service)).doesNotThrowAnyException();
        assertThat(new GatewayExtractor().extract(service)).isEmpty();
    }

    @Test
    void javaDslAppliesAllSupportedPathFilters(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class Routes {
                    RouteLocator routes(RouteLocatorBuilder b) {
                        return b.routes().route(r -> r.path("/api/a/**")
                            .filters(f -> f.stripPrefix(1).prefixPath("/v1").setPath("/fixed").rewritePath("/fixed", "/done"))
                            .uri("lb://svc")).build();
                    }
                }
                """).facts(new GatewayExtractor());

        assertThat(calls(facts)).containsExactly("* lb://svc/done");
    }

    @Test
    void javaDslResolvesUriConstant(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class Routes { static final String URI = "lb://orders";
                    RouteLocator routes(RouteLocatorBuilder b) { return b.routes().route(r -> r.path("/x/**").uri(URI)).build(); }
                }
                """).facts(new GatewayExtractor());

        assertThat(facts).filteredOn(Fact.Call.class::isInstance).singleElement()
                .isInstanceOfSatisfying(Fact.Call.class, c -> {
                    assertThat(c.url().text()).isEqualTo("lb://orders/x/**");
                    assertThat(c.url().confidence()).isEqualTo(Confidence.RESOLVED);
                });
    }

    private static TempRepo repo(Path tmp, String source) {
        return TempRepo.at(tmp.resolve("repo")).app().java("x/Gateway.java", source);
    }

    private static TempRepo yamlRoute(Path tmp, String predicates, String filters) {
        return repo(tmp, "package x; class Empty {}").resource("application.yml", """
                spring.cloud.gateway.routes:
                  - uri: lb://svc
                    %s    %s""".formatted(predicates, filters));
    }

    private static GatewayExtractor.Def def(String name, String... args) {
        return new GatewayExtractor.Def(name, List.of(args), null);
    }

    private static List<String> endpoints(List<Fact> facts) {
        return facts.stream().filter(Fact.Endpoint.class::isInstance).map(Fact.Endpoint.class::cast)
                .map(e -> e.method() + " " + e.path().text()).toList();
    }

    private static List<String> calls(List<Fact> facts) {
        return facts.stream().filter(Fact.Call.class::isInstance).map(Fact.Call.class::cast)
                .map(c -> c.method() + " " + c.url().text()).toList();
    }
}
