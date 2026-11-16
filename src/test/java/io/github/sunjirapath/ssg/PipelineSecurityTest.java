package io.github.sunjirapath.ssg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class PipelineSecurityTest {
    @TempDir(factory = WorkspaceTempDirFactory.class) Path tmp;

    @Test
    void recursiveAliasMapAttackIsRejectedBeforeStringConversion() throws Exception {
        // Kills String.valueOf on non-scalar alias values (mutual recursion can overflow toString).
        Path aliases = Files.writeString(tmp.resolve("aliases.yml"), "host: &a {child: &b {parent: *a}}\n");
        assertThatThrownBy(() -> Pipeline.aliases(aliases)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flat map");
    }

    @Test
    void malformedSecretAndUnsafeTagAliasAttacksDoNotEchoYaml() throws Exception {
        // Kills exposing YAML source snippets in CLI exception diagnostics or constructing tagged objects.
        Path aliases = tmp.resolve("aliases.yml");
        for (String text : new String[]{"host: [CANARY_ALIAS_PASSWORD\n", "host: !!java.net.URL [https://CANARY_ALIAS_PASSWORD.invalid]\n"}) {
            Files.writeString(aliases, text);
            assertThatThrownBy(() -> Pipeline.aliases(aliases)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("CANARY_ALIAS_PASSWORD");
        }
    }

    @Test
    void bootstrapNacosConfigRepoAndJdbcCredentialAttacksDoNotReachReports() throws Exception {
        // Kills retaining secret-key values in any config layer or dropping URL credential redaction.
        var repo = TempRepo.at(tmp.resolve("orders")).app()
                .resource("bootstrap.properties", "local.password=CANARY_BOOTSTRAP\n")
                .resource("application.properties", "spring.application.name=orders\nspring.datasource.url=jdbc:mysql://user:CANARY_JDBC@db/app?password=CANARY_QUERY\n")
                .file("nacos-config.sql", "INSERT INTO config_info (data_id,content) VALUES ('orders.properties','bus.secret=CANARY_NACOS');\n")
                .java("app/Api.java", """
                        package app;
                        @RestController class Api {
                            @GetMapping("/${remote.token}") void get() {}
                            @KafkaListener(topics="${bus.secret}") void seed(String s) {}
                            @KafkaListener(topics="${local.password}") void local(String s) {}
                            void call() { new RestTemplate().getForObject("${spring.datasource.url}", String.class); }
                        }
                        """);
        var config = TempRepo.at(tmp.resolve("config")).file("orders.properties", "remote.token=CANARY_CONFIG_REPO\n");
        var service = repo.services("default", config.root()).getFirst();
        for (String key : List.of("local.password", "bus.secret", "remote.token")) {
            assertThat(service.config().get(key)).as("layer supplies %s", key).isNotNull();
            assertThat(service.config().get(key).value()).isNull();
        }
        var result = Pipeline.scan(new Pipeline.Options(List.of(repo.root()), "default", null, config.root()));
        Path out = tmp.resolve("out");
        io.github.sunjirapath.ssg.output.JsonOutput.write(result.graph(), out);
        io.github.sunjirapath.ssg.output.MarkdownOutput.write(result.graph(), result.repoRoots(), out);
        io.github.sunjirapath.ssg.output.DiagramOutput.write(result.graph(), out);
        try (var files = Files.walk(out)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) assertThat(Files.readString(file)).doesNotContain("CANARY_");
        }
        assertThat(io.github.sunjirapath.ssg.diff.GraphDiff.report(result.graph(), result.graph())).doesNotContain("CANARY_");
        assertThat(io.github.sunjirapath.ssg.output.MarkdownOutput.servicePage(result.graph(), "orders")).doesNotContain("CANARY_");
    }

    @Test
    void malformedOpenApiSecretAttackDoesNotLeakParserSnippets() throws Exception {
        // Kills logging raw SnakeYAML exceptions while recovering a missing generated controller interface.
        var repo = TempRepo.at(tmp.resolve("orders")).app()
                .java("app/Api.java", "package app; @RestController class Api implements GeneratedApi {}")
                .resource("openapi.yml", "openapi: 3.0.0\npaths:\n  /orders:\n    get: [CANARY_OPENAPI_PASSWORD\n");
        var bytes = new java.io.ByteArrayOutputStream();
        var original = System.err;
        try (var capture = new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setErr(capture);
            assertThat(TempRepo.scan(repo).findings()).anyMatch(f -> f.kind() == io.github.sunjirapath.ssg.model.Finding.Kind.MISSING_INTERFACE);
        } finally { System.setErr(original); }
        assertThat(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("CANARY_OPENAPI_PASSWORD");
    }
}
