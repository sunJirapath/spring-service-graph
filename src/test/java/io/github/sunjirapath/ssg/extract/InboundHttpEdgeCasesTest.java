package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Fact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InboundHttpEdgeCasesTest {

    @Test
    void extractsEveryComposedMappingAndIgnoresRepresentationAttributes(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("verbs")).app().java("x/C.java", """
                package x;
                @RestController @RequestMapping("/api") class C {
                    @GetMapping(value = "/get", produces = "application/json") void get() {}
                    @PostMapping(path = "/post", consumes = "application/json") void post() {}
                    @PutMapping("/put") void put() {}
                    @DeleteMapping("/delete") void delete() {}
                    @PatchMapping("/patch") void patch() {}
                }
                """));

        assertThat(endpoints).extracting(InboundHttpEdgeCasesTest::methodAndPath)
                .containsExactlyInAnyOrder(
                        "GET /api/get", "POST /api/post", "PUT /api/put",
                        "DELETE /api/delete", "PATCH /api/patch");
    }

    @Test
    void requestMappingSupportsAnySingleArrayAndStaticImportedMethods(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("request-methods")).app().java("x/C.java", """
                package x;
                import static org.springframework.web.bind.annotation.RequestMethod.GET;
                @RestController class C {
                    @RequestMapping("/any") void any() {}
                    @RequestMapping(value = "/one", method = RequestMethod.GET) void one() {}
                    @RequestMapping(path = "/many", method = {RequestMethod.GET, RequestMethod.POST}) void many() {}
                    @RequestMapping(value = "/static", method = GET) void imported() {}
                }
                """));

        assertThat(endpoints).extracting(InboundHttpEdgeCasesTest::methodAndPath)
                .containsExactlyInAnyOrder("* /any", "GET /one", "GET /many", "POST /many", "GET /static");
    }

    @Test
    void emptyRequestMethodArrayMeansAnyMethod(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("empty-request-methods")).app().java("x/C.java", """
                package x;
                @RestController class C { @RequestMapping(path = "/any", method = {}) void any() {} }
                """));

        // EXPECTATION?: Spring treats an empty RequestMethod array as no HTTP-method restriction.
        assertThat(endpoints).extracting(InboundHttpEdgeCasesTest::methodAndPath).containsExactly("* /any");
    }

    @Test
    void classAndMethodPathArraysFormCartesianProduct(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("arrays")).app().java("x/C.java", """
                package x;
                @RestController @RequestMapping(path = {"/v1", "/v2"}) class C {
                    @GetMapping(path = {"/users", "/people"}) void list() {}
                }
                """));

        assertThat(endpoints).extracting(e -> e.path().text())
                .containsExactlyInAnyOrder("/v1/users", "/v1/people", "/v2/users", "/v2/people");
    }

    @Test
    void normalizesEmptyRootMissingLeadingTrailingAndDoubleSlashes(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("normalization")).app().java("x/C.java", """
                package x;
                @RestController @RequestMapping("api//") class C {
                    @GetMapping void empty() {}
                    @GetMapping("//items//") void items() {}
                }
                @RestController class Root { @GetMapping("/") void root() {} }
                """));

        assertThat(endpoints).extracting(e -> e.path().text())
                .containsExactlyInAnyOrder("/api", "/api/items", "/");
    }

    @Test
    void resolvesPathConstantDeclaredInAnotherClass(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("constant")).app()
                .java("x/Paths.java", "package x; class Paths { static final String API = \"/constant\"; }")
                .java("x/C.java", "package x; @RestController class C { @GetMapping(Paths.API) void get() {} }"));

        assertThat(endpoints).singleElement().satisfies(e -> {
            assertThat(e.path().text()).isEqualTo("/constant");
            assertThat(e.path().confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(e.path().via()).singleElement().satisfies(v -> assertThat(v.file()).endsWith("Paths.java"));
        });
    }

    @Test
    void resolvesPlaceholderDefaultButKeepsMissingPlaceholderAsEndpoint(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("placeholders")).app().java("x/C.java", """
                package x;
                @RestController class C {
                    @GetMapping("${api.path:/default}") void withDefault() {}
                    @GetMapping("${missing.path}") void missing() {}
                }
                """));

        assertThat(endpoints).anySatisfy(e -> {
            assertThat(e.path().text()).isEqualTo("/default");
            assertThat(e.path().confidence()).isEqualTo(Confidence.RESOLVED);
        }).anySatisfy(e -> {
            assertThat(e.path().text()).isEqualTo("/{?}");
            assertThat(e.path().confidence()).isEqualTo(Confidence.UNRESOLVED);
        });
    }

    @Test
    void preservesRegexPathVariables(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("regex")).app().java("x/C.java", """
                package x;
                @RestController class C { @GetMapping("/orders/{id:\\\\d+}") void get() {} }
                """));

        assertThat(endpoints).singleElement().satisfies(e -> assertThat(e.path().text()).isEqualTo("/orders/{id:\\d+}"));
    }

    @Test
    void controllerResponseBodyMethodIsExposedButUnannotatedClassIsIgnored(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("controller")).app().java("x/C.java", """
                package x;
                @Controller class C { @ResponseBody @GetMapping("/ok") Object ok() { return null; } }
                class Utility { @GetMapping("/not-an-endpoint") void helper() {} }
                """));

        assertThat(endpoints).extracting(e -> e.path().text()).containsExactly("/ok");
    }

    @Test
    void unimplementedMappedInterfaceProducesNoEndpoint(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("orphan-interface")).app().java("x/Api.java", """
                package x;
                @RequestMapping("/api") interface Api { @GetMapping("/orphan") Object get(); }
                """));

        assertThat(endpoints).isEmpty();
    }

    @Test
    void inheritedInterfaceMappingUsesAnnotationEvidenceAndOverrideHandler(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("interface-override")).app()
                .java("x/Api.java", """
                        package x;
                        @RequestMapping("/api") interface Api {
                            @GetMapping("/items/{id}") Object get(String id);
                        }
                        """)
                .java("x/C.java", """
                        package x;
                        @RestController class C implements Api {
                            public Object get(String id) { return null; }
                        }
                        """));

        assertThat(endpoints).singleElement().satisfies(e -> {
            assertThat(e.path().text()).isEqualTo("/api/items/{id}");
            assertThat(e.evidence().file()).endsWith("Api.java");
            assertThat(e.handler().file()).endsWith("C.java");
        });
    }

    @Test
    void inheritedInterfaceDefaultMethodIsExposedWithoutOverride(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("default-method")).app().java("x/C.java", """
                package x;
                interface Api { @GetMapping("/health") default String health() { return "ok"; } }
                @RestController class C implements Api {}
                """));

        assertThat(endpoints).singleElement().satisfies(e -> {
            assertThat(e.path().text()).isEqualTo("/health");
            assertThat(e.handler()).isEqualTo(e.evidence());
        });
    }

    @Test
    void abstractBaseMappingUsesConcreteOverrideAsHandler(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("abstract-base")).app().java("x/C.java", """
                package x;
                abstract class Base { @GetMapping("/base") abstract Object get(); }
                @RestController class C extends Base { @Override Object get() { return null; } }
                """));

        assertThat(endpoints).singleElement().satisfies(e -> {
            assertThat(e.path().text()).isEqualTo("/base");
            assertThat(e.evidence().line()).isEqualTo(2);
            assertThat(e.handler().line()).isEqualTo(3);
        });
    }

    @Test
    void controllerMethodMappingOverridesInterfaceMethodMapping(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("method-wins")).app().java("x/C.java", """
                package x;
                interface Api { @GetMapping("/interface") Object get(); }
                @RestController class C implements Api {
                    @PostMapping("/controller") public Object get() { return null; }
                }
                """));

        assertThat(endpoints).extracting(InboundHttpEdgeCasesTest::methodAndPath)
                .containsExactly("POST /controller");
    }

    @Test
    void interfaceClassMappingIsInheritedWhenControllerHasNone(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("class-path")).app().java("x/C.java", """
                package x;
                @RequestMapping("/interface") interface Api { @GetMapping("/x") Object get(); }
                @RestController class C implements Api { public Object get() { return null; } }
                """));

        assertThat(endpoints).singleElement().satisfies(e -> assertThat(e.path().text()).isEqualTo("/interface/x"));
    }

    @Test
    void controllerClassMappingOverridesInterfaceClassMapping(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("controller-class-wins")).app().java("x/C.java", """
                package x;
                @RequestMapping("/interface") interface Api { @GetMapping("/x") Object get(); }
                @RestController @RequestMapping("/controller") class C implements Api {
                    public Object get() { return null; }
                }
                """));

        assertThat(endpoints).singleElement().satisfies(e -> assertThat(e.path().text()).isEqualTo("/controller/x"));
    }

    @Test
    void genericInterfaceReturnTypeDoesNotPreventMappingInheritance(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("generic")).app().java("x/C.java", """
                package x;
                interface Api<T> { @GetMapping("/generic/{id}") T get(String id); }
                class Pet {}
                @RestController class C implements Api<Pet> { public Pet get(String id) { return null; } }
                """));

        assertThat(endpoints).singleElement().satisfies(e -> {
            assertThat(e.path().text()).isEqualTo("/generic/{id}");
            assertThat(e.handler().line()).isEqualTo(4);
        });
    }

    @Test
    void mappingsFromTwoInterfacesAreBothExposed(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("two-interfaces")).app().java("x/C.java", """
                package x;
                interface ReadApi { @GetMapping("/items") Object read(); }
                interface WriteApi { @PostMapping("/items") Object write(Object body); }
                @RestController class C implements ReadApi, WriteApi {
                    public Object read() { return null; }
                    public Object write(Object body) { return null; }
                }
                """));

        assertThat(endpoints).extracting(InboundHttpEdgeCasesTest::methodAndPath)
                .containsExactlyInAnyOrder("GET /items", "POST /items");
    }

    @Test
    void overloadedMappedHandlersRemainDistinct(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("overloads")).app().java("x/C.java", """
                package x;
                @RestController class C {
                    @GetMapping("/one/{id}") Object read(long id) { return null; }
                    @GetMapping("/many") Object read(java.util.List<Long> ids) { return null; }
                }
                """));

        assertThat(endpoints).extracting(e -> e.path().text()).containsExactlyInAnyOrder("/one/{id}", "/many");
    }

    @Test
    void prefixesServletContextPathAndTrimsItsTrailingSlash(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("servlet-context")).app()
                .resource("application.properties", "server.servlet.context-path=/fleet/\n")
                .java("x/C.java", "package x; @RestController class C { @GetMapping(\"/items\") void get() {} }"));

        assertThat(endpoints).singleElement().satisfies(e -> assertThat(e.path().text()).isEqualTo("/fleet/items"));
    }

    @Test
    void prefixesWebFluxBasePathWhenServletContextIsAbsent(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("webflux-context")).app()
                .resource("application.properties", "spring.webflux.base-path=reactive/\n")
                .java("x/C.java", "package x; @RestController class C { @GetMapping(\"items\") void get() {} }"));

        assertThat(endpoints).singleElement().satisfies(e -> assertThat(e.path().text()).isEqualTo("/reactive/items"));
    }

    @Test
    void prefixesMvcServletPathWhenOtherContextKeysAreAbsent(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("mvc-context")).app()
                .resource("application.properties", "spring.mvc.servlet.path=/mvc\n")
                .java("x/C.java", "package x; @RestController class C { @GetMapping(\"/items\") void get() {} }"));

        assertThat(endpoints).singleElement().satisfies(e -> assertThat(e.path().text()).isEqualTo("/mvc/items"));
    }

    @Test
    void servletContextPathTakesPrecedenceOverOtherContextKeys(@TempDir Path tmp) {
        var endpoints = endpoints(TempRepo.at(tmp.resolve("context-precedence")).app()
                .resource("application.properties", """
                        server.servlet.context-path=/servlet
                        spring.webflux.base-path=/reactive
                        spring.mvc.servlet.path=/mvc
                        """)
                .java("x/C.java", "package x; @RestController class C { @GetMapping(\"/x\") void get() {} }"));

        assertThat(endpoints).singleElement().satisfies(e -> assertThat(e.path().text()).isEqualTo("/servlet/x"));
    }

    @Test
    void openApiThreeServerPathAddsAllVerbsAndIgnoresNonOperations(@TempDir Path tmp) {
        var endpoints = endpoints(missingInterfaceRepo(tmp.resolve("openapi3")).resource("api.yaml", """
                openapi: 3.0.3
                servers:
                  - url: https://api.example.com/root/v1
                paths:
                  /things/{id}:
                    summary: shared summary
                    parameters: []
                    get: {}
                    put: {}
                    post: {}
                    delete: {}
                    patch: {}
                    head: {}
                    options: {}
                    trace: {}
                """));

        assertThat(endpoints).extracting(InboundHttpEdgeCasesTest::methodAndPath)
                .containsExactlyInAnyOrder(
                        "GET /root/v1/things/{id}", "PUT /root/v1/things/{id}",
                        "POST /root/v1/things/{id}", "DELETE /root/v1/things/{id}",
                        "PATCH /root/v1/things/{id}", "HEAD /root/v1/things/{id}",
                        "OPTIONS /root/v1/things/{id}", "TRACE /root/v1/things/{id}");
        assertThat(endpoints).allSatisfy(e -> {
            assertThat(e.source()).isEqualTo("openapi");
            assertThat(e.path().confidence()).isEqualTo(Confidence.RESOLVED);
        });
    }

    @Test
    void swaggerTwoJsonUsesBasePath(@TempDir Path tmp) {
        var endpoints = endpoints(missingInterfaceRepo(tmp.resolve("swagger-json")).resource("swagger.json", """
                {
                  "swagger": "2.0",
                  "basePath": "/legacy",
                  "paths": { "/pets": { "get": {}, "parameters": [] } }
                }
                """));

        assertThat(endpoints).extracting(InboundHttpEdgeCasesTest::methodAndPath)
                .containsExactly("GET /legacy/pets");
    }

    @Test
    void missingGeneratedInterfaceWithoutSpecReportsProblem(@TempDir Path tmp) {
        var facts = facts(missingInterfaceRepo(tmp.resolve("missing")));

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.Problem.class, p -> {
            assertThat(p.kind()).isEqualTo("MISSING_INTERFACE");
            assertThat(p.message()).contains("C").contains("no OpenAPI spec");
        });
    }

    @Test
    void specsUnderTestAndBuildAndNonSpecYamlAreIgnored(@TempDir Path tmp) {
        var repo = missingInterfaceRepo(tmp.resolve("ignored-specs"))
                .resource("notes.yaml", "name: openapi migration notes\npaths:\n  /wrong:\n    get: {}\n")
                .file("src/test/resources/openapi.yaml", "openapi: 3.0.0\npaths:\n  /test:\n    get: {}\n")
                .file("build/generated/openapi.yaml", "openapi: 3.0.0\npaths:\n  /build:\n    get: {}\n");

        assertThat(facts(repo)).singleElement().isInstanceOf(Fact.Problem.class);
    }

    @Test
    void malformedSpecDoesNotThrowAndStillReportsMissingInterface(@TempDir Path tmp) {
        var repo = missingInterfaceRepo(tmp.resolve("malformed")).resource("openapi.yaml", "openapi: [\npaths: nope");

        assertThat(facts(repo)).singleElement().isInstanceOfSatisfying(Fact.Problem.class,
                p -> assertThat(p.kind()).isEqualTo("MISSING_INTERFACE"));
    }

    @Test
    void malformedSpecDoesNotHideLaterValidSpec(@TempDir Path tmp) {
        var endpoints = endpoints(missingInterfaceRepo(tmp.resolve("malformed-and-valid"))
                .resource("a-bad.yaml", "openapi: [\npaths: nope")
                .resource("z-good.yaml", """
                        openapi: 3.0.0
                        paths:
                          /still-visible:
                            get: {}
                        """));

        assertThat(endpoints).extracting(InboundHttpEdgeCasesTest::methodAndPath)
                .containsExactly("GET /still-visible");
    }

    @Test
    void knownLimitOpenApiBasePathPropertyOverrideIsNotApplied(@TempDir Path tmp) {
        var endpoints = endpoints(missingInterfaceRepo(tmp.resolve("openapi-known-limit"))
                .resource("application.properties", "openapi.demo.base-path=/configured\n")
                .resource("openapi.yaml", """
                        openapi: 3.0.0
                        servers:
                          - url: /from-spec
                        paths:
                          /items:
                            get: {}
                        """));

        assertThat(endpoints).singleElement().satisfies(e -> assertThat(e.path().text()).isEqualTo("/from-spec/items"));
    }

    private static TempRepo missingInterfaceRepo(Path root) {
        return TempRepo.at(root).app().java("x/C.java", "package x; @RestController class C implements MissingApi {}");
    }

    private static List<Fact> facts(TempRepo repo) {
        return repo.facts(new InboundHttpExtractor());
    }

    private static List<Fact.Endpoint> endpoints(TempRepo repo) {
        return facts(repo).stream().filter(Fact.Endpoint.class::isInstance).map(Fact.Endpoint.class::cast).toList();
    }

    private static String methodAndPath(Fact.Endpoint endpoint) {
        return endpoint.method() + " " + endpoint.path().text();
    }
}
