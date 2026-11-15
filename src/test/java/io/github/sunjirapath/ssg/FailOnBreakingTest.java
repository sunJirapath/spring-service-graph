package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.diff.GraphDiff;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import io.github.sunjirapath.ssg.output.JsonOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailOnBreakingTest {

    @Test
    void existingIncompleteEvidenceInEitherScanCannotPassTheGate(@TempDir Path tmp) throws Exception {
        Graph clean = new Graph(List.of(), List.of(), List.of());
        for (var kind : List.of(Finding.Kind.UNRESOLVED, Finding.Kind.PARSE_ERROR, Finding.Kind.MISSING_INTERFACE,
                Finding.Kind.DUPLICATE_SERVICE, Finding.Kind.INCOMPLETE_ANALYSIS)) {
            Graph incomplete = new Graph(List.of(), List.of(), List.of(
                    new Finding(kind, "caller", "incomplete input", List.of(new Evidence("caller", "Client.java", 1)))));
            assertThat(gatedDiff(tmp, incomplete, clean)).as("base %s", kind).isEqualTo(4);
            assertThat(gatedDiff(tmp, clean, incomplete)).as("head %s", kind).isEqualTo(4);
            assertThat(gatedDiff(tmp, incomplete, incomplete)).as("both %s", kind).isEqualTo(4);
        }
    }

    @Test
    void incompleteTakesPrecedenceOverProvenBreakingChanges(@TempDir Path tmp) throws Exception {
        var paths = breakingGraphs(tmp);
        Graph base = JsonOutput.read(paths.base());
        Graph head = JsonOutput.read(paths.head());
        Graph incomplete = new Graph(head.nodes(), head.edges(), List.of(new Finding(Finding.Kind.PARSE_ERROR,
                "gateway", "failed source", List.of(new Evidence("gateway", "Other.java", 1)))));

        var result = GraphDiff.compare(base, incomplete);
        assertThat(result.breaking()).isNotEmpty();
        assertThat(result.incomplete()).isNotEmpty();
        assertThat(gatedDiff(tmp, base, incomplete)).isEqualTo(4);
    }

    @Test
    void inputWithoutDiscoveredServicesIsIncomplete(@TempDir Path tmp) throws Exception {
        var repo = TempRepo.at(tmp.resolve("unrecognized")).file("README.md", "No recognized launcher");
        Graph graph = TempRepo.scan(repo);

        assertThat(graph.findings()).anyMatch(f -> f.kind() == Finding.Kind.INCOMPLETE_ANALYSIS);
        assertThat(gatedDiff(tmp, graph, graph)).isEqualTo(4);
    }

    @Test
    void removedEndpointWithKotlinOnlyCallerFailsClosed(@TempDir Path tmp) throws Exception {
        var orders = orders(tmp);
        var caller = TempRepo.at(tmp.resolve("caller")).app()
                .resource("application.properties", "spring.application.name=caller\n")
                .file("src/main/kotlin/app/Client.kt", """
                        package app
                        @FeignClient(name="orders") interface Client {
                            @GetMapping("/old") fun old(): String
                        }
                        """);
        Graph base = TempRepo.scan(orders, caller);
        orders.java("app/Api.java", "package app; @RestController class Api {}");
        Graph head = TempRepo.scan(orders, caller);

        assertThat(base.edges()).noneMatch(e -> e.kind() == Edge.Kind.CALLS);
        assertThat(head.findings()).anySatisfy(f -> {
            assertThat(f.kind()).isEqualTo(Finding.Kind.INCOMPLETE_ANALYSIS);
            assertThat(f.evidence()).anySatisfy(e -> assertThat(e.file()).endsWith("Client.kt"));
        });
        assertThat(GraphDiff.compare(base, head).markdown()).contains("## Incomplete analysis")
                .doesNotContain("_None found._");
        assertThat(gatedDiff(tmp, base, head)).isEqualTo(4);
    }

    @Test
    void removedEndpointWithNewlyUnresolvedCallerFailsClosed(@TempDir Path tmp) throws Exception {
        var orders = orders(tmp);
        var caller = caller(tmp);
        Graph base = TempRepo.scan(orders, caller);
        assertThat(base.edges()).anyMatch(e -> e.kind() == Edge.Kind.CALLS
                && e.to().equals(Node.Endpoint.idOf("orders", "GET", "/old")));
        orders.java("app/Api.java", "package app; @RestController class Api {}");
        caller.java("app/Client.java", """
                package app;
                @FeignClient(url="${runtime.url}") interface Client {
                    @GetMapping("/old") Object old();
                }
                """);
        Graph head = TempRepo.scan(orders, caller);

        assertThat(head.findings()).anyMatch(f -> f.kind() == Finding.Kind.UNRESOLVED);
        assertThat(GraphDiff.compare(base, head).incomplete()).isNotEmpty();
        assertThat(gatedDiff(tmp, base, head)).isEqualTo(4);
    }

    @Test
    void removedEndpointWithFailedCallerParsingFailsClosed(@TempDir Path tmp) throws Exception {
        var orders = orders(tmp);
        var caller = caller(tmp);
        Graph base = TempRepo.scan(orders, caller);
        orders.java("app/Api.java", "package app; @RestController class Api {}");
        caller.java("app/Client.java", "package app; interface Client {");
        Graph head = TempRepo.scan(orders, caller);

        assertThat(head.findings()).anyMatch(f -> f.kind() == Finding.Kind.PARSE_ERROR);
        assertThat(gatedDiff(tmp, base, head)).isEqualTo(4);
    }

    @Test
    void malformedYamlFailsClosedWithoutLeakingParserSource(@TempDir Path tmp) throws Exception {
        var orders = orders(tmp);
        Graph base = TempRepo.scan(orders);
        orders.resource("application.yml", "spring: [CANARY_SECRET\n");
        Graph head = TempRepo.scan(orders);

        assertThat(head.findings()).anySatisfy(f -> {
            assertThat(f.kind()).isEqualTo(Finding.Kind.PARSE_ERROR);
            assertThat(f.message()).contains("cannot read configuration").doesNotContain("CANARY_SECRET");
            assertThat(f.evidence()).anySatisfy(e -> assertThat(e.file()).endsWith("application.yml"));
        });
        assertThat(GraphDiff.report(base, head)).doesNotContain("CANARY_SECRET");
        assertThat(gatedDiff(tmp, base, head)).isEqualTo(4);
    }

    @Test
    void newCallerToMissingGeneratedContractIsIncompleteRatherThanBreaking(@TempDir Path tmp) throws Exception {
        var orders = TempRepo.at(tmp.resolve("orders")).app()
                .resource("application.properties", "spring.application.name=orders\n")
                .java("app/Api.java", """
                        package app;
                        @RestController class Api implements GeneratedApi {
                            public Object old() { return null; }
                        }
                        """);
        var caller = TempRepo.at(tmp.resolve("caller")).app()
                .resource("application.properties", "spring.application.name=caller\n");
        Graph base = TempRepo.scan(orders, caller);
        caller.java("app/Client.java", clientSource());
        Graph head = TempRepo.scan(orders, caller);

        assertThat(head.findings()).anyMatch(f -> f.kind() == Finding.Kind.MISSING_INTERFACE)
                .noneMatch(f -> f.kind() == Finding.Kind.CONTRACT_DRIFT);
        assertThat(head.edges()).anyMatch(e -> e.kind() == Edge.Kind.CALLS && e.to().equals(Node.Service.idOf("orders")));
        var result = GraphDiff.compare(base, head);
        assertThat(result.breaking()).isEmpty();
        assertThat(result.incomplete()).isNotEmpty();
        assertThat(gatedDiff(tmp, base, head)).isEqualTo(4);
    }

    @Test
    void lostGeneratedContractDoesNotProveEndpointRemoval(@TempDir Path tmp) throws Exception {
        var orders = TempRepo.at(tmp.resolve("orders")).app()
                .resource("application.properties", "spring.application.name=orders\n")
                .java("app/Api.java", "package app; @RestController class Api implements GeneratedApi { public Object old() { return null; } }")
                .java("app/GeneratedApi.java", "package app; interface GeneratedApi { @GetMapping(\"/old\") Object old(); }");
        var caller = caller(tmp);
        Graph base = TempRepo.scan(orders, caller);
        assertThat(base.nodes()).anyMatch(n -> n.id().equals(Node.Endpoint.idOf("orders", "GET", "/old")));
        orders.java("app/GeneratedApi.java", "package app; interface Unrelated {}");
        Graph head = TempRepo.scan(orders, caller);

        assertThat(head.findings()).anyMatch(f -> f.kind() == Finding.Kind.MISSING_INTERFACE)
                .noneMatch(f -> f.kind() == Finding.Kind.CONTRACT_DRIFT);
        assertThat(GraphDiff.compare(base, head).breaking()).isEmpty();
        assertThat(gatedDiff(tmp, base, head)).isEqualTo(4);
    }

    private static TempRepo orders(Path tmp) {
        return TempRepo.at(tmp.resolve("orders")).app()
                .resource("application.properties", "spring.application.name=orders\n")
                .java("app/Api.java", "package app; @RestController class Api { @GetMapping(\"/old\") Object old() { return null; } }");
    }

    private static TempRepo caller(Path tmp) {
        return TempRepo.at(tmp.resolve("caller")).app()
                .resource("application.properties", "spring.application.name=caller\n")
                .java("app/Client.java", clientSource());
    }

    private static String clientSource() {
        return "package app; @FeignClient(name=\"orders\") interface Client { @GetMapping(\"/old\") Object old(); }";
    }

    private static int gatedDiff(Path tmp, Graph base, Graph head) throws Exception {
        Path baseOut = tmp.resolve("base-out");
        Path headOut = tmp.resolve("head-out");
        JsonOutput.write(base, baseOut);
        JsonOutput.write(head, headOut);
        return Main.run(new String[]{"diff", "--base", baseOut.toString(), "--head", headOut.toString(), "--fail-on-breaking"});
    }

    @Test
    void diffWithoutFailOnBreakingReturnsSuccessWhenChangesAreBreaking(@TempDir Path tmp) throws Exception {
        var graphs = breakingGraphs(tmp);

        assertThat(Main.run(new String[]{"diff", "--base", graphs.base().toString(), "--head", graphs.head().toString()}))
                .isZero();
    }

    @Test
    void diffWithFailOnBreakingReturnsThreeWhenChangesAreBreaking(@TempDir Path tmp) throws Exception {
        var graphs = breakingGraphs(tmp);

        assertThat(Main.run(new String[]{"diff", "--base", graphs.base().toString(), "--head", graphs.head().toString(),
                "--fail-on-breaking"})).isEqualTo(3);
    }

    @Test
    void diffWithFailOnBreakingReturnsSuccessWhenThereAreNoBreakingChanges(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("base");
        Path head = tmp.resolve("head");
        var empty = new Graph(List.of(), List.of(), List.of());
        JsonOutput.write(empty, base);
        JsonOutput.write(empty, head);

        assertThat(Main.run(new String[]{"diff", "--base", base.toString(), "--head", head.toString(), "--fail-on-breaking"}))
                .isZero();
    }

    @Test
    void failOnBreakingWithAValueIsRejected(@TempDir Path tmp) throws Exception {
        var graphs = breakingGraphs(tmp);

        assertThatThrownBy(() -> Main.run(new String[]{"diff", "--base", graphs.base().toString(), "--head", graphs.head().toString(),
                "--fail-on-breaking", "yes"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--fail-on-breaking takes no value");
    }

    @Test
    void diffAcceptsGraphJsonFilesForBaseAndHead(@TempDir Path tmp) throws Exception {
        var graphs = breakingGraphs(tmp);

        assertThat(Main.run(new String[]{"diff", "--base", graphs.base().resolve("graph.json").toString(),
                "--head", graphs.head().resolve("graph.json").toString(), "--fail-on-breaking"})).isEqualTo(3);
    }

    private static GraphPaths breakingGraphs(Path tmp) throws Exception {
        Path base = tmp.resolve("base");
        Path head = tmp.resolve("head");
        var orders = new Node.Service(Node.Service.idOf("orders"), "orders", "orders", ".");
        var gateway = new Node.Service(Node.Service.idOf("gateway"), "gateway", "gateway", ".");
        var endpoint = new Node.Endpoint(Node.Endpoint.idOf("orders", "GET", "/orders"), "orders", "GET", "/orders");
        var call = new Edge(Edge.Kind.CALLS, gateway.id(), endpoint.id(), Confidence.EXACT, new TreeMap<>(),
                List.of(new Evidence("gateway", "Client.java", 1)));
        JsonOutput.write(new Graph(List.of(orders, gateway, endpoint), List.of(call), List.of()), base);
        var remaining = new Edge(Edge.Kind.CALLS, gateway.id(), orders.id(), Confidence.EXACT,
                new TreeMap<>(Map.of("method", "GET", "path", "/orders")), call.evidence());
        JsonOutput.write(new Graph(List.of(orders, gateway), List.of(remaining), List.of()), head);
        return new GraphPaths(base, head);
    }

    private record GraphPaths(Path base, Path head) {}
}
