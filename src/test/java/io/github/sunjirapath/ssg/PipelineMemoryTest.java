package io.github.sunjirapath.ssg;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Finding;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineMemoryTest {
    @TempDir Path dir;

    @Test
    void thirtyReposFitWithoutRetainingPriorIndexes() throws Exception {
        var repos = new ArrayList<String>();
        for (int r = 0; r < 30; r++) {
            var repo = TempRepo.at(dir.resolve("repo-" + r)).app();
            // Unused POJOs exercise syntax-tree retention without growing the graph.
            for (int c = 0; c < 150; c++) {
                var source = new StringBuilder("package app; class Data" + c + " {\n");
                for (int f = 0; f < 100; f++)
                    source.append("String field").append(f).append(" = \"value-").append(r).append('-')
                            .append(c).append('-').append(f).append("\";\n");
                repo.java("app/Data" + c + ".java", source.append("}\n").toString());
            }
            repos.add(repo.root().toString());
        }
        // Gradle workers may put only their bootstrap jar in java.class.path.
        var classpath = new ArrayList<String>();
        for (Class<?> type : List.of(Main.class, JavaParser.class, Yaml.class,
                ObjectMapper.class, JsonFactory.class, JsonIgnore.class))
            classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path out = dir.resolve("out");
        var command = new ArrayList<>(List.of(java.toString(), "-Xmx128m", "-cp",
                String.join(File.pathSeparator, classpath), Main.class.getName(),
                "scan", "--out", out.toString(), "--repos"));
        command.addAll(repos);
        Path log = dir.resolve("scan.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(180, TimeUnit.SECONDS)).as("bounded-heap fleet scan completed").isTrue();
            assertThat(process.exitValue()).withFailMessage("bounded-heap scan failed: %s", Files.readString(log)).isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
        var graph = JsonOutput.read(out);
        assertThat(graph.nodes()).filteredOn(Node.Service.class::isInstance).extracting(Node::id)
                .containsExactlyInAnyOrderElementsOf(IntStream.range(0, 30).mapToObj(r -> "service:repo-" + r).toList());
        assertThat(graph.findings()).isEmpty();
    }

    @Test
    void duplicateDiagnosticsKeepPreviousLocationAndEvidenceAcrossThreeRepos() {
        var repos = IntStream.range(0, 3).mapToObj(r -> TempRepo.at(dir.resolve("repo-" + r)).app()
                .resource("application.properties", "spring.application.name=shared\n")).toArray(TempRepo[]::new);
        var duplicates = TempRepo.scan(repos).findings().stream()
                .filter(f -> f.kind() == Finding.Kind.DUPLICATE_SERVICE).toList();
        assertThat(duplicates).extracting(Finding::message).containsExactly(
                "service name 'shared' is used by repo-0/. and repo-1/.",
                "service name 'shared' is used by repo-1/. and repo-2/.");
        assertThat(duplicates).extracting(f -> f.evidence().stream().map(Evidence::repo).toList())
                .containsExactly(List.of("repo-0", "repo-1"), List.of("repo-1", "repo-2"));
        assertThat(duplicates).allSatisfy(f -> assertThat(f.evidence()).extracting(Evidence::file)
                .containsOnly("src/main/resources/application.properties"));
    }
}
