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
}
