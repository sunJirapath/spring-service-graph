package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Fact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiScopeTest {

    @Test
    void missingOrdersApiKeepsOnlyOperationsTaggedOrders(@TempDir Path tmp) {
        var repo = missingController(tmp.resolve("repo"), "OrdersApi")
                .resource("openapi.yaml", spec("""
                          /orders:
                            get:
                              tags: [Orders]
                          /legacy:
                            post:
                              tags: [Legacy]
                        """));

        assertThat(endpoints(repo)).extracting(e -> e.method() + " " + e.path().text())
                .containsExactly("GET /orders");
    }

    @Test
    void tagsMatchInterfaceNamesIgnoringCaseAndNonAlphanumerics(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("repo")).app()
                .java("demo/Controllers.java", """
                        package demo;
                        @RestController class LinesController implements LineItemsApi {}
                        @RestController class HrController implements Hr2Api {}
                        """)
                .resource("openapi.yaml", spec("""
                          /line-items:
                            get:
                              tags: [Line Items]
                          /people:
                            post:
                              tags: [HR2]
                        """));

        assertThat(endpoints(repo)).extracting(e -> e.method() + " " + e.path().text())
                .containsExactlyInAnyOrder("GET /line-items", "POST /people");
    }

    @Test
    void untaggedOperationUsesFirstPathSegmentAsInterfaceName(@TempDir Path tmp) {
        var repo = missingController(tmp.resolve("repo"), "OrdersApi")
                .resource("openapi.yaml", spec("""
                          /orders/{id}:
                            get: {}
                          /legacy/{id}:
                            get: {}
                        """));

        assertThat(endpoints(repo)).extracting(e -> e.method() + " " + e.path().text())
                .containsExactly("GET /orders/{id}");
    }

    @Test
    void untaggedFallbackKeepsOperationsThatDoNotNameMissingInterface(@TempDir Path tmp) {
        var repo = missingController(tmp.resolve("repo"), "GeneratedApi")
                .resource("openapi.yaml", spec("""
                          /legacy/orders:
                            delete: {}
                          /archive/orders:
                            post: {}
                        """));

        assertThat(endpoints(repo)).extracting(e -> e.method() + " " + e.path().text())
                .containsExactlyInAnyOrder("DELETE /legacy/orders", "POST /archive/orders");
    }

    @Test
    void untaggedFallbackExcludesInterfaceAlreadyPresentInSameService(@TempDir Path tmp) {
        var repo = missingController(tmp.resolve("repo"), "GeneratedApi")
                .java("demo/ExistingApi.java", "package demo; interface ExistingApi {}")
                .resource("openapi.yaml", spec("""
                          /existing/orders:
                            get: {}
                          /legacy/orders:
                            post: {}
                        """));

        assertThat(endpoints(repo)).extracting(e -> e.method() + " " + e.path().text())
                .containsExactly("POST /legacy/orders");
    }

    @Test
    void interfaceInAnotherServiceDoesNotExcludeUntaggedFallbackOperation(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("fleet"))
                .file("a/src/main/java/a/App.java", "package a; @SpringBootApplication class App {}")
                .file("a/src/main/resources/application.properties", "spring.application.name=alpha\n")
                .file("a/src/main/java/a/Controller.java",
                        "package a; @RestController class Controller implements GeneratedApi {}")
                .file("a/src/main/resources/openapi.yaml", spec("""
                          /legacy/orders:
                            get: {}
                        """))
                .file("b/src/main/java/b/App.java", "package b; @SpringBootApplication class App {}")
                .file("b/src/main/resources/application.properties", "spring.application.name=beta\n")
                .file("b/src/main/java/a/LegacyApi.java", "package a; interface LegacyApi {}")
                .file("b/src/main/java/b/Controller.java", "package b; @RestController class Controller {} ");

        var facts = new InboundHttpExtractor().extract(repo.service("alpha"));

        assertThat(facts).filteredOn(Fact.Endpoint.class::isInstance).map(Fact.Endpoint.class::cast)
                .extracting(e -> e.method() + " " + e.path().text()).containsExactly("GET /legacy/orders");
    }

    @Test
    void controllerEndpointWinsWhenSpecContainsSameMethodAndPath(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("repo")).app()
                .java("demo/Controller.java", """
                        package demo;
                        @RestController class Controller implements GeneratedApi {
                            @GetMapping("/orders") Object get() { return null; }
                        }
                        """)
                .resource("openapi.yaml", spec("""
                          /orders:
                            get:
                              tags: [Generated]
                        """));

        assertThat(endpoints(repo)).singleElement().satisfies(e -> {
            assertThat(e.method() + " " + e.path().text()).isEqualTo("GET /orders");
            assertThat(e.source()).isEqualTo("controller");
        });
    }

    @Test
    void missingSpecProducesOneProblemPerControllerNamingItsInterface(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("repo")).app()
                .java("demo/Controllers.java", """
                        package demo;
                        @RestController class OrdersController implements OrdersApi {}
                        @RestController class BillingController implements BillingApi {}
                        """);

        List<Fact.Problem> problems = repo.facts(new InboundHttpExtractor()).stream()
                .filter(Fact.Problem.class::isInstance).map(Fact.Problem.class::cast).toList();

        assertThat(problems).hasSize(2).allSatisfy(p -> assertThat(p.kind()).isEqualTo("MISSING_INTERFACE"));
        assertThat(problems).extracting(Fact.Problem::message)
                .anySatisfy(message -> assertThat(message).contains("OrdersApi"))
                .anySatisfy(message -> assertThat(message).contains("BillingApi"));
    }

    private static TempRepo missingController(Path root, String interfaceName) {
        return TempRepo.at(root).app().java("demo/Controller.java",
                "package demo; @RestController class Controller implements " + interfaceName + " {}");
    }

    private static List<Fact.Endpoint> endpoints(TempRepo repo) {
        return repo.facts(new InboundHttpExtractor()).stream()
                .filter(Fact.Endpoint.class::isInstance).map(Fact.Endpoint.class::cast).toList();
    }

    private static String spec(String paths) {
        return "openapi: 3.0.0\npaths:\n" + paths;
    }
}
