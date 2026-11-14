package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Fact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayOutboundBranchGap2Test {
    @TempDir Path tmp;

    @Test
    void gatewayIgnoresMalformedAndBlankUriRoutesAndReadsNamedItems() {
        var facts = TempRepo.at(tmp.resolve("gateway")).app()
                .java("x/Empty.java", "package x; class Empty {}")
                .resource("application.properties", """
                        spring.cloud.gateway.routes[0].broken=ignored
                        spring.cloud.gateway.routes[bad].uri=lb://not-indexed
                        spring.cloud.gateway.routes[1].uri=
                        spring.cloud.gateway.routes[1].predicates[0].name=Path
                        spring.cloud.gateway.routes[1].predicates[0].args.pattern=/blank
                        spring.cloud.gateway.routes[2].uri=lb://named
                        spring.cloud.gateway.routes[2].predicates[0].name=Path
                        spring.cloud.gateway.routes[2].predicates[0].args.pattern=/api
                        spring.cloud.gateway.routes[2].filters[0].name=StripPrefix
                        spring.cloud.gateway.routes[2].filters[0].args.parts=1
                        spring.cloud.gateway.routes[3].uri=lb://rewrite
                        spring.cloud.gateway.routes[3].predicates[0]=Path=/bad/**
                spring.cloud.gateway.routes[3].filters[0]=RewritePath=[,/x
                        """).facts(new GatewayExtractor());

        assertThat(facts).filteredOn(Fact.Endpoint.class::isInstance)
                .extracting(f -> ((Fact.Endpoint) f).path().text()).containsExactly("/api", "/bad/**");
        assertThat(facts).filteredOn(Fact.Call.class::isInstance)
                .extracting(f -> ((Fact.Call) f).url().text()).containsExactly("lb://named/", "lb://rewrite/{?}");
    }

    @Test
    void outboundDistinguishesExchangeArityObjectUrlAndReceiverForms() {
        var facts = TempRepo.at(tmp.resolve("outbound")).app().java("x/Calls.java", """
                package x;
                class Calls {
                    RestTemplate rest;
                    WebClient web;
                    void send() {
                        rest.exchange("https://one.example/a");
                        rest.exchange("https://two.example/a", HttpMethod.DELETE, null, Object.class);
                        rest.getForObject(new URL("https://object.example/a"), Object.class);
                        this.rest.getForObject("/relative", Object.class);
                        this.web.get().uri(b -> b.path("/one").path("/two", "ignored").build());
                        this.web.post().uri(b -> b.queryParam("q", 1).build());
                        this.web.path().uri("/not-a-verb");
                        get().uri("/missing-client");
                    }
                    Object config() { return WebClient.builder().baseUrl("https://base.example").build(); }
                }
                """).facts(new OutboundHttpExtractor());

        assertThat(facts).filteredOn(Fact.Call.class::isInstance)
                .extracting(f -> ((Fact.Call) f).method() + " " + ((Fact.Call) f).url().text())
                .containsExactlyInAnyOrder("* https://one.example/a", "DELETE https://two.example/a",
                        "GET {?}", "GET https://base.example/relative", "GET https://base.example/one", "POST {?}");
    }

    @Test
    void gatewayEmptyPathRewritesToRootAndRestTemplateUsesItsSingleRootUri() {
        assertThat(GatewayExtractor.rewrite("", java.util.List.of()).text()).isEqualTo("/");
        assertThat(GatewayExtractor.rewrite("/discard", java.util.List.of(
                new GatewayExtractor.Def("SetPath", java.util.List.of(""), null))).text()).isEqualTo("/");
        var facts = TempRepo.at(tmp.resolve("root-uri")).app().java("x/Calls.java", """
                package x;
                class Calls {
                    RestTemplate rest;
                    Object config() { return new RestTemplateBuilder().rootUri("https://root.example/api"); }
                    void send() { rest.getForObject("/items", Object.class); }
                }
                """).facts(new OutboundHttpExtractor());

        assertThat(facts).filteredOn(Fact.Call.class::isInstance)
                .extracting(f -> ((Fact.Call) f).url().text()).containsExactly("https://root.example/api/items");
    }

    @Test
    void unresolvedGatewayDslPathIsPreservedInTheCall() {
        var facts = TempRepo.at(tmp.resolve("gateway-dsl")).app().java("x/Routes.java", """
                package x;
                @Configuration class Routes {
                    RouteLocator routes(String path) {
                        return builder.routes().route(r -> r.path(path).uri("lb://remote")).build();
                    }
                }
                """).facts(new GatewayExtractor());

        assertThat(facts).filteredOn(Fact.Endpoint.class::isInstance)
                .singleElement().isInstanceOfSatisfying(Fact.Endpoint.class, e -> assertThat(e.path().text()).isEqualTo("/{?}"));
        assertThat(facts).filteredOn(Fact.Call.class::isInstance)
                .singleElement().isInstanceOfSatisfying(Fact.Call.class, c -> assertThat(c.url().text()).isEqualTo("lb://remote/{?}"));
    }
}
