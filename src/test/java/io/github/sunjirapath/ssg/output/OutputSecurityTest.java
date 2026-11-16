package io.github.sunjirapath.ssg.output;

import io.github.sunjirapath.ssg.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import static io.github.sunjirapath.ssg.TestFiles.symlink;
import static org.assertj.core.api.Assertions.*;

class OutputSecurityTest {
    @TempDir(factory = io.github.sunjirapath.ssg.WorkspaceTempDirFactory.class) Path tmp;

    @Test
    void outputLeafSymlinkAttackCannotOverwriteOutsideFile() throws Exception {
        // Kills Files.writeString following a pre-existing report symlink.
        Path outside = Files.writeString(tmp.resolve("canary"), "KEEP");
        Path out = Files.createDirectory(tmp.resolve("out"));
        symlink(out.resolve("graph.json"), outside);
        assertThatThrownBy(() -> JsonOutput.write(graph("orders"), out)).isInstanceOf(IOException.class);
        assertThat(Files.readString(outside)).isEqualTo("KEEP");
    }

    @Test
    void outputDirectorySymlinkAttackCannotWriteOutsideOut() throws Exception {
        // Kills checking only the leaf rather than its linked ancestor.
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Path out = symlink(tmp.resolve("out"), outside);
        assertThatThrownBy(() -> JsonOutput.write(graph("orders"), out)).isInstanceOf(IOException.class);
        try (var files = Files.list(outside)) { assertThat(files).isEmpty(); }
    }

    @Test
    void outputHardLinkAttackCannotTruncateOutsideFile() throws Exception {
        // Kills replacing the atomic rename with in-place truncation of a multiply linked inode.
        Path outside = Files.writeString(tmp.resolve("canary"), "KEEP");
        Path out = Files.createDirectory(tmp.resolve("out"));
        Files.createLink(out.resolve("graph.json"), outside);
        JsonOutput.write(graph("orders"), out);
        assertThat(Files.readString(outside)).isEqualTo("KEEP");
        assertThat(JsonOutput.read(out).nodes()).hasSize(1);
    }

    @Test
    void traversalAndWindowsDeviceNameAttackStaysUnderOutput() throws Exception {
        // Kills removing GraphView basename sanitization or Windows device-name allocation.
        var a = graph("../../escape").nodes().getFirst();
        var b = graph("CON").nodes().getFirst();
        MarkdownOutput.write(new Graph(List.of(a, b), List.of(), List.of()), Map.of(), tmp.resolve("out"));
        try (var files = Files.list(tmp.resolve("out/services"))) {
            assertThat(files.map(p -> p.getFileName().toString()).toList())
                    .contains("CON-2.md").noneMatch(n -> n.contains("/") || n.contains("\\"));
        }
        assertThat(tmp.resolve("escape.md")).doesNotExist();
    }

    @Test
    void javascriptAndControlCharacterLinkBaseAttacksAreRejected() {
        // Kills removing the link-base scheme/control allowlist before rendering links.
        for (String url : List.of("javascript:alert(1)", "data:text/html,x", "https://host/\n::error::x"))
            assertThatThrownBy(() -> MarkdownOutput.write(graph("orders"), Map.of(), tmp, url))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markdownAndEvidenceLinkInjectionAttackCannotEscapeCells() throws Exception {
        // Kills raw service interpolation, raw code fences, or unencoded evidence-link delimiters.
        String name = "orders\n::error::bad\u001b[31m<script>](javascript:bad)`";
        var service = (Node.Service) graph(name).nodes().getFirst();
        var ep = new Node.Endpoint(Node.Endpoint.idOf(name, "GET", "/`<img>\n|x"), name, "GET", "/`<img>\n|x");
        var edge = new Edge(Edge.Kind.EXPOSES, service.id(), ep.id(), Confidence.EXACT,
                new TreeMap<>(), List.of(new Evidence("repo", "a)\n::error::x.java", 1)));
        var graph = new Graph(List.of(service, ep), List.of(edge), List.of());
        MarkdownOutput.write(graph, Map.of(), tmp, "https://host/{file}#L{line}");
        String index = Files.readString(tmp.resolve("index.md"));
        assertThat(index).doesNotContain("\n::error::", "\u001b", "<script>", "](javascript:")
                .contains("&#10;");
        try (var pages = Files.list(tmp.resolve("services"))) {
            assertThat(Files.readString(pages.findFirst().orElseThrow())).contains("%29%0A");
        }
        assertThat(MarkdownOutput.servicePage(graph, name)).doesNotContain("<img>", "\n|x");
    }

    @Test
    void ordinaryMarkdownFormattingAndDiagramPathCharactersArePreserved() throws Exception {
        String name = "orders_blue";
        var service = graph(name).nodes().getFirst();
        String path = "/account-api/{id}/**";
        var ep = new Node.Endpoint(Node.Endpoint.idOf(name, "*", path), name, "*", path);
        var edge = new Edge(Edge.Kind.EXPOSES, service.id(), ep.id(), Confidence.EXACT,
                new TreeMap<>(), List.of());
        var finding = new Finding(Finding.Kind.UNRESOLVED, name, "from `base_url() + \"/**\"`", List.of());
        var graph = new Graph(List.of(service, ep), List.of(edge), List.of(finding));
        MarkdownOutput.write(graph, Map.of(), tmp);
        assertThat(Files.readString(tmp.resolve("index.md")))
                .contains("[orders_blue](services/orders_blue.md)", finding.message());
        assertThat(Files.readString(tmp.resolve("services/orders_blue.md")))
                .contains("| * | `/account-api/{id}/**` |", finding.message());
        String label = "orders_blue/account-api/{id}/**`v2`";
        DiagramOutput.write(graph(label), tmp);
        try (var paths = Files.list(tmp.resolve("diagrams"))) {
            for (Path p : paths.toList()) assertThat(Files.readString(p)).contains(label);
        }
    }

    @Test
    void mermaidAndPlantUmlDirectiveInjectionAttackStaysInOneLabel() throws Exception {
        // Kills allowing line breaks/directive delimiters in quoted diagram labels.
        DiagramOutput.write(graph("orders\n%%{init: bad}\n!include /canary\u001b"), tmp);
        try (var paths = Files.walk(tmp)) {
            for (Path p : paths.filter(Files::isRegularFile).toList())
                assertThat(Files.readString(p)).doesNotContain("\n%%", "\n!include", "\u001b", "%%{init");
        }
    }

    @Test
    void failedAtomicReplacementAttackRetainsExistingTargetAndCleansTemp() throws Exception {
        // Kills deleting/truncating a destination before the atomic move or leaking pending files.
        Path target = Files.createDirectory(tmp.resolve("report"));
        Files.writeString(target.resolve("keep"), "KEEP");
        assertThatThrownBy(() -> TextFile.write(target, "replacement")).isInstanceOf(IOException.class);
        assertThat(Files.readString(target.resolve("keep"))).isEqualTo("KEEP");
        try (var files = Files.list(tmp)) { assertThat(files.map(p -> p.getFileName().toString())).containsExactly("report"); }
    }

    private static Graph graph(String name) {
        return new Graph(List.of(new Node.Service(Node.Service.idOf(name), name, "repo", ".")), List.of(), List.of());
    }
}
