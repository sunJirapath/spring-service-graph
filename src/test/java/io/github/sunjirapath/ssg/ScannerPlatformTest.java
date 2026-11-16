package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Node;
import io.github.sunjirapath.ssg.output.DiagramOutput;
import io.github.sunjirapath.ssg.output.MarkdownOutput;
import io.github.sunjirapath.ssg.scan.Repo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScannerPlatformTest {
    @TempDir(factory = WorkspaceTempDirFactory.class) Path tmp;

    @Test
    void relativeAbsoluteAndDotDotRootsHaveIdenticalGraphs() throws Exception {
        var repo = fixture(tmp.resolve("checkout with spaces"));
        Path absolute = repo.root().toAbsolutePath().normalize();
        Files.createDirectory(absolute.resolve("child"));
        Path relative = Path.of("").toAbsolutePath().relativize(absolute);
        var expected = TempRepo.scan(repo);
        assertThat(expected.nodes()).extracting(Node::id).contains("endpoint:orders:GET /สินค้า");
        for (Path input : List.of(relative, absolute, absolute.resolve("child/.."), relative.resolve("./"))) {
            assertThat(Repo.of(input).root()).isEqualTo(absolute);
            assertThat(Pipeline.scan(new Pipeline.Options(List.of(input), "default", null, null)).graph()).isEqualTo(expected);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsAndPosixRootSeparatorsProduceIdenticalForwardSlashEvidence() throws Exception {
        var repo = fixture(tmp.resolve("checkout with spaces"));
        String nativeRoot = repo.root().toAbsolutePath().toString();
        var expected = TempRepo.scan(repo);
        for (String input : List.of(nativeRoot.replace('\\', '/'), nativeRoot.replace('/', '\\'))) {
            var graph = Pipeline.scan(new Pipeline.Options(List.of(Path.of(input)), "default", null, null)).graph();
            assertThat(graph).isEqualTo(expected);
            assertThat(graph.edges()).flatExtracting(Edge::evidence).allSatisfy(e ->
                    assertThat(e.file()).startsWith("src/").doesNotContain("\\"));
        }
    }

    @Test
    void sourcePathsOverTwoHundredCharactersKeepExactEvidence() {
        var repo = TempRepo.at(tmp.resolve("long checkout with spaces")).app();
        String relative = "src/main/java/app/สินค้าController.java";
        while (repo.root().resolve(relative).toString().length() <= 200) relative = relative.replace("app/", "app/another-segment/");
        repo.file(relative, "package app; @RestController class สินค้าController { @GetMapping(\"/สินค้า\") void get() {} }");
        var graph = TempRepo.scan(repo);
        assertThat(graph.findings()).isEmpty();
        assertThat(graph.nodes()).extracting(Node::id).contains("endpoint:long checkout with spaces:GET /สินค้า");
        String expected = relative;
        assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.EXPOSES).singleElement()
                .satisfies(e -> assertThat(e.evidence()).anySatisfy(v -> assertThat(v.file()).isEqualTo(expected)));
    }

    @Test
    void caseOnlyServiceNamesKeepDistinctPagesAndDiagramLinks() throws Exception {
        var upper = TempRepo.at(tmp.resolve("upper")).app().resource("application.properties", "spring.application.name=Orders\n");
        var lower = TempRepo.at(tmp.resolve("lower")).app().resource("application.properties", "spring.application.name=orders\n");
        var graph = TempRepo.scan(upper, lower);
        assertThat(graph.nodes()).extracting(Node::id).containsExactly("service:Orders", "service:orders");
        assertThat(graph.findings()).isEmpty();
        Path out = tmp.resolve("report with spaces");
        MarkdownOutput.write(graph, Map.of(), out);
        DiagramOutput.write(graph, out);
        assertThat(Files.readString(out.resolve("index.md")))
                .contains("[Orders](services/Orders.md)", "[orders](services/orders-2.md)");
        try (var files = Files.list(out.resolve("services"))) {
            assertThat(files.map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .containsExactlyInAnyOrder("orders.md", "orders-2.md");
        }
        assertThat(Files.readString(out.resolve("services/Orders.md"))).contains("Orders");
        assertThat(Files.readString(out.resolve("services/orders-2.md"))).contains("orders");
        assertThat(out.resolve("diagrams/Orders.mmd")).isRegularFile();
        assertThat(out.resolve("diagrams/orders-2.mmd")).isRegularFile();
    }

    private static TempRepo fixture(Path root) {
        return TempRepo.at(root).app().resource("application.properties", "spring.application.name=orders\n")
                .java("app/Api.java", "package app; @RestController class Api { @GetMapping(\"/สินค้า\") void get() {} }");
    }
}
