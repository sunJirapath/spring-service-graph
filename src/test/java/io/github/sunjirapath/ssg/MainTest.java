package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import io.github.sunjirapath.ssg.output.JsonOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainTest {

    @Test
    void noArgumentsPrintUsageAndReturnSuccess() throws Exception {
        var result = captureStdout(() -> Main.run(new String[0]));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("usage: ssg scan", "ssg diff", "ssg mcp");
    }

    @Test
    void helpPrintsUsageAndReturnsSuccess() throws Exception {
        var result = captureStdout(() -> Main.run(new String[]{"--help"}));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).startsWith("usage: ssg scan");
    }

    @Test
    void unknownCommandThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> Main.run(new String[]{"deploy"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("unknown command: deploy");
    }

    @Test
    void scanWithoutReposThrows(@TempDir Path tmp) {
        assertThatThrownBy(() -> Main.run(new String[]{"scan", "--out", tmp.resolve("out").toString()}))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("--repos is required");
    }

    @Test
    void scanWithoutOutThrows(@TempDir Path tmp) {
        assertThatThrownBy(() -> Main.run(new String[]{"scan", "--repos", tmp.toString()}))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("--out is required");
    }

    @Test
    void repeatedSingleValuedOptionThrows(@TempDir Path tmp) {
        assertThatThrownBy(() -> Main.run(new String[]{"scan", "--repos", tmp.toString(), "--profile", "dev",
                "--profile", "prod", "--out", tmp.resolve("out").toString()}))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("--profile takes one value");
    }

    @Test
    void unexpectedPositionalArgumentThrows() {
        assertThatThrownBy(() -> Main.run(new String[]{"scan", "unexpected"}))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("unexpected argument: unexpected");
    }

    @Test
    void reposGlobExpandsSortedDirectoriesOnly(@TempDir Path tmp) throws IOException {
        Path z = Files.createDirectory(tmp.resolve("z-repo"));
        Path a = Files.createDirectory(tmp.resolve("a-repo"));
        Files.writeString(tmp.resolve("file-repo"), "not a directory");

        // a glob cannot go through Path.of on Windows, so build the argument as a string
        assertThat(Main.expand(tmp + java.io.File.separator + "*-repo")).containsExactly(a, z);
    }

    @Test
    void reposGlobMatchingNothingThrows(@TempDir Path tmp) {
        assertThatThrownBy(() -> Main.run(new String[]{"scan", "--repos", tmp + java.io.File.separator + "missing-*",
                "--out", tmp.resolve("out").toString()}))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("--repos matched no directories");
    }

    @Test
    void nonDirectoryReposPathThrows(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("repo.txt"), "x");

        assertThatThrownBy(() -> Main.expand(file.toString())).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a directory: " + file);
    }

    @Test
    void scanWritesJsonMarkdownServicePagesAndDiagrams(@TempDir Path tmp) throws Exception {
        TempRepo repo = TempRepo.at(tmp.resolve("orders")).app()
                .resource("application.yml", "spring.application.name: orders\n")
                .java("x/Orders.java", "package x; @RestController class Orders { @GetMapping(\"/orders\") Object get() { return null; } }");
        Path out = tmp.resolve("out");

        assertThat(Main.run(new String[]{"scan", "--repos", repo.root().toString(), "--out", out.toString()})).isZero();
        assertThat(out.resolve("graph.json")).isRegularFile();
        assertThat(out.resolve("index.md")).isRegularFile();
        assertThat(out.resolve("services/orders.md")).isRegularFile();
        assertThat(out.resolve("diagrams/fleet.puml")).isRegularFile();
        assertThat(out.resolve("diagrams/orders.mmd")).isRegularFile();
    }

    @Test
    void profileIsHonouredEndToEnd(@TempDir Path tmp) throws Exception {
        TempRepo repo = TempRepo.at(tmp.resolve("repo")).app()
                .resource("application.yml", "spring.application.name: base-name\n")
                .resource("application-prod.yml", "spring.application.name: prod-name\n");
        Path out = tmp.resolve("out");

        Main.run(new String[]{"scan", "--repos", repo.root().toString(), "--profile", "prod", "--out", out.toString()});

        assertThat(JsonOutput.read(out).nodes()).filteredOn(Node.Service.class::isInstance).extracting(Node::id)
                .containsExactly(Node.Service.idOf("prod-name"));
    }

    @Test
    void aliasesAreHonouredEndToEnd(@TempDir Path tmp) throws Exception {
        TempRepo caller = TempRepo.at(tmp.resolve("caller")).app()
                .resource("application.yml", "spring.application.name: caller\n")
                .java("x/Client.java", """
                        package x;
                        @FeignClient(name="orders-client", url="https://orders.internal")
                        interface Client { @GetMapping("/orders") Object orders(); }
                        """);
        TempRepo orders = TempRepo.at(tmp.resolve("orders")).app()
                .resource("application.yml", "spring.application.name: orders\n")
                .java("x/Orders.java", "package x; @RestController class Orders { @GetMapping(\"/orders\") Object get() { return null; } }");
        Path aliases = Files.writeString(tmp.resolve("aliases.yml"), "orders.internal: orders\n");
        Path out = tmp.resolve("out");

        Main.run(new String[]{"scan", "--repos", caller.root().toString(), orders.root().toString(),
                "--aliases", aliases.toString(), "--out", out.toString()});

        assertThat(JsonOutput.read(out).edges()).filteredOn(e -> e.kind() == Edge.Kind.CALLS)
                .extracting(Edge::to).contains(Node.Endpoint.idOf("orders", "GET", "/orders"));
    }

    @Test
    void configRepoIsHonouredEndToEnd(@TempDir Path tmp) throws Exception {
        TempRepo caller = TempRepo.at(tmp.resolve("caller")).app()
                .resource("application.yml", "spring.application.name: caller\n")
                .java("x/Client.java", """
                        package x;
                        @FeignClient(name="orders-client", url="${orders.url}")
                        interface Client { @GetMapping("/orders") Object orders(); }
                        """);
        TempRepo orders = TempRepo.at(tmp.resolve("orders")).app()
                .resource("application.yml", "spring.application.name: orders\n")
                .java("x/Orders.java", "package x; @RestController class Orders { @GetMapping(\"/orders\") Object get() { return null; } }");
        Path configRepo = Files.createDirectory(tmp.resolve("config-repo"));
        Files.writeString(configRepo.resolve("caller.yml"), "orders.url: http://orders:8080\n");
        Path out = tmp.resolve("out");

        Main.run(new String[]{"scan", "--repos", caller.root().toString(), orders.root().toString(),
                "--config-repo", configRepo.toString(), "--out", out.toString()});

        assertThat(JsonOutput.read(out).edges()).filteredOn(e -> e.kind() == Edge.Kind.CALLS)
                .anySatisfy(e -> {
                    assertThat(e.to()).isEqualTo(Node.Endpoint.idOf("orders", "GET", "/orders"));
                    assertThat(e.confidence()).isEqualTo(Confidence.RESOLVED);
                    assertThat(e.evidence()).anyMatch(ev -> ev.repo().equals("config-repo"));
                });
    }

    @Test
    void diffAcceptsOutputDirectoriesAndReturnsSuccess(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("base");
        Path head = tmp.resolve("head");
        JsonOutput.write(new Graph(List.of(), List.of(), List.of()), base);
        JsonOutput.write(new Graph(List.of(), List.of(), List.of()), head);

        assertThat(Main.run(new String[]{"diff", "--base", base.toString(), "--head", head.toString()})).isZero();
    }

    private static Captured captureStdout(ThrowingIntSupplier action) throws Exception {
        PrintStream original = System.out;
        var bytes = new ByteArrayOutputStream();
        try (var replacement = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(replacement);
            return new Captured(action.getAsInt(), bytes.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(original);
        }
    }

    private record Captured(int exitCode, String stdout) {}

    @FunctionalInterface
    private interface ThrowingIntSupplier {
        int getAsInt() throws Exception;
    }
}
