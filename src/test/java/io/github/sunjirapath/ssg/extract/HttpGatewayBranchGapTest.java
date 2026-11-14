package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HttpGatewayBranchGapTest {

    @Test
    void combinesClassAndMethodRequestMethodsAndSkipsJaxRsLocators(@TempDir Path tmp) {
        var facts = TempRepo.at(tmp.resolve("inbound")).app().java("x/Endpoints.java", """
                package x;
                @ApplicationPath("/rest") class AppPath {}
                @Path("/items") class Resource {
                    @Path("/{id}") Object locate(String id) { return null; }
                    @GET @Path("/read") Object read() { return null; }
                }
                @RestController @RequestMapping(method = RequestMethod.POST)
                class Controller implements GeneratedApi {
                    @GetMapping("/items") void get() {}
                    @RequestMapping(path = "/any", method = {}) void any() {}
                }
                """).resource("openapi.yaml", """
                openapi: 3.0.3
                paths:
                  /items:
                    get: {}
                """).facts(new InboundHttpExtractor());

        assertThat(facts).filteredOn(Fact.Endpoint.class::isInstance)
                .extracting(f -> ((Fact.Endpoint) f).method() + " " + ((Fact.Endpoint) f).path().text())
                .containsExactlyInAnyOrder("GET /rest/items/read", "GET /items", "POST /items", "POST /any");
    }

    @Test
    void handlerTypePathPrefixFiltersByAnnotationAndPackage(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("prefixes")).app()
                .java("x/WebConfig.java", """
                        package x;
                        @Configuration class WebConfig {
                            void configure(PathMatchConfigurer config) {
                                config.addPathPrefix("/api", HandlerTypePredicate.forAnnotation(RestController.class)
                                        .and(HandlerTypePredicate.forBasePackage("x.api")));
                            }
                        }
                        """)
                .java("x/api/ApiController.java", "package x.api; @RestController class ApiController { @GetMapping(\"/a\") void a() {} }")
                .java("x/other/OtherController.java", "package x.other; @RestController class OtherController { @GetMapping(\"/b\") void b() {} }")
                .java("x/api/Plain.java", "package x.api; @Controller class Plain { @GetMapping(\"/c\") void c() {} }");

        assertThat(repo.facts(new InboundHttpExtractor())).filteredOn(Fact.Endpoint.class::isInstance)
                .extracting(f -> ((Fact.Endpoint) f).path().text()).containsExactlyInAnyOrder("/api/a", "/b", "/c");
    }

    @Test
    void extractsNestedRouterPredicatesAndLeavesUnknownNestingUnresolved(@TempDir Path tmp) {
        var facts = TempRepo.at(tmp.resolve("router")).app().java("x/Routes.java", """
                package x;
                import static org.springframework.web.reactive.function.server.RouterFunctions.nest;
                import static org.springframework.web.reactive.function.server.RouterFunctions.route;
                import static org.springframework.web.reactive.function.server.RequestPredicates.*;
                @Configuration class Routes {
                    @Bean RouterFunction<?> routes() {
                        return nest((path("/api")), route((GET("/items")), this::handle))
                                .andRoute(nest(or(path("/one"), path("/two")), route(POST("/other"), this::handle)))
                                .andRoute(nest(path("/v1").and(headers("x-test")), route(GET("/next"), this::handle)));
                    }
                    Object handle(ServerRequest request) { return null; }
                }
                """).facts(new InboundHttpExtractor());

        assertThat(facts).filteredOn(Fact.Endpoint.class::isInstance)
                .filteredOn(f -> ((Fact.Endpoint) f).method().equals("GET"))
                .extracting(f -> ((Fact.Endpoint) f).path().text())
                .containsExactlyInAnyOrder("/api/items", "/v1/next");
        assertThat(facts).filteredOn(Fact.Endpoint.class::isInstance)
                .filteredOn(f -> ((Fact.Endpoint) f).method().equals("POST")).singleElement()
                .isInstanceOfSatisfying(Fact.Endpoint.class, e -> {
                    assertThat(e.path().text()).isEqualTo("/{?}/other");
                    assertThat(e.path().confidence()).isEqualTo(Confidence.UNRESOLVED);
                });
    }

    @Test
    void restTemplateExchangeAndFluentMethodUseDeclaredVerbAndKnownClient(@TempDir Path tmp) {
        var facts = TempRepo.at(tmp.resolve("outbound")).app().java("x/Calls.java", """
                package x;
                class Calls {
                    RestTemplate restTemplate;
                    WebClient webClient;
                    Object optional;
                    void call() {
                        restTemplate.exchange("https://orders.test/items", RequestMethod.DELETE, null, Object.class);
                        webClient.method(HttpMethod.PATCH).uri(builder -> builder.build());
                        webClient.get().uri(builder -> builder.path("/items").build());
                        webClient.method(HttpMethod.GET, HttpMethod.POST).uri("/ignored");
                        optional.get().uri("https://not-a-client.test");
                    }
                }
                """).facts(new OutboundHttpExtractor());

        assertThat(facts).filteredOn(Fact.Call.class::isInstance)
                .extracting(f -> ((Fact.Call) f).client() + " " + ((Fact.Call) f).method() + " " + ((Fact.Call) f).url().text())
                .containsExactlyInAnyOrder("rest-template DELETE https://orders.test/items", "webclient PATCH {?}",
                        "webclient GET {?}/items");
    }

    @Test
    void gatewaySkipsRoutesWithoutUriAndDoesNotCallInternalTargets(@TempDir Path tmp) {
        var facts = TempRepo.at(tmp.resolve("gateway")).app().java("x/Empty.java", "package x; class Empty {}")
                .resource("application.properties", """
                        spring.cloud.gateway.routes[0].predicates[0]=Path=/missing/**
                        spring.cloud.gateway.routes[1].uri=forward:/local
                        spring.cloud.gateway.routes[1].predicates[0]=Path=/local/**
                        spring.cloud.gateway.routes[2].uri=no://op
                        spring.cloud.gateway.routes[2].predicates[0]=Path=/noop/**
                        spring.cloud.gateway.routes[3].uri=lb://remote
                        spring.cloud.gateway.routes[3].predicates[0]=Path=/remote/**
                        spring.cloud.gateway.routes[3].filters[0]=PrefixPath=/v1
                        spring.cloud.gateway.routes[3].filters[1]=SetPath=/fixed
                        spring.cloud.gateway.routes[4].uri=lb://long-form
                        spring.cloud.gateway.routes[4].predicates[0].name=Path
                        spring.cloud.gateway.routes[4].predicates[0].args.pattern=/api/items
                        spring.cloud.gateway.routes[4].filters[0].name=StripPrefix
                        spring.cloud.gateway.routes[4].filters[0].args.parts=1
                        """).facts(new GatewayExtractor());

        assertThat(facts).filteredOn(Fact.Endpoint.class::isInstance)
                .extracting(f -> ((Fact.Endpoint) f).path().text())
                .containsExactlyInAnyOrder("/local/**", "/noop/**", "/remote/**", "/api/items");
        assertThat(facts).filteredOn(Fact.Call.class::isInstance)
                .extracting(f -> ((Fact.Call) f).url().text())
                .containsExactlyInAnyOrder("lb://remote/fixed", "lb://long-form/items");
    }

    @Test
    void openApiBaseOverrideAvoidsDuplicatingContainedContextAndKeepsOtherContext(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("openapi")).app()
                .resource("application.properties", "openapi.orders.base-path=/ctx/v3\n")
                .resource("openapi.yaml", """
                        openapi: 3.0.3
                        info:
                          title: Orders
                        servers:
                          - url: https://example.test/ctx/v1
                        paths:
                          /orders:
                            get:
                              tags: [Orders]
                            post: []
                        """);

        var contained = OpenApiSpec.endpoints(repo.service(), Value.exact("/ctx"));
        var separate = OpenApiSpec.endpoints(repo.service(), Value.exact("/other"));

        assertThat(contained).hasSize(2).allSatisfy(e -> assertThat(e.path().text()).isEqualTo("/ctx/v3/orders"));
        assertThat(separate).hasSize(2).allSatisfy(e -> assertThat(e.path().text()).isEqualTo("/other/ctx/v3/orders"));
    }

    @Test
    void openApiSpecsUnderBuildAndTestDirectoriesAreIgnored(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("ignored-specs")).app()
                .file("build/openapi.yaml", """
                        openapi: 3.0.3
                        paths:
                          /build:
                            get: {}
                        """)
                .file("src/test/resources/openapi.yaml", """
                        openapi: 3.0.3
                        paths:
                          /test:
                            get: {}
                        """);

        assertThat(OpenApiSpec.endpoints(repo.service(), Value.exact(""))).isEmpty();
    }

    @Test
    void mappingFallbacksHandleEmptyPathsAndUnknownRequestMethod(@TempDir Path tmp) {
        var facts = TempRepo.at(tmp.resolve("mapping")).app().java("x/Controller.java", """
                package x;
                @RestController class Controller {
                    @RequestMapping(path = {}, method = RequestMethod.GET) void emptyPath() {}
                    @RequestMapping(path = "/unknown", method = methodVariable) void unknown() {}
                    @GetMapping(path = {}) void composedEmptyPath() {}
                }
                """).facts(new InboundHttpExtractor());

        assertThat(facts).filteredOn(Fact.Endpoint.class::isInstance)
                .extracting(f -> ((Fact.Endpoint) f).method() + " " + ((Fact.Endpoint) f).path().text())
                .containsExactlyInAnyOrder("GET /", "* /unknown", "GET /");
    }

    @Test
    void diamondHierarchyInheritsTheSingleMappingOnceThroughBothPaths(@TempDir Path tmp) {
        var facts = TempRepo.at(tmp.resolve("diamond")).app().java("x/Controller.java", """
                package x;
                interface Root { @GetMapping("/root") Object get(); }
                interface Left extends Root {}
                interface Right extends Root {}
                class Base { Object get() { return null; } }
                @RestController class Controller extends Base implements Left, Right {
                    public Object get() { return null; }
                }
                """).facts(new InboundHttpExtractor());

        assertThat(facts).filteredOn(Fact.Endpoint.class::isInstance)
                .extracting(f -> ((Fact.Endpoint) f).method() + " " + ((Fact.Endpoint) f).path().text())
                .containsExactly("GET /root");
    }
}
