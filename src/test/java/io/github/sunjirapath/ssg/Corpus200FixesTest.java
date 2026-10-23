package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Corpus200FixesTest {
    @TempDir Path tmp;

    @Test
    void buildDirectoryNamesInsideProductionPackagesDoNotHideApps() {
        for (String pkg : List.of("com.target", "com.build", "com.src.test")) {
            var repo = TempRepo.at(tmp.resolve(pkg)).java(pkg.replace('.', '/') + "/App.java",
                    "package " + pkg + "; @SpringBootApplication @RestController class App { @GetMapping(\"/ping\") void ping() {} }")
                    .file("target/classes/Bad.java", "@SpringBootApplication @RestController class Bad { @GetMapping(\"/bad\") void bad() {} }")
                    .file("build/classes/Worse.java", "@SpringBootApplication @RestController class Worse { @GetMapping(\"/worse\") void bad() {} }");
            var graph = TempRepo.scan(repo);
            assertThat(graph.nodes()).filteredOn(Node.Service.class::isInstance).hasSize(1);
            assertThat(graph.nodes()).filteredOn(Node.Endpoint.class::isInstance)
                    .extracting(n -> ((Node.Endpoint) n).path()).containsExactly("/ping");
        }
    }

    @Test
    void nestedWebFluxPathsComposeWithoutPrefixingOutsideRoutes() {
        var repo = routes("""
                return nest(path(PREFIX).and(accept(JSON)),
                        nest(accept(JSON).and(path("/v1")),
                                route(GET("/items"), h::handle).andRoute(POST("/items"), h::handle)))
                        .andRoute(GET("/outside"), h::handle);
                """).resource("application.properties", "spring.webflux.base-path=/reactive\n");
        var graph = TempRepo.scan(repo);
        assertThat(graph.nodes()).filteredOn(Node.Endpoint.class::isInstance)
                .extracting(n -> ((Node.Endpoint) n).method() + " " + ((Node.Endpoint) n).path())
                .containsExactlyInAnyOrder("GET /reactive/api/v1/items", "POST /reactive/api/v1/items", "GET /reactive/outside");
        assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.EXPOSES)
                .allSatisfy(e -> assertThat(e.evidence()).anySatisfy(ev -> {
                    assertThat(ev.file()).isEqualTo("src/main/java/app/Routes.java");
                    assertThat(ev.line()).isPositive();
                }));
    }

    @Test
    void unknownNestedPredicatesDoNotProduceConfidentUnprefixedEndpoints() {
        for (String predicate : List.of("dynamic", "path(\"/a\").or(path(\"/b\"))")) {
            var graph = TempRepo.scan(routes("return nest(" + predicate + ", route(GET(\"/items\"), h::handle));"));
            assertThat(graph.nodes()).filteredOn(Node.Endpoint.class::isInstance)
                    .extracting(n -> ((Node.Endpoint) n).path()).containsExactly("/{?}/items");
            assertThat(graph.findings()).anyMatch(f -> f.kind() == Finding.Kind.UNRESOLVED);
        }
    }

    @Test
    void nestingPredicatesAreNotEmittedAsHandlerlessEndpoints() {
        var graph = TempRepo.scan(routes("return nest(GET(\"/api\"), nest(all(), route(GET(\"/items\"), h::handle)));"));
        assertThat(graph.nodes()).filteredOn(Node.Endpoint.class::isInstance)
                .extracting(n -> ((Node.Endpoint) n).path()).containsExactly("/api/items");
        assertThat(graph.findings()).noneMatch(f -> f.kind() == Finding.Kind.UNRESOLVED);
    }

    @Test
    void contradictoryNestedMethodsDoNotProduceExactPaths() {
        for (String predicate : List.of("GET(\"/api\")", "path(\"/api\").and(method(GET))", "method(GET)")) {
            var graph = TempRepo.scan(routes("return nest(" + predicate + ", route(POST(\"/items\"), h::handle));"));
            assertThat(graph.nodes()).filteredOn(Node.Endpoint.class::isInstance)
                    .extracting(n -> ((Node.Endpoint) n).path()).containsExactly("/{?}/items");
            assertThat(graph.findings()).anyMatch(f -> f.kind() == Finding.Kind.UNRESOLVED);
        }
        var graph = TempRepo.scan(routes("return nest(method(GET), route(GET(\"/items\"), h::handle));"));
        assertThat(graph.nodes()).filteredOn(Node.Endpoint.class::isInstance)
                .extracting(n -> ((Node.Endpoint) n).path()).containsExactly("/items");
        assertThat(graph.findings()).noneMatch(f -> f.kind() == Finding.Kind.UNRESOLVED);
    }

    private TempRepo routes(String body) {
        return TempRepo.at(tmp.resolve("routes")).app().java("app/Routes.java", """
                package app;
                import static org.springframework.web.reactive.function.server.RouterFunctions.*;
                import static org.springframework.web.reactive.function.server.RequestPredicates.*;
                import static org.springframework.http.MediaType.APPLICATION_JSON;
                import static org.springframework.http.HttpMethod.GET;
                @Configuration class Routes {
                    static final String PREFIX = "/api";
                    @Bean RouterFunction routes(H h, RequestPredicate dynamic) {
                """ + body + "\n}\n}\n")
                .java("app/H.java", "package app; class H { Object handle(ServerRequest r) { return null; } }");
    }
}
