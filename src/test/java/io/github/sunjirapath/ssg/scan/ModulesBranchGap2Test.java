package io.github.sunjirapath.ssg.scan;

import io.github.sunjirapath.ssg.TempRepo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ModulesBranchGap2Test {

    @TempDir
    Path tmp;

    @Test
    void projectAndPomVersionAliasesResolveAndManagedVersionsWalkToParent() {
        var repo = TempRepo.at(tmp)
                .file("pom.xml", """
                        <project><groupId>g</groupId><artifactId>root</artifactId><version>7</version>
                          <dependencyManagement><dependencies>
                            <dependency><groupId>g</groupId><artifactId>managed</artifactId><version>${project.version}</version></dependency>
                          </dependencies></dependencyManagement>
                        </project>
                        """)
                .file("v6/core/pom.xml", "<project><groupId>g</groupId><artifactId>core</artifactId><version>6</version></project>")
                .file("v7/core/pom.xml", "<project><groupId>g</groupId><artifactId>core</artifactId><version>7</version></project>")
                .file("v6/managed/pom.xml", "<project><groupId>g</groupId><artifactId>managed</artifactId><version>6</version></project>")
                .file("v7/managed/pom.xml", "<project><groupId>g</groupId><artifactId>managed</artifactId><version>7</version></project>")
                .file("app/pom.xml", """
                        <project><parent><groupId>g</groupId><artifactId>root</artifactId><version>7</version></parent>
                          <artifactId>app</artifactId><dependencies>
                            <dependency><groupId>g</groupId><artifactId>core</artifactId><version>${project.version}</version></dependency>
                            <dependency><groupId>g</groupId><artifactId>core</artifactId><version>${pom.version}</version></dependency>
                            <dependency><groupId>g</groupId><artifactId>managed</artifactId></dependency>
                          </dependencies>
                        </project>
                        """);
        Path root = repo.root().toAbsolutePath().normalize();

        assertThat(Modules.read(Repo.of(root)).direct(root.resolve("app")))
                .containsExactly(root.resolve("v7/core"), root.resolve("v7/managed"));
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void dependencyManagementParentCycleStopsAfterVisitingEachPom() {
        var repo = TempRepo.at(tmp.resolve("parent-cycle"))
                .file("v1/lib/pom.xml", "<project><groupId>g</groupId><artifactId>lib</artifactId><version>1</version></project>")
                .file("v2/lib/pom.xml", "<project><groupId>g</groupId><artifactId>lib</artifactId><version>2</version></project>")
                .file("cycle-a/pom.xml", """
                        <project><parent><groupId>g</groupId><artifactId>cycle-b</artifactId></parent><artifactId>cycle-a</artifactId>
                          <dependencies><dependency><groupId>g</groupId><artifactId>lib</artifactId></dependency></dependencies>
                        </project>
                        """)
                .file("cycle-b/pom.xml", "<project><parent><groupId>g</groupId><artifactId>cycle-a</artifactId></parent><artifactId>cycle-b</artifactId></project>");
        Path root = repo.root().toAbsolutePath().normalize();

        assertThat(Modules.read(Repo.of(root)).direct(root.resolve("cycle-a")))
                .satisfiesExactly(path -> assertThat(path).isIn(root.resolve("v1/lib"), root.resolve("v2/lib")));
    }

    @Test
    void gradleAppliedScriptsRejectVariablesUrlsAndPathsOutsideTheCheckout() {
        var repo = TempRepo.at(tmp.resolve("checkout"))
                .file("settings.gradle", "include ':app', ':lib'")
                .file("app/build.gradle", """
                        apply from: '$unknown.gradle'
                        apply from: 'https://example.invalid/build.gradle'
                        apply from: '../../outside.gradle'
                        dependencies { implementation project(':lib') }
                        """)
                .file("app/$unknown.gradle", "dependencies { implementation project(':variable-script') }")
                .file("lib/build.gradle", "");
        TempRepo.at(tmp).file("outside.gradle", "dependencies { implementation project(':outside-script') }");
        Path root = repo.root().toAbsolutePath().normalize();

        assertThat(Modules.read(Repo.of(root)).direct(root.resolve("app"))).containsExactly(root.resolve("lib"));
    }

    @Test
    void gradleAppliedScriptSymlinkCannotReadOutsideTheCheckout() throws IOException {
        var repo = TempRepo.at(tmp.resolve("symlink-repo"))
                .file("settings.gradle", "include ':app', ':lib'")
                .file("app/build.gradle", "apply from: 'linked.gradle'\ndependencies { implementation project(':lib') }\n")
                .file("lib/build.gradle", "");
        var outside = TempRepo.at(tmp.resolve("symlink-outside")).file("outside.gradle",
                "dependencies { implementation project(':outside') }\n");
        boolean linked;
        try {
            Files.createSymbolicLink(repo.root().resolve("app/linked.gradle"), outside.root().resolve("outside.gradle"));
            linked = true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            linked = false;
        }
        Assumptions.assumeTrue(linked, "symbolic link creation is unavailable");
        Path root = repo.root().toAbsolutePath().normalize();

        assertThat(Modules.read(Repo.of(root)).direct(root.resolve("app"))).containsExactly(root.resolve("lib"));
    }
}
