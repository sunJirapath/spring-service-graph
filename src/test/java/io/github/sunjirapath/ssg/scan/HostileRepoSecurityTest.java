package io.github.sunjirapath.ssg.scan;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.diff.GraphDiff;
import io.github.sunjirapath.ssg.model.Finding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static io.github.sunjirapath.ssg.TestFiles.symlink;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class HostileRepoSecurityTest {
    @TempDir(factory = io.github.sunjirapath.ssg.WorkspaceTempDirFactory.class) Path tmp;

    @Test
    void configurationSymlinkAttackCannotReadOutsideSecret() throws Exception {
        // Kills following directly resolved application.yml links (not enumerated by Repo.files).
        var repo = TempRepo.at(tmp.resolve("repo")).app();
        Path secret = Files.writeString(tmp.resolve("outside.yml"), "spring.application.name: CANARY_OUTSIDE\n");
        Path resources = Files.createDirectories(repo.root().resolve("src/main/resources"));
        symlink(resources.resolve("application.yml"), secret);
        var graph = TempRepo.scan(repo);
        assertThat(graph.nodes().toString()).doesNotContain("CANARY_OUTSIDE");
        assertThat(graph.findings()).anyMatch(f -> f.message().contains("escapes repository"));
    }

    @Test
    void explicitTraversalAttackCannotLoadConfigurationOrEnumerateOutside() throws Exception {
        // Kills lexical containment checks being absent on public reader entry points.
        var repo = TempRepo.at(tmp.resolve("repo")).app();
        Path outside = Files.writeString(tmp.resolve("outside.yml"), "payload: CANARY_OUTSIDE\n");
        Config c = Config.load(Repo.of(repo.root()), List.of(repo.root().resolve("../outside.yml")), "default");
        assertThat(c.get("payload")).isNull();
        assertThat(c.problems()).isNotEmpty();
        assertThat(Repo.of(repo.root()).files(outside.getParent())).isEmpty();
    }

    @Test
    void directorySymlinkAndLoopAttackCannotEnumerateOutside() throws Exception {
        // Kills walking a caller-supplied start through a symlink even without FOLLOW_LINKS.
        var repo = TempRepo.at(tmp.resolve("repo"));
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Files.writeString(outside.resolve("private.java"), "CANARY_OUTSIDE");
        Path link = symlink(repo.root().resolve("resources"), outside);
        symlink(repo.root().resolve("loop"), repo.root());
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(Repo.of(repo.root()).files(link)).isEmpty();
            assertThat(Repo.of(repo.root()).files(repo.root())).isEmpty();
        });
    }

    @Test
    void autoConfigurationRegistrationSymlinkAttackCannotReadOutsideClasses() throws Exception {
        // Kills the containment checks on directly resolved META-INF files, bypassing Repo.files.
        var repo = TempRepo.at(tmp.resolve("repo"));
        Path module = Files.createDirectories(repo.root().resolve("lib"));
        Path meta = Files.createDirectories(module.resolve("src/main/resources/META-INF"));
        Path factories = Files.writeString(tmp.resolve("outside.factories"),
                "org.springframework.boot.autoconfigure.EnableAutoConfiguration=outside.CANARY\n");
        Path imports = Files.writeString(tmp.resolve("outside.imports"), "outside.CANARY\n");
        symlink(meta.resolve("spring.factories"), factories);
        Files.createDirectories(meta.resolve("spring"));
        symlink(meta.resolve("spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"), imports);
        var reader = Discovery.class.getDeclaredMethod("autoConfigs", Repo.class, Path.class);
        reader.setAccessible(true);
        assertThat((List<?>) reader.invoke(null, Repo.of(repo.root()), module)).isEmpty();
    }

    @Test
    void recursiveYamlMigrationAttackIsIncompleteAndDoesNotHideLaterTable() {
        // Kills walking a YAML alias cycle or swallowing the error without a gate finding.
        var repo = TempRepo.at(tmp.resolve("repo")).app()
                .resource("db/changelog/a.yml", "databaseChangeLog: &loop [*loop]\n")
                .resource("db/migration/V1__good.sql", "CREATE TABLE security_visible (id INT);\n");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var graph = TempRepo.scan(repo);
            assertThat(graph.nodes().toString()).contains("security_visible");
            assertThat(graph.findings()).anyMatch(f -> f.kind() == Finding.Kind.INCOMPLETE_ANALYSIS);
            assertThat(GraphDiff.compare(graph, graph).incomplete()).isNotEmpty();
        });
    }

    @Test
    void deeplyNestedAndAliasExpansionYamlAttacksAreRejected() {
        // Kills removing shared graph depth/cycle/visit checks used by migration and OpenAPI walkers.
        assertThatThrownBy(() -> Config.composeAll(new StringReader("a: " + "[".repeat(100) + "0" + "]".repeat(100))))
                .isInstanceOf(RuntimeException.class);
        StringBuilder bomb = new StringBuilder("a0: &a0 [x,x,x,x,x,x]\n");
        for (int i = 1; i <= 6; i++) bomb.append("a").append(i).append(": &a").append(i).append(" [")
                .append(("*a" + (i - 1) + ",").repeat(5)).append("*a").append(i - 1).append("]\n");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatThrownBy(() -> Config.composeAll(new StringReader(bomb.toString()))).isInstanceOf(RuntimeException.class));
    }

    @Test
    void oversizedConfigurationAttackDoesNotHideFollowingConfiguration() {
        // Kills reading unlimited bytes before the parser's own scalar limits apply.
        var repo = TempRepo.at(tmp.resolve("repo"))
                .resource("large.properties", "large=" + "x".repeat(Config.MAX_TEXT_BYTES))
                .resource("good.properties", "visible=yes\n");
        Path root = repo.root().resolve("src/main/resources");
        var c = Config.load(Repo.of(repo.root()), List.of(root.resolve("large.properties"), root.resolve("good.properties")), "default");
        assertThat(c.get("large")).isNull();
        assertThat(c.get("visible").value()).isEqualTo("yes");
        assertThat(c.problems()).isNotEmpty();
    }

    @Test
    void oversizedJavaAttackLeavesFindingAndIndexesLaterFile() {
        // Kills the per-source byte guard and per-file continuation after a rejection.
        var repo = TempRepo.at(tmp.resolve("repo")).app()
                .java("app/AHuge.java", " ".repeat(Config.MAX_TEXT_BYTES + 1))
                .java("app/ZGood.java", "package app; @RestController class ZGood { @GetMapping(\"/visible\") void get() {} }");
        var graph = TempRepo.scan(repo);
        assertThat(graph.nodes().toString()).contains("/visible");
        assertThat(graph.findings()).anyMatch(f -> f.kind() == Finding.Kind.PARSE_ERROR);
    }

    @Test
    void binaryJavaAndMalformedArchiveAttacksDoNotHideValidSource() {
        // Kills aborting on a bad UTF-8 source or dispatching class/JAR data to an executable loader.
        var repo = TempRepo.at(tmp.resolve("repo")).app()
                .bytes("src/main/java/app/ABinary.java", new byte[]{(byte) 0xff, 0, (byte) 0xfe})
                .bytes("src/main/resources/hostile.jar", new byte[]{'P', 'K', 0, 0})
                .bytes("src/main/resources/Evil.class", new byte[]{(byte) 0xca, (byte) 0xfe})
                .java("app/ZGood.java", "package app; @RestController class ZGood { @GetMapping(\"/visible\") void get() {} }");
        var graph = TempRepo.scan(repo);
        assertThat(graph.nodes().toString()).contains("/visible");
        assertThat(graph.findings()).anyMatch(f -> f.kind() == Finding.Kind.PARSE_ERROR);
    }

    @Test
    void oversizedMigrationAttackIsIncompleteAndKeepsLaterMigration() {
        // Kills unbounded resource reads or swallowing a rejected file without a finding/continuation.
        var repo = TempRepo.at(tmp.resolve("repo")).app()
                .resource("db/migration/V0__large.sql", " ".repeat(Config.MAX_TEXT_BYTES + 1))
                .resource("db/migration/V1__visible.sql", "CREATE TABLE security_visible (id INT);\n");
        var graph = TempRepo.scan(repo);
        assertThat(graph.nodes().toString()).contains("security_visible");
        assertThat(graph.findings()).anyMatch(f -> f.kind() == Finding.Kind.INCOMPLETE_ANALYSIS
                && f.evidence().stream().anyMatch(e -> e.file().endsWith("V0__large.sql")));
    }

    @Test
    void oversizedNacosSeedAttackCannotHideLaterConfigRow() {
        // Kills wrapping all Nacos files in one catch instead of continuing after the bad seed.
        var repo = TempRepo.at(tmp.resolve("repo")).app()
                .resource("application.properties", "spring.application.name=orders\n")
                .file("a-nacos.sql", " ".repeat(Config.MAX_TEXT_BYTES + 1))
                .file("z-nacos.sql", "INSERT INTO config_info (data_id,content) VALUES ('orders.properties','bus.topic=security.visible');\n");
        assertThat(repo.service().config().get("bus.topic")).isNotNull();
        assertThat(repo.service().config().get("bus.topic").value()).isEqualTo("security.visible");
        var graph = TempRepo.scan(repo);
        assertThat(GraphDiff.compare(graph, graph).incomplete()).isNotEmpty();
    }

    @Test
    void xmlExternalEntityAttackCannotRegisterOutsideBean() throws Exception {
        // Kills permitting DTD/entity expansion in XmlBeans; the expanded class would enter the result.
        Path root = Files.createDirectories(tmp.resolve("resources"));
        Path secret = Files.writeString(tmp.resolve("secret.txt"), "<bean class=\"outside.SecretBean\"/>");
        Files.writeString(root.resolve("beans.xml"), "<!DOCTYPE beans [<!ENTITY leak SYSTEM \"" + secret.toUri()
                + "\">]><beans xmlns=\"http://www.springframework.org/schema/beans\">&leak;</beans>");
        assertThat(new XmlBeans(new Config(), List.of(root)).classes("classpath:beans.xml")).isEmpty();
    }

    @Test
    void unsafeYamlTagAndMalformedSecretAttacksDoNotLeakValues() {
        // Kills object construction from tags or reintroducing parser snippets in config diagnostics.
        var repo = TempRepo.at(tmp.resolve("repo"))
                .resource("tag.yml", "x: !!java.net.URL [https://CANARY_TAG.invalid]\n")
                .resource("bad.yml", "password: [CANARY_PASSWORD\n")
                .resource("good.properties", "password=CANARY_PASSWORD\nvisible=yes\n");
        Path root = repo.root().resolve("src/main/resources");
        var c = Config.load(Repo.of(repo.root()), List.of(root.resolve("tag.yml"), root.resolve("bad.yml"), root.resolve("good.properties")), "default");
        assertThat(c.get("password").value()).isNull();
        assertThat(c.problems()).isNotEmpty();
        assertThat(c.problems().toString()).doesNotContain("CANARY_PASSWORD", "CANARY_TAG");
    }

    @Test
    void parserDiagnosticSecretInjectionAttackCannotLeakSourceLiterals() {
        // Kills propagating lexer/parser source tokens into graph findings and CLI reports.
        var repo = TempRepo.at(tmp.resolve("syntax")).app()
                .java("app/Broken.java", "package app; class Broken { String x = \"CANARY_SOURCE_SECRET\\q\"; }")
                .java("app/Syntax.java", "package app; class Syntax { String x = \"ok\" \"CANARY_SYNTAX_SECRET\"; }");
        var graph = TempRepo.scan(repo);
        assertThat(graph.findings()).anyMatch(f -> f.kind() == Finding.Kind.PARSE_ERROR);
        assertThat(graph.findings().toString()).doesNotContain("CANARY_SOURCE_SECRET", "CANARY_SYNTAX_SECRET");
    }
}
