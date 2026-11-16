package io.github.sunjirapath.ssg.real;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sunjirapath.ssg.model.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import java.io.StringReader;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;

class ScannerRealProjectTest {
    @TempDir(factory = io.github.sunjirapath.ssg.WorkspaceTempDirFactory.class) Path tmp;
    private static boolean supportedProjectSpotCheckSeen;
    private static boolean literalMappingSpotCheckPassed;

    @AfterAll
    static void atLeastOneSupportedProjectHasALiteralMappingSpotCheck() {
        Assumptions.assumeTrue(supportedProjectSpotCheckSeen, "no supported real projects were available for spot checks");
        assertThat(literalMappingSpotCheckPassed).as("at least one supported project has a literal mapping").isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.github.sunjirapath.ssg.real.RealProjects#projects")
    void scanIsDeterministicHasRealEvidenceAndDoesNotWriteIntoCheckout(RealProjects.Project project) throws Exception {
        Path copy = RealProjects.copy(project, tmp), out = tmp.resolve("out"), process = tmp.resolve("process");
        var original = RealProjects.tree(project.path());
        var before = RealProjects.tree(copy);
        var copyPaths = RealProjects.paths(copy);
        Files.createDirectories(process);
        for (String file : List.of("stdin.jsonl", "stdout.log", "stderr.log")) Files.writeString(process.resolve(file), "");
        Set<Path> outside = outsideOut(out);
        Graph graph = RealProjects.scan(copy, out, process);
        var first = RealProjects.output(out);
        RealProjects.scan(copy, out, process);
        var second = RealProjects.output(out);
        assertThat(second.keySet()).containsExactlyElementsOf(first.keySet());
        for (Path file : first.keySet()) assertThat(second.get(file)).as("deterministic %s", file).isEqualTo(first.get(file));
        assertThat(first.keySet()).contains(Path.of("graph.json"), Path.of("index.md"));
        assertThat(RealProjects.tree(copy)).isEqualTo(before);
        assertThat(RealProjects.paths(copy)).isEqualTo(copyPaths);
        assertThat(RealProjects.tree(project.path())).isEqualTo(original);
        assertThat(outsideOut(out)).isEqualTo(outside);
        evidence(graph, copy);
        noSecretIdentities(graph, copy);
        if (project.unsupported()) {
            // FINDING: unsupported Boot 2.x must be explicit; current discovery has no version gate.
            assertThat(graph.findings()).anyMatch(f -> f.kind() == Finding.Kind.INCOMPLETE_ANALYSIS
                    && f.message().toLowerCase(Locale.ROOT).contains("boot 2"));
            var gate = RealProjects.cli(process, "", "diff", "--base", out.toString(), "--head", out.toString(), "--fail-on-breaking");
            assertThat(gate.code()).isEqualTo(4);
        } else {
            assertThat(graph.nodes()).anyMatch(Node.Service.class::isInstance).anyMatch(Node.Endpoint.class::isInstance);
            supportedProjectSpotCheckSeen = true;
            literalMappingSpotCheck(graph, copy);
        }
    }

    @ParameterizedTest(name = "MCP {0}")
    @MethodSource("io.github.sunjirapath.ssg.real.RealProjects#projects")
    void mcpInitializeListAndQueryUseOnlyProducedSnapshot(RealProjects.Project project) throws Exception {
        Path copy = RealProjects.copy(project, tmp), out = tmp.resolve("out"), process = tmp.resolve("process");
        Graph graph = RealProjects.scan(copy, out, process);
        String target = graph.nodes().stream().filter(Node.Service.class::isInstance).map(Node::id).findFirst().orElse("missing");
        var json = new ObjectMapper();
        String request = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"who_calls\",\"arguments\":{\"target\":"
                + json.writeValueAsString(target) + "}}}\n";
        var result = RealProjects.cli(process, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """ + request, "mcp", "--graph", out.toString());
        assertThat(result.code()).isZero();
        var lines = result.stdout().lines().toList();
        assertThat(lines).hasSize(3);
        for (int i = 0; i < lines.size(); i++) {
            assertThat(lines.get(i).length()).isLessThan(150_000);
            var reply = json.readTree(lines.get(i));
            assertThat(reply.path("jsonrpc").asText()).isEqualTo("2.0");
            assertThat(reply.path("id").asInt()).isEqualTo(i + 1);
            assertThat(reply.has("result")).isTrue();
        }
        assertThat(json.readTree(lines.get(0)).at("/result/serverInfo/name").asText()).isEqualTo("spring-service-graph");
        assertThat(json.readTree(lines.get(1)).at("/result/tools").size()).isPositive();
        assertThat(json.readTree(lines.get(2)).at("/result/content/0/text").asText()).isNotBlank();
    }

    private Set<Path> outsideOut(Path out) throws Exception {
        var paths = RealProjects.paths(tmp);
        paths.removeIf(p -> tmp.resolve(p).startsWith(out));
        return paths;
    }

    private static void evidence(Graph graph, Path copy) throws Exception {
        for (Edge edge : graph.edges()) {
            assertThat(edge.evidence()).as("edge has provenance").isNotEmpty();
            for (Evidence e : edge.evidence()) {
                assertThat(e.repo()).isEqualTo(copy.getFileName().toString());
                Path file = copy.resolve(e.file()).normalize();
                assertThat(file.startsWith(copy)).isTrue();
                assertThat(file).isRegularFile();
                assertThat(file.toRealPath().startsWith(copy.toRealPath())).isTrue();
                int lines = Files.readAllLines(file).size();
                assertThat(e.line()).as("%s:%s", e.file(), e.line()).isBetween(1, lines);
            }
        }
    }

    private static void literalMappingSpotCheck(Graph graph, Path copy) throws Exception {
        var mapping = Pattern.compile("@GetMapping\\s*\\(\\s*(?:(?:value|path)\\s*=\\s*)?\"([^\"]+)\"");
        for (Edge edge : graph.edges()) if (edge.kind() == Edge.Kind.EXPOSES) {
            var endpoint = graph.nodes().stream().filter(n -> n.id().equals(edge.to())).filter(Node.Endpoint.class::isInstance)
                    .map(Node.Endpoint.class::cast).findFirst().orElseThrow();
            for (Evidence e : edge.evidence()) if (e.file().endsWith(".java")) {
                String line = Files.readAllLines(copy.resolve(e.file())).get(e.line() - 1);
                var match = mapping.matcher(line);
                if (match.find() && !match.group(1).contains("${")) {
                    assertThat(endpoint.path()).endsWith(match.group(1));
                    literalMappingSpotCheckPassed = true;
                    return; // Literal is quoted on the reported line, independently of scanner AST positions.
                }
            }
        }
        Assumptions.assumeTrue(false, copy.getFileName() + " has no literal @GetMapping; spot check not applicable");
    }

    private static void noSecretIdentities(Graph graph, Path copy) throws Exception {
        List<String> identities = graph.nodes().stream().map(n -> switch (n) {
            case Node.Endpoint e -> e.path();
            case Node.Topic t -> t.name();
            case Node.Table t -> t.name();
            default -> "";
        }).toList();
        for (Path relative : RealProjects.tree(copy).keySet()) {
            String name = relative.getFileName().toString();
            if (!name.matches("application.*\\.(ya?ml|properties)")) continue;
            var candidates = new HashSet<String>();
            String text = Files.readString(copy.resolve(relative));
            if (name.endsWith(".properties")) {
                var props = new Properties();
                props.load(new StringReader(text));
                props.forEach((key, value) -> { if (secretKey(key.toString())) candidates.add(value.toString()); });
            } else for (org.yaml.snakeyaml.nodes.Node node : io.github.sunjirapath.ssg.scan.Config.composeAll(new StringReader(text)))
                secrets(node, "", Collections.newSetFromMap(new IdentityHashMap<>()), candidates);
            for (String candidate : candidates) {
                String value = candidate.strip();
                if (value.length() < 4 || value.startsWith("${") || value.startsWith("#{")) continue;
                // Boolean assertion prevents a real config secret from appearing in failure diagnostics.
                assertThat(identities.stream().noneMatch(s -> s.contains(value)))
                        .as("config secret appeared in endpoint/topic/table identity from %s", relative).isTrue();
            }
        }
    }

    private static boolean secretKey(String key) { return key.toLowerCase(Locale.ROOT).matches(".*(password|secret|token|key).*"); }

    private static void secrets(org.yaml.snakeyaml.nodes.Node node, String key, Set<org.yaml.snakeyaml.nodes.Node> seen, Set<String> out) {
        if (node == null || !seen.add(node)) return;
        try {
            if (node instanceof ScalarNode scalar && secretKey(key)) out.add(scalar.getValue());
            else if (node instanceof MappingNode map) for (NodeTuple t : map.getValue()) {
                if (t.getKeyNode() instanceof ScalarNode k) secrets(t.getValueNode(), key + "." + k.getValue(), seen, out);
            } else if (node instanceof SequenceNode seq) seq.getValue().forEach(n -> secrets(n, key, seen, out));
        } finally { seen.remove(node); }
    }
}
