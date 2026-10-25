package io.github.sunjirapath.ssg.scan;

import io.github.sunjirapath.ssg.TempRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigPrecedenceRegressionTest {
    @TempDir Path dir;

    @Test
    void propertiesWinOverYamlAtTheSameLocalAndRemoteLocation() {
        var app = TempRepo.at(dir.resolve("app")).app()
                .resource("application.yml", "spring.application.name: yaml\n")
                .resource("application.properties", "spring.application.name=props\n")
                .resource("application-prod.yml", "target: yaml-profile\n")
                .resource("application-prod.properties", "target=props-profile\n");
        var service = app.services("prod", null).getFirst();
        assertThat(service.name()).isEqualTo("props");
        assertThat(service.config().valueOf("target").text()).isEqualTo("props-profile");

        var remote = TempRepo.at(dir.resolve("remote"))
                .file("props.yml", "target: remote-yaml\n")
                .file("props.properties", "target=remote-props\n")
                .file("props-prod.yml", "target: remote-yaml-profile\n")
                .file("props-prod.properties", "target=remote-props-profile\n");
        assertThat(app.services("prod", remote.root()).getFirst().config().valueOf("target").text())
                .isEqualTo("remote-props-profile");
    }

    @Test
    void yamlMergeUsesFirstMappingAndExplicitKeysReplaceWholeNestedValues() throws Exception {
        Path file = Files.writeString(dir.resolve("application.yml"), """
                first: &first
                  url: first
                  nested: {keep: first, stale: first}
                  routes: [{uri: first}, {uri: stale}]
                second: &second
                  url: second
                  nested: {keep: second, extra: second}
                merged:
                  <<: [*first, *second]
                explicit:
                  <<: *first
                  nested: {keep: explicit}
                  routes: [{uri: explicit}]
                """);
        Config config = Config.load(Repo.of(dir), List.of(file), "default");
        assertThat(config.valueOf("merged.url").text()).isEqualTo("first");
        assertThat(config.withPrefix("merged.nested.")).containsOnlyKeys("merged.nested.keep", "merged.nested.stale");
        assertThat(config.withPrefix("explicit.nested.")).containsOnlyKeys("explicit.nested.keep");
        assertThat(config.withPrefix("explicit.routes[")).containsOnlyKeys("explicit.routes[0].uri");
        assertThat(config.get("explicit.nested.keep").evidence().line()).isEqualTo(12);
    }
}
