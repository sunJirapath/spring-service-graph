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
    void emptyInheritedPathStillSelectsTheWholeFirstMapping(@TempDir Path tmp) {
        var facts = TempRepo.at(tmp.resolve("empty-path")).app()
                .java("x/C.java", """
                        package x;
                        @RequestMapping(method = RequestMethod.POST) interface Verb {}
                        @RequestMapping(path = "/api", method = RequestMethod.GET) interface Path {}
                        @RestController class C implements Verb, Path {
                            @RequestMapping("/x") public void x() {}
                        }
                        """).facts(new InboundHttpExtractor());
        assertThat(endpoints(facts)).containsExactly("POST /x");
    }

    @Test
    void interfaceTypeMappingPrecedesSuperclassMapping(@TempDir Path tmp) {
        var facts = TempRepo.at(tmp.resolve("hierarchy")).app()
                .java("x/C.java", """
                        package x;
                        @RequestMapping(path = "/base", method = RequestMethod.DELETE) class Base {}
                        @RequestMapping(path = "/api", method = RequestMethod.GET) interface Api {}
                        @RestController class C extends Base implements Api {
                            @PostMapping("/x") public void x() {}
                        }
                        """).facts(new InboundHttpExtractor());
        assertThat(endpoints(facts)).containsExactlyInAnyOrder("GET /api/x", "POST /api/x");
    }

    @Test
    void conflictingMethodMappingsFollowTheConcreteMethodDeclaration(@TempDir Path tmp) {
        var facts = TempRepo.at(tmp.resolve("method-hierarchy")).app()
                .java("x/C.java", """
                        package x;
                        interface Api { @GetMapping("/interface") void x(); }
                        class Base { @PostMapping("/super") public void x() {} }
                        @RestController class Override extends Base implements Api { public void x() {} }
                        @RestController class Inherited extends Base implements Api {}
                        """).facts(new InboundHttpExtractor());
        assertThat(endpoints(facts)).containsExactlyInAnyOrder("GET /interface", "POST /super");
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
