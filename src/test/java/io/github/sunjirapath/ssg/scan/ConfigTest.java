package io.github.sunjirapath.ssg.scan;

import io.github.sunjirapath.ssg.model.Confidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigTest {

    @TempDir
    Path dir;

    private Config load(String file, String text, String profile) throws IOException {
        Path f = dir.resolve(file);
        Files.writeString(f, text);
        return Config.load(Repo.of(dir), List.of(f), profile);
    }

    @Test
    void yamlKeysAreFlattenedWithLineNumbers() throws IOException {
        var c = load("application.yml", """
                spring:
                  application:
                    name: orders
                routes:
                  - id: a
                    uri: lb://x
                """, "default");
        assertThat(c.get("spring.application.name").value()).isEqualTo("orders");
        assertThat(c.get("spring.application.name").evidence().line()).isEqualTo(3);
        assertThat(c.get("routes[0].uri").value()).isEqualTo("lb://x");
    }

    @Test
    void placeholdersResolveWithDefaultsAndNesting() throws IOException {
        var c = load("application.properties", """
                host=orders
                url=http://${host}:${port:8080}/api
                nested=${missing:${host}}
                """, "default");
        var url = c.valueOf("url");
        assertThat(url.text()).isEqualTo("http://orders:8080/api");
        assertThat(url.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(url.via()).anyMatch(e -> e.line() == 1);
        assertThat(c.valueOf("nested").text()).isEqualTo("orders");
        assertThat(c.resolve("${nope}").confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(c.resolve("#{'${host}'.split(',')}").confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(c.resolve("plain").confidence()).isEqualTo(Confidence.EXACT);
    }

    @Test
    void secretValuesAreDroppedButKeysKept() throws IOException {
        var c = load("application.yml", """
                spring.datasource.password: hunter2
                clients.x.api-key: abc
                clients.x.url: http://x
                """, "default");
        assertThat(c.get("spring.datasource.password")).isNotNull();
        assertThat(c.get("spring.datasource.password").value()).isNull();
        assertThat(c.get("clients.x.api-key").value()).isNull();
        assertThat(c.resolve("${clients.x.api-key}").text()).doesNotContain("abc");
        assertThat(c.get("clients.x.url").value()).isEqualTo("http://x");
    }

    @Test
    void profileDocumentsApplyOnlyForTheirProfile() throws IOException {
        String yaml = """
                topic: base
                ---
                spring.config.activate.on-profile: prod
                topic: prod-topic
                """;
        assertThat(load("a.yml", yaml, "default").get("topic").value()).isEqualTo("base");
        assertThat(load("b.yml", yaml, "prod").get("topic").value()).isEqualTo("prod-topic");
    }

    @Test
    void propertiesKeepLineOfContinuedValues() throws IOException {
        var c = load("application.properties", "# c\na=1\nb=x\\\n  y\nc=3\n", "default");
        assertThat(c.get("b").value()).isEqualTo("xy");
        assertThat(c.get("b").evidence().line()).isEqualTo(3);
        assertThat(c.get("c").evidence().line()).isEqualTo(5);
    }

    @Test
    void recursiveYamlAliasesDiscardTheirDocumentAndAllowLaterFiles() throws IOException {
        Path good = Files.writeString(dir.resolve("good.yml"), "good: retained\n");
        for (String cycle : List.of(
                "loop: &loop\n  self: *loop\n",
                "loop: &loop [*loop]\n",
                "loop: &loop\n  <<: *loop\n",
                "loop: &loop\n  <<: [*loop]\n")) {
            Path bad = Files.writeString(dir.resolve("bad.yml"), "partial: discarded\n" + cycle);

            var c = Config.load(Repo.of(dir), List.of(bad, good), "default");

            assertThat(c.withPrefix("")).containsOnlyKeys("good");
            assertThat(c.get("good").value()).isEqualTo("retained");
        }
    }

    @Test
    void sharedYamlAliasesAreExpandedOnEachIndependentPath() throws IOException {
        var c = load("application.yml", """
                defaults: &defaults
                  urls: &urls [one, two]
                first: *defaults
                second:
                  <<: *defaults
                third: [*urls, *urls]
                """, "default");

        assertThat(c.get("first.urls[1]").value()).isEqualTo("two");
        assertThat(c.get("second.urls[1]").value()).isEqualTo("two");
        assertThat(c.get("third[0][1]").value()).isEqualTo("two");
        assertThat(c.get("third[1][1]").value()).isEqualTo("two");
        assertThat(c.get("third[1][1]").evidence().line()).isEqualTo(2);
    }

    @Test
    void propertiesDocumentsApplyProfilesAndPrecedenceIndependently() throws IOException {
        String text = """
                mode=base
                base-only=yes
                #---
                spring.config.activate.on-profile=prod
                mode=production
                prod-only=yes
                !---
                spring.config.activate.on-profile=dev | qa
                mode=development
                dev-only=yes
                #---
                spring.config.activate.on-profile=!prod
                mode=non-production
                """;

        var defaultConfig = load("default.properties", text, "default");
        var prod = load("prod.properties", text, "prod");
        var qa = load("qa.properties", text, "qa");

        assertThat(defaultConfig.get("mode").value()).isEqualTo("non-production");
        assertThat(defaultConfig.get("prod-only")).isNull();
        assertThat(defaultConfig.get("dev-only")).isNull();
        assertThat(prod.get("mode").value()).isEqualTo("production");
        assertThat(prod.get("mode").evidence().line()).isEqualTo(5);
        assertThat(prod.get("base-only").value()).isEqualTo("yes");
        assertThat(prod.get("dev-only")).isNull();
        assertThat(qa.get("mode").value()).isEqualTo("non-production");
        assertThat(qa.get("mode").evidence().line()).isEqualTo(13);
        assertThat(qa.get("dev-only").value()).isEqualTo("yes");
    }

    @Test
    void propertiesDocumentsPreserveEscapesContinuationsAndPhysicalEvidence() throws IOException {
        String text = "base=yes\r\n#--- \t\r\nspring.config.activate.on-profile=(prod | qa) & !dev\r\n"
                + "escaped\\=key=left\\\r\n  #---\r\nletter=\\u0041\r\n";

        var c = load("application.properties", text, "qa");

        assertThat(c.get("escaped=key").value()).isEqualTo("left#---");
        assertThat(c.get("escaped=key").evidence().line()).isEqualTo(4);
        assertThat(c.get("letter").value()).isEqualTo("A");
        assertThat(c.get("letter").evidence().line()).isEqualTo(6);
        assertThat(load("dev.properties", text, "dev").withPrefix("")).containsOnlyKeys("base");
    }

    @Test
    void propertiesDocumentSeparatorsFollowSpringCommentRules() throws IOException {
        for (String invalid : List.of(" #---", "\t!---", "#----", "!--", "#--- extra",
                "# before\n#---", "#---\n# after", "! before\n!---", "!---\n! after")) {
            String text = "base=yes\n" + invalid + "\nspring.config.activate.on-profile=prod\nselected=yes\n";
            assertThat(load("invalid.properties", text, "default").withPrefix(""))
                    .as("not a separator: %s", invalid).isEmpty();
            assertThat(load("invalid.properties", text, "prod").get("base").value()).isEqualTo("yes");
        }
        for (String separator : List.of("#---", "!---")) {
            String opposite = separator.startsWith("#") ? "!" : "#";
            String text = "base=yes\n" + opposite + " before\n" + separator + "\n"
                    + opposite + " after\nspring.config.activate.on-profile=prod\nselected=yes\n";
            assertThat(load("valid.properties", text, "default").withPrefix("")).containsOnlyKeys("base");
        }
    }

    @Test
    void propertiesCommentsCannotContinueIntoAnotherDocument() throws IOException {
        var c = load("application.properties", "base=yes\n! comment\\\n#---\n"
                + "spring.config.activate.on-profile=prod\nselected=yes\n", "default");

        assertThat(c.withPrefix("")).containsOnlyKeys("base");
    }

    @Test
    void embeddedPropertiesHonorDocumentProfilesAndRetainSeedEvidence() {
        var c = new Config();
        Path seed = dir.resolve("seed.sql");
        c.addEmbedded(Repo.of(dir), seed, 42, true,
                "mode=base\n#---\nspring.config.activate.on-profile=prod\nmode=production\n", "default");

        assertThat(c.get("mode").value()).isEqualTo("base");
        assertThat(c.get("mode").evidence().line()).isEqualTo(42);
        assertThat(c.get("mode").evidence().file()).isEqualTo("seed.sql");
    }
}
