package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.output.DiagramOutput;
import io.github.sunjirapath.ssg.output.JsonOutput;
import io.github.sunjirapath.ssg.output.MarkdownOutput;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every output file for the synthetic fleet must match src/test/resources/golden byte for byte.
 * After an intended change, regenerate with {@code ./gradlew test -PupdateGolden} and review the diff.
 */
class GoldenTest {

    private static final Path GOLDEN = Path.of("src/test/resources/golden");
    // fixed location, so relative evidence links in the Markdown are stable
    private static final Path OUT = Path.of("build/golden-out");

    @Test
    void outputMatchesGoldenFiles() throws IOException {
        var result = TestFleet.scan();
        deleteRecursively(OUT);
        JsonOutput.write(result.graph(), OUT);
        MarkdownOutput.write(result.graph(), result.repoRoots(), OUT);
        DiagramOutput.write(result.graph(), OUT);

        if (Boolean.getBoolean("ssg.updateGolden")) {
            deleteRecursively(GOLDEN);
            for (Path f : files(OUT)) {
                Path target = GOLDEN.resolve(OUT.relativize(f).toString());
                Files.createDirectories(target.getParent());
                Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        assertThat(relative(files(OUT), OUT)).as("output file set").isEqualTo(relative(files(GOLDEN), GOLDEN));
        for (Path f : files(OUT)) {
            Path golden = GOLDEN.resolve(OUT.relativize(f).toString());
            assertThat(Files.readString(f)).as(OUT.relativize(f).toString()).isEqualTo(Files.readString(golden));
        }
    }

    @Test
    void outputIsDeterministic() throws IOException {
        var a = Path.of("build/golden-out-a");
        var b = Path.of("build/golden-out-b");
        for (Path out : List.of(a, b)) {
            deleteRecursively(out);
            var result = Pipeline.scan(new Pipeline.Options(
                    TestFleet.REPOS.stream().map(TestFleet.ROOT::resolve).toList(), "default", null, null));
            JsonOutput.write(result.graph(), out);
        }
        assertThat(Files.readString(a.resolve("graph.json"))).isEqualTo(Files.readString(b.resolve("graph.json")));
    }

    private static List<Path> files(Path dir) throws IOException {
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static List<String> relative(List<Path> files, Path dir) {
        return files.stream().map(f -> dir.relativize(f).toString().replace('\\', '/')).toList();
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
