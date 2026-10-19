package io.github.sunjirapath.ssg.output;

import io.github.sunjirapath.ssg.Main;
import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class LinkBaseTest {

    @Test
    void linkBaseReplacesRepoFileAndLineAndEncodesSpaces(@TempDir Path tmp) throws Exception {
        MarkdownOutput.write(graph("src/main/java/my package/Orders.java", 17), Map.of(), tmp,
                "https://git.example/{repo}/blob/main/{file}#L{line}");

        assertThat(Files.readString(tmp.resolve("services/orders.md")))
                .contains("https://git.example/orders-repo/blob/main/src/main/java/my%20package/Orders.java#L17");
    }

    @Test
    void nullLinkBaseKeepsLocalRelativeEvidenceLinks(@TempDir Path tmp) throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("orders-repo"));
        Path out = tmp.resolve("out");

        MarkdownOutput.write(graph("src/main/java/Orders.java", 4), Map.of("orders-repo", repo), out, null);

        assertThat(Files.readString(out.resolve("services/orders.md")))
                .contains("../../orders-repo/src/main/java/Orders.java#L4");
    }

    @Test
    void blankLinkBaseKeepsLocalRelativeEvidenceLinks(@TempDir Path tmp) throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("orders-repo"));
        Path out = tmp.resolve("out");

        MarkdownOutput.write(graph("src/main/java/Orders.java", 4), Map.of("orders-repo", repo), out, "  \t");

        assertThat(Files.readString(out.resolve("services/orders.md")))
                .contains("../../orders-repo/src/main/java/Orders.java#L4");
    }

    @Test
    void servicePageRemainsPlainTextWithoutLinks() {
        String page = MarkdownOutput.servicePage(graph("src/main/java/Orders.java", 9), "orders");

        assertThat(page).contains("`orders-repo/src/main/java/Orders.java:9`").doesNotContain("](");
    }

    @Test
    void mainScanPassesLinkBaseToMarkdownOutput(@TempDir Path tmp) throws Exception {
        var repo = TempRepo.at(tmp.resolve("orders-repo")).app()
                .resource("application.properties", "spring.application.name=orders\n")
                .java("demo/Orders.java", """
                        package demo;
                        @RestController class Orders {
                            @GetMapping("/orders") Object get() { return null; }
                        }
                        """);
        Path out = tmp.resolve("out");

        assertThat(runMain("scan", "--repos", repo.root().toString(), "--out", out.toString(),
                "--link-base", "https://source.example/{repo}/{file}?line={line}")).isZero();

        assertThat(Files.readString(out.resolve("services/orders.md")))
                .contains("https://source.example/orders-repo/src/main/java/demo/Orders.java?line=3");
    }

    private static int runMain(String... args) throws Exception {
        Method run = Main.class.getDeclaredMethod("run", String[].class);
        run.setAccessible(true);
        return (int) run.invoke(null, (Object) args);
    }

    private static Graph graph(String file, int line) {
        var service = new Node.Service(Node.Service.idOf("orders"), "orders", "orders-repo", ".");
        var endpoint = new Node.Endpoint(Node.Endpoint.idOf("orders", "GET", "/orders"), "orders", "GET", "/orders");
        var attrs = new TreeMap<String, String>();
        attrs.put("source", "controller");
        var exposes = new Edge(Edge.Kind.EXPOSES, service.id(), endpoint.id(), Confidence.EXACT, attrs,
                List.of(new Evidence("orders-repo", file, line)));
        return new Graph(List.of(service, endpoint), List.of(exposes), List.of());
    }
}
