package io.github.sunjirapath.ssg;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import io.github.sunjirapath.ssg.output.JsonOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MainProcessTest {
    @TempDir Path tmp;

    @Test
    void mainTerminatesWithDocumentedHelpArgumentAndIoStatuses() throws Exception {
        assertThat(launch("--help")).isZero();
        assertThat(launch("diff", "--unknown")).isEqualTo(2);
        assertThat(launch("mcp", "--graph", tmp.resolve("missing.json").toString())).isEqualTo(1);
    }

    @Test
    void mainPreservesCleanBreakingAndIncompleteGateStatuses() throws Exception {
        Path base = tmp.resolve("base");
        Path head = tmp.resolve("head");
        var orders = new Node.Service("service:orders", "orders", "orders", ".");
        var gateway = new Node.Service("service:gateway", "gateway", "gateway", ".");
        var endpoint = new Node.Endpoint(Node.Endpoint.idOf("orders", "GET", "/orders"), "orders", "GET", "/orders");
        var evidence = List.of(new Evidence("gateway", "Client.java", 1));
        var call = new Edge(Edge.Kind.CALLS, gateway.id(), endpoint.id(), Confidence.EXACT, new TreeMap<>(), evidence);
        Graph original = new Graph(List.of(orders, gateway, endpoint), List.of(call), List.of());
        JsonOutput.write(original, base);
        JsonOutput.write(original, head);
        String[] args = {"diff", "--base", base.toString(), "--head", head.toString(), "--fail-on-breaking"};
        assertThat(launch(args)).isZero();

        var survivingCall = new Edge(Edge.Kind.CALLS, gateway.id(), orders.id(), Confidence.EXACT,
                new TreeMap<>(Map.of("method", "GET", "path", "/orders")), evidence);
        var remaining = new Graph(List.of(orders, gateway), List.of(survivingCall), List.of());
        JsonOutput.write(remaining, head);
        assertThat(launch(args)).isEqualTo(3);
        assertThat(Files.readString(tmp.resolve("process.log"))).contains("Breaking changes", "/orders");

        JsonOutput.write(new Graph(remaining.nodes(), remaining.edges(), List.of(new Finding(
                Finding.Kind.PARSE_ERROR, "gateway", "failed source", evidence))), head);
        assertThat(launch(args)).isEqualTo(4);
        assertThat(Files.readString(tmp.resolve("process.log"))).contains("Incomplete analysis");
    }

    private int launch(String... args) throws Exception {
        // Gradle workers may expose only their bootstrap jar through java.class.path.
        var classpath = new ArrayList<String>();
        for (Class<?> type : List.of(Main.class, JavaParser.class, Yaml.class,
                ObjectMapper.class, JsonFactory.class, JsonIgnore.class))
            classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var command = new ArrayList<>(List.of(java.toString(), "-cp", String.join(File.pathSeparator, classpath),
                Main.class.getName()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(tmp.resolve("process.log").toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("CLI process terminated").isTrue();
            return process.exitValue();
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
