package io.github.sunjirapath.ssg;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentationTest {
    @Test
    void configRepoDocumentsAppSubfoldersAndSameRepoServerSearchPaths() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        assertThat(readme).contains("root and `<app>/` subfolder", "Config Server in the same scanned repo",
                        "`git.search-paths`", "`native.search-locations`", "files must stay inside the config checkout")
                .doesNotContain("Only the repo root is read", "only the repo root is read");
    }

    @Test
    void foldingDocsDistinguishReassignmentsFromParameterOverrides() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        assertThat(readme).contains("differing non-final field assignments within the declaring class as `UNRESOLVED`",
                        "Straight-line local reassignments fold the last assignment before each use",
                        "differing branch/loop reassignments stay `UNRESOLVED`",
                        "Constructor/setter assignments directly from parameters are ignored",
                        "retaining the initializer as a static guess")
                .doesNotContain("A non-final field or local variable that is reassigned later still folds to its initializer");
    }

    @Test
    void forkDocsRequireFleetAccessBeforePromisingGateResults() throws Exception {
        String ci = Files.readString(Path.of("docs/ci.md"));
        assertThat(ci).contains("do not receive `FLEET_READ_TOKEN`", "private-fleet checkouts fail before scanning",
                        "no impact summary or gate result is produced", "comment: 'false'",
                        "A reduced fleet does not establish impact on omitted private services")
                .doesNotContain("The job summary and the gate still work");
    }
}
