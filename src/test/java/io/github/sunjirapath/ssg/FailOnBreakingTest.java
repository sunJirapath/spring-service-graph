package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import io.github.sunjirapath.ssg.output.JsonOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailOnBreakingTest {

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
        JsonOutput.write(new Graph(List.of(orders, gateway), List.of(), List.of()), head);
        return new GraphPaths(base, head);
    }

    private record GraphPaths(Path base, Path head) {}
}
