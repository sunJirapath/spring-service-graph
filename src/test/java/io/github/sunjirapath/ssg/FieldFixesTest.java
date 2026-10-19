package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.extract.OutboundHttpExtractor;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FieldFixesTest {

    @Test
    void nestedWorktreeDoesNotCreateServicesEndpointsOrEvidence(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("orders"))
                .app()
                .resource("application.properties", "spring.application.name=orders\n")
                .java("demo/OrdersController.java", """
                        package demo;
                        @RestController class OrdersController implements OrdersApi {
                            public Object current() { return null; }
                        }
                        """)
                .file("target/generated-sources/openapi/demo/OrdersApi.java", """
                        package demo;
                        interface OrdersApi {
                            @GetMapping("/orders") Object current();
                        }
                        """)
                .file(".claude/worktrees/old/.git", "gitdir: ../../../../.git/worktrees/old\n")
                .file(".claude/worktrees/old/src/main/java/app/OldApp.java",
                        "package app; @SpringBootApplication class OldApp {}")
                .file(".claude/worktrees/old/src/main/resources/application.properties",
                        "spring.application.name=orders-old\n")
                .file(".claude/worktrees/old/target/generated-sources/openapi/demo/OrdersApi.java", """
                        package demo;
                        interface OrdersApi {
                            @GetMapping("/old-orders") Object old();
                        }
                        """);

        var graph = TempRepo.scan(repo);

        assertThat(graph.nodes()).filteredOn(Node.Service.class::isInstance)
                .extracting(Node::id).containsExactly(Node.Service.idOf("orders"));
        assertThat(graph.nodes()).filteredOn(Node.Endpoint.class::isInstance)
                .extracting(Node::id).containsExactly(Node.Endpoint.idOf("orders", "GET", "/orders"));
        assertThat(graph.edges()).flatExtracting(Edge::evidence)
                .noneMatch(e -> e.file().contains("worktrees"));
        assertThat(graph.findings()).flatExtracting(Finding::evidence)
                .noneMatch(e -> e.file().contains("worktrees"));
    }

    @Test
    void duplicateServiceNamesProduceFindingAndStderrWarning(@TempDir Path tmp) throws Exception {
        var one = TempRepo.at(tmp.resolve("one")).app()
                .resource("application.properties", "spring.application.name=shared\n");
        var two = TempRepo.at(tmp.resolve("two")).app()
                .resource("application.properties", "spring.application.name=shared\n");
        PrintStream original = System.err;
        var bytes = new ByteArrayOutputStream();
        GraphHolder result = new GraphHolder();
        try (var replacement = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(replacement);
            result.graph = TempRepo.scan(one, two);
        } finally {
            System.setErr(original);
        }

        assertThat(result.graph.findings()).filteredOn(f -> f.kind() == Finding.Kind.DUPLICATE_SERVICE)
                .singleElement().satisfies(f -> assertThat(f.message()).contains("one/.", "two/."));
        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .contains("warn: service name 'shared' found twice", "one/.", "two/.");
    }

    @Test
    void sameNamedInterfacesResolveWithinTheirOwnModules(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("fleet"))
                .file("a/src/main/java/a/App.java", "package a; @SpringBootApplication class App {}")
                .file("a/src/main/resources/application.properties", "spring.application.name=service-a\n")
                .file("a/src/main/java/demo/api/OrdersApi.java", """
                        package demo.api;
                        public interface OrdersApi { @GetMapping("/a-orders") Object get(); }
                        """)
                .file("a/src/main/java/a/OrdersController.java", """
                        package a;
                        import demo.api.OrdersApi;
                        @RestController class OrdersController implements OrdersApi {
                            public Object get() { return null; }
                        }
                        """)
                .file("b/src/main/java/b/App.java", "package b; @SpringBootApplication class App {}")
                .file("b/src/main/resources/application.properties", "spring.application.name=service-b\n")
                .file("b/src/main/java/demo/api/OrdersApi.java", """
                        package demo.api;
                        public interface OrdersApi { @GetMapping("/b-orders") Object get(); }
                        """)
                .file("b/src/main/java/b/OrdersController.java", """
                        package b;
                        import demo.api.OrdersApi;
                        @RestController class OrdersController implements OrdersApi {
                            public Object get() { return null; }
                        }
                        """);

        var graph = TempRepo.scan(repo);

        assertThat(graph.nodes()).filteredOn(Node.Endpoint.class::isInstance).extracting(Node::id)
                .containsExactlyInAnyOrder(
                        Node.Endpoint.idOf("service-a", "GET", "/a-orders"),
                        Node.Endpoint.idOf("service-b", "GET", "/b-orders"))
                .doesNotContain(
                        Node.Endpoint.idOf("service-a", "GET", "/b-orders"),
                        Node.Endpoint.idOf("service-b", "GET", "/a-orders"));
    }

    @Test
    void exposesEvidenceUsesMappingLineAndIncludesControllerHandler(@TempDir Path tmp) {
        String api = """
                package demo;
                interface OrdersApi {
                    @Operation(summary = "read")
                    @PreAuthorize("hasRole('USER')")
                    @GetMapping("/orders/{id}")
                    Object get(String id);
                }
                """;
        String controller = """
                package demo;
                @RestController class OrdersController implements OrdersApi {
                    @Override
                    public Object get(String id) { return null; }
                }
                """;
        var repo = TempRepo.at(tmp.resolve("orders")).app()
                .resource("application.properties", "spring.application.name=orders\n")
                .java("demo/OrdersApi.java", api)
                .java("demo/OrdersController.java", controller);

        var exposes = TempRepo.scan(repo).edges().stream()
                .filter(e -> e.kind() == Edge.Kind.EXPOSES).toList();

        assertThat(exposes).singleElement().satisfies(edge -> assertThat(edge.evidence())
                .extracting(e -> e.file() + ":" + e.line())
                .containsExactly(
                        "src/main/java/demo/OrdersApi.java:" + lineOf(api, "@GetMapping"),
                        "src/main/java/demo/OrdersController.java:" + lineOf(controller, "public Object get")));
    }

    @Test
    void declarativeCallEvidenceUsesMappingAnnotationsInsteadOfEarlierAnnotations(@TempDir Path tmp) {
        String clients = """
                package demo;
                @FeignClient(name = "orders")
                interface OrdersClient {
                    @Operation(summary = "read")
                    @PreAuthorize("hasRole('USER')")
                    @GetMapping("/orders/{id}")
                    Object get(String id);
                }
                @HttpExchange("https://audit.example")
                interface AuditClient {
                    @Operation(summary = "write")
                    @PostExchange("/events")
                    Object post();
                }
                """;
        var repo = TempRepo.at(tmp.resolve("caller")).app().java("demo/Clients.java", clients);

        List<Fact.Call> calls = repo.facts(new OutboundHttpExtractor()).stream()
                .filter(Fact.Call.class::isInstance).map(Fact.Call.class::cast).toList();

        assertThat(calls).extracting(c -> c.client() + ":" + c.evidence().line())
                .containsExactlyInAnyOrder(
                        "feign:" + lineOf(clients, "@GetMapping"),
                        "http-exchange:" + lineOf(clients, "@PostExchange"));
    }

    private static int lineOf(String text, String marker) {
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(marker)) return i + 1;
        throw new AssertionError("missing marker " + marker);
    }

    private static final class GraphHolder {
        private io.github.sunjirapath.ssg.model.Graph graph;
    }
}
