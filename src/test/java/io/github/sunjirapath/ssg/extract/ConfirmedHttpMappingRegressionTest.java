package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Fact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmedHttpMappingRegressionTest {

    @Test
    void combinesClassAndMethodRequestMethods(@TempDir Path tmp) {
        var facts = TempRepo.at(tmp.resolve("mvc")).app()
                .java("x/C.java", """
                        package x;
                        @RestController @RequestMapping(path = "/api", method = {RequestMethod.GET, RequestMethod.POST})
                        class C {
                            @RequestMapping(path = "/item", method = {RequestMethod.POST, RequestMethod.PUT}) void item() {}
                        }
                        """).facts(new InboundHttpExtractor());

        assertThat(endpoints(facts)).containsExactlyInAnyOrder("GET /api/item", "POST /api/item", "PUT /api/item");
    }

    @Test
    void discoversJaxRsOptionsResources(@TempDir Path tmp) {
        var facts = TempRepo.at(tmp.resolve("jaxrs")).app()
                .java("x/Resource.java", "package x; @Path(\"/resource\") class Resource { @OPTIONS Object options() { return null; } }")
                .facts(new InboundHttpExtractor());

        assertThat(endpoints(facts)).containsExactly("OPTIONS /resource");
    }

    @Test
    void inheritsClassMethodsWhenTheMethodMappingHasNoConstraint(@TempDir Path tmp) {
        var facts = TempRepo.at(tmp.resolve("inherited")).app()
                .java("x/C.java", """
                        package x;
                        @RequestMapping(path = "/api", method = RequestMethod.POST)
                        interface Api { @RequestMapping("/item") void item(); }
                        @RestController class C implements Api { public void item() {} }
                        @RestController @RequestMapping("/plain") class Plain {
                            @GetMapping("/item") void item() {}
                        }
                        """).facts(new InboundHttpExtractor());

        assertThat(endpoints(facts)).containsExactlyInAnyOrder("POST /api/item", "GET /plain/item");
    }

    @Test
    void intersectsGatewayMethodPredicatesAndSkipsEmptyIntersections(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("gateway")).app().java("x/Empty.java", "package x; class Empty {}")
                .resource("application.properties", """
                        spring.cloud.gateway.routes[0].uri=lb://overlap
                        spring.cloud.gateway.routes[0].predicates[0]=Path=/overlap/**
                        spring.cloud.gateway.routes[0].predicates[1]=Method=GET,POST
                        spring.cloud.gateway.routes[0].predicates[2]=Method=POST,PUT
                        spring.cloud.gateway.routes[1].uri=lb://disjoint
                        spring.cloud.gateway.routes[1].predicates[0]=Path=/disjoint/**
                        spring.cloud.gateway.routes[1].predicates[1]=Method=GET
                        spring.cloud.gateway.routes[1].predicates[2]=Method=DELETE
                        """);

        var facts = repo.facts(new GatewayExtractor());

        assertThat(endpoints(facts)).containsExactly("POST /overlap/**");
        assertThat(calls(facts)).containsExactly("POST lb://overlap/overlap/**");
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
