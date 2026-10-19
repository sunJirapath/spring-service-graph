package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.output.JsonOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainCoverageTest {

    @Test
    void shortHelpFlagPrintsUsage() throws Exception {
        var captured = captureStdout(() -> Main.run(new String[]{"-h"}));

        assertThat(captured.code()).isZero();
        assertThat(captured.text()).contains("usage: ssg scan");
    }

    @Test
    void emptyReposOptionIsRejected(@TempDir Path tmp) {
        assertThatThrownBy(() -> Main.run(new String[]{"scan", "--repos", "--out", tmp.resolve("out").toString()}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--repos is required");
    }

    @Test
    void emptyOutOptionIsRejected(@TempDir Path tmp) {
        assertThatThrownBy(() -> Main.run(new String[]{"scan", "--repos", tmp.toString(), "--out"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--out is required");
    }

    @Test
    void emptyOptionalProfileUsesDefault(@TempDir Path tmp) throws Exception {
        var repo = TempRepo.at(tmp.resolve("repo")).app()
                .resource("application.yml", "spring.application.name: base-name\n")
                .resource("application-prod.yml", "spring.application.name: prod-name\n");
        Path out = tmp.resolve("out");

        Main.run(new String[]{"scan", "--repos", repo.root().toString(), "--profile", "--out", out.toString()});

        assertThat(JsonOutput.read(out).nodes()).extracting(Object::toString)
                .anySatisfy(node -> assertThat(node).contains("base-name"));
    }

    @Test
    void mcpCommandAcceptsEndOfInputAndReturnsSuccess(@TempDir Path tmp) throws Exception {
        Path graphDir = tmp.resolve("graph");
        JsonOutput.write(new Graph(List.of(), List.of(), List.of()), graphDir);
        InputStream original = System.in;
        try {
            System.setIn(new ByteArrayInputStream(new byte[0]));
            assertThat(Main.run(new String[]{"mcp", "--graph", graphDir.toString()})).isZero();
        } finally {
            System.setIn(original);
        }
    }

    @Test
    void globWithoutParentUsesCurrentDirectory() throws Exception {
        assertThat(Main.expand("src*")).anySatisfy(path ->
                assertThat(path.getFileName().toString()).isEqualTo("src"));
    }

    @Test
    void rootRelativeGlobWithNoMatchesReturnsEmptyList() throws Exception {
        assertThat(Main.expand("\\ssg-definitely-not-present-*" )).isEmpty();
    }

    @Test
    void pipelineWarnsForRepoWithoutApplication(@TempDir Path tmp) throws Exception {
        Path repo = TempRepo.at(tmp.resolve("library")).java("x/Library.java", "package x; class Library {}").root();

        var captured = captureStderr(() -> Pipeline.scan(new Pipeline.Options(List.of(repo), "default", null, null)));

        assertThat(captured).contains("warn: no Spring Boot app", repo.toString());
    }

    @Test
    void pipelineWarnsWhenServiceNameOccursInTwoRepos(@TempDir Path tmp) throws Exception {
        var first = TempRepo.at(tmp.resolve("one")).app()
                .resource("application.properties", "spring.application.name=duplicate\n");
        var second = TempRepo.at(tmp.resolve("two")).app()
                .resource("application.properties", "spring.application.name=duplicate\n");

        var captured = captureStderr(() -> Pipeline.scan(new Pipeline.Options(
                List.of(first.root(), second.root()), "default", null, null)));

        assertThat(captured).contains("warn: service name 'duplicate' found twice", "one/.", "two/.");
    }

    @Test
    void emptyAliasesDocumentProducesNoAliases(@TempDir Path tmp) throws Exception {
        Path aliases = Files.writeString(tmp.resolve("aliases.yml"), "");

        assertThat(Pipeline.aliases(aliases)).isEmpty();
    }

    @Test
    void scalarAliasesDocumentIsRejected(@TempDir Path tmp) throws Exception {
        Path aliases = Files.writeString(tmp.resolve("aliases.yml"), "orders\n");

        assertThatThrownBy(() -> Pipeline.aliases(aliases))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected a map of host: target");
    }

    private static Captured captureStdout(ThrowingIntSupplier action) throws Exception {
        PrintStream original = System.out;
        var bytes = new ByteArrayOutputStream();
        try (var replacement = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(replacement);
            return new Captured(action.get(), bytes.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(original);
        }
    }

    private static String captureStderr(ThrowingAction action) throws Exception {
        PrintStream original = System.err;
        var bytes = new ByteArrayOutputStream();
        try (var replacement = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(replacement);
            action.run();
            return bytes.toString(StandardCharsets.UTF_8);
        } finally {
            System.setErr(original);
        }
    }

    private record Captured(int code, String text) {}

    @FunctionalInterface
    private interface ThrowingIntSupplier { int get() throws Exception; }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }
}
