package io.github.sunjirapath.ssg.scan;

import io.github.sunjirapath.ssg.TempRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigBranchGapTest {

    @TempDir
    Path tmp;

    @Test
    void propertiesCommentPrefixControlsSeparatorsAndBackslashParityControlsContinuation() {
        String text = "before=kept\n# comment\n#---\nsame-comment=kept\n!---\nafter=kept\n"
                + "odd=left\\\n  right\neven=literal\\\\\nnext=separate\n";
        var repo = TempRepo.at(tmp).resource("application.properties", text);
        var config = Config.load(Repo.of(repo.root()), List.of(repo.root().resolve("src/main/resources/application.properties")), "default");

        assertThat(config.withPrefix("")).containsOnlyKeys("before", "same-comment", "after", "odd", "even", "next");
        assertThat(config.get("before").value()).isEqualTo("kept");
        assertThat(config.get("after").value()).isEqualTo("kept");
        assertThat(config.get("same-comment").value()).isEqualTo("kept");
        assertThat(config.get("odd").value()).isEqualTo("leftright");
        assertThat(config.get("even").value()).isEqualTo("literal\\");
        assertThat(config.get("next").value()).isEqualTo("separate");
    }

    @Test
    void invalidYamlMergeDropsItsDocumentAndEmptyListAndProfileExpressionsStayObservable() {
        var repo = TempRepo.at(tmp).resource("broken.yml", """
                initial: retained
                invalid:
                  <<: plain-scalar
                """).resource("syntax.yml", "broken: [secret-value\n")
                .resource("application.yml", """
                empty: []
                ---
                ---
                spring.config.activate.on-profile: "(prod & !qa) | staging"
                selected: yes
                ---
                spring.config.activate.on-profile: ""
                ignored: yes
                ---
                spring.config.activate.on-profile: null
                null-profile: yes
                """);
        Path broken = repo.root().resolve("src/main/resources/broken.yml");
        Path syntax = repo.root().resolve("src/main/resources/syntax.yml");
        Path application = repo.root().resolve("src/main/resources/application.yml");
        var config = Config.load(Repo.of(repo.root()), List.of(broken, syntax, application), "prod");

        assertThat(config.withPrefix("")).containsOnlyKeys("empty", "selected", "spring.config.activate.on-profile");
        assertThat(config.get("initial")).isNull();
        assertThat(config.get("empty").value()).isEmpty();
        assertThat(config.get("selected").value()).isEqualTo("yes");
        assertThat(config.get("ignored")).isNull();
        assertThat(config.get("invalid")).isNull();
        assertThat(config.get("null-profile")).isNull();
    }

    @Test
    void mavenFilteringUsesNearestAncestorPomAndStopsAtRepoRoot() throws java.io.IOException {
        var repo = TempRepo.at(tmp.resolve("repo"))
                .file("pom.xml", "<project><artifactId>outer</artifactId></project>")
                .file("nested/pom.xml", "<project><parent><artifactId>ignored-parent</artifactId></parent><artifactId>inner</artifactId></project>")
                .resource("nested/src/main/resources/application.yml", "name: @project.artifactId@\n");
        Path config = repo.root().resolve("nested/src/main/resources/application.yml");
        Path outside = tmp.getParent().resolve("outside-application.yml");

        assertThat(Config.unfiltered(Repo.of(repo.root()), config, "name: @project.artifactId@"))
                .isEqualTo("name: inner");
        assertThat(Config.unfiltered(Repo.of(repo.root()), outside, "name: @project.artifactId@"))
                .isEqualTo("name: ${project.artifactId}");

        // a pom above the repo root must never be used: the in-repo config has no in-repo pom, so it stays unfiltered
        java.nio.file.Files.writeString(tmp.resolve("pom.xml"), "<project><artifactId>above-root</artifactId></project>");
        var bare = TempRepo.at(tmp.resolve("repo2")).resource("application.yml", "name: @project.artifactId@\n");
        assertThat(Config.unfiltered(Repo.of(bare.root()), bare.root().resolve("src/main/resources/application.yml"),
                "name: @project.artifactId@")).isEqualTo("name: ${project.artifactId}");
    }
}
