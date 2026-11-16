package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.diff.GraphDiff;
import io.github.sunjirapath.ssg.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Deliberately unskipped evidence for unresolved security boundaries; these may fail. */
class SecurityFindingsTest {
    @TempDir(factory = io.github.sunjirapath.ssg.WorkspaceTempDirFactory.class) Path tmp;

    @Test
    void unsupportedBootTwoMetadataAttackCannotPassSafetyGate() {
        // FINDING: Boot 2.x metadata is never rejected; the impact gate can declare unsupported analysis clean.
        // Kills omitting the unsupported-framework finding after reading Maven metadata.
        var repo = TempRepo.at(tmp.resolve("legacy")).app().file("pom.xml", """
                <project><parent><groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-parent</artifactId><version>2.0.3.RELEASE</version></parent>
                <artifactId>legacy</artifactId></project>
                """);
        var graph = TempRepo.scan(repo);
        assertThat(graph.findings()).anyMatch(f -> f.kind() == Finding.Kind.INCOMPLETE_ANALYSIS
                && f.message().toLowerCase().contains("boot 2"));
        assertThat(GraphDiff.compare(graph, graph).incomplete()).isNotEmpty();
    }

    @Test
    void diffMarkdownNewlineAndHtmlInjectionAttackCannotForgeReportSections() {
        // FINDING: GraphDiff renders source-controlled labels/messages/evidence without escaping.
        // Kills raw interpolation into the impact report used in PR comments and shell section parsing.
        var base = new Graph(List.of(), List.of(), List.of());
        var head = new Graph(List.of(), List.of(), List.of(new Finding(Finding.Kind.INCOMPLETE_ANALYSIS,
                "bad\n## Forged", "<img src=x>\n::error::FORGED\u001b", List.of())));
        assertThat(GraphDiff.report(base, head)).doesNotContain("\n## Forged", "<img", "\n::error::FORGED", "\u001b");
    }

    @Test
    void unsafeXmlRegistrationAttackMustMarkIncompleteAnalysis() {
        // FINDING: XmlBeans silently drops rejected XML; no finding reaches the impact gate.
        // Kills swallowing a registration parser failure without an incomplete-analysis finding.
        var repo = TempRepo.at(tmp.resolve("xml"))
                .java("app/App.java", "package app; @SpringBootApplication @ImportResource(\"classpath:beans.xml\") class App {}")
                .resource("beans.xml", "<!DOCTYPE beans [<!ENTITY x 'bomb'>]><beans>&x;</beans>");
        var graph = TempRepo.scan(repo);
        assertThat(GraphDiff.compare(graph, graph).incomplete()).isNotEmpty();
    }

    @Test
    void gatewayRewriteRegexBacktrackingAttackMustFinishWithinBudget() throws Exception {
        // FINDING: Gateway RewritePath executes arbitrary repository regexes in-process without a work budget.
        // Kills passing attacker-controlled nested quantifiers straight to String.replaceAll.
        var repo = TempRepo.at(tmp.resolve("gateway")).app().resource("application.properties",
                "spring.cloud.gateway.routes[0].uri=lb://target\n"
                + "spring.cloud.gateway.routes[0].predicates[0]=Path=/" + "a".repeat(60_000) + "!\n"
                + "spring.cloud.gateway.routes[0].filters[0]=RewritePath=(a+)+$,x\n");
        var result = CliProcess.run(tmp.resolve("process"), Duration.ofSeconds(8), "", "scan", "--repos",
                repo.root().toString(), "--out", tmp.resolve("out").toString());
        assertThat(result.timedOut()).as("hostile regex must finish; child JVM is killed on timeout").isFalse();
        assertThat(result.code()).isZero();
    }

    @Test
    void secretPlaceholderDefaultAttackCannotLeakIntoEndpointIdentity() {
        // FINDING: redacted secret keys still resolve their literal placeholder defaults and retain raw defaults.
        // Kills using a sensitive-key default as a reportable contract value.
        var repo = TempRepo.at(tmp.resolve("defaults")).app()
                .resource("application.properties", "password=REDACTED_REAL_VALUE\n")
                .java("app/Api.java", "package app; @RestController class Api { @GetMapping(\"/${password:CANARY_DEFAULT_SECRET}\") void get() {} }");
        var graph = TempRepo.scan(repo);
        assertThat(graph.toString()).doesNotContain("CANARY_DEFAULT_SECRET", "REDACTED_REAL_VALUE");
    }

    @Test
    void staleOutputAttackCannotKeepRetiredServiceReport() throws Exception {
        // FINDING: output writers overwrite current pages but leave old service/diagram pages behind.
        // Kills retaining a retired service's report after regenerating into an existing output directory.
        var repo = TempRepo.at(tmp.resolve("repo")).app().resource("application.properties", "spring.application.name=old-service\n");
        Path out = tmp.resolve("out");
        Main.run(new String[]{"scan", "--repos", repo.root().toString(), "--out", out.toString()});
        assertThat(out.resolve("services/old-service.md")).isRegularFile();
        repo.resource("application.properties", "spring.application.name=new-service\n");
        Main.run(new String[]{"scan", "--repos", repo.root().toString(), "--out", out.toString()});
        assertThat(out.resolve("services/old-service.md")).doesNotExist();
        assertThat(out.resolve("diagrams/old-service.mmd")).doesNotExist();
    }

    @Test
    void pathologicalJavaArrayNestingAndConcatenationAttacksStayBounded() throws Exception {
        // FINDING: source bytes are bounded, but AST complexity/CPU/heap have no scanner-wide budget.
        // Kills unbounded parser/evaluator work that crashes the child or prevents later sources being analyzed.
        var repo = TempRepo.at(tmp.resolve("pathological")).app()
                .java("app/AArray.java", "package app; class AArray { int[] a={" + "1,".repeat(100_000) + "}; }")
                .java("app/BNested.java", "package app; class BNested { int a=" + "(".repeat(4000) + "1" + ")".repeat(4000) + "; }")
                .java("app/CConcat.java", "package app; @RestController class CConcat { @GetMapping(\"/\"+"
                        + "\"x\"+".repeat(10_000) + "\"end\") void get() {} }")
                .java("app/ZGood.java", "package app; @RestController class ZGood { @GetMapping(\"/visible\") void get() {} }");
        Path out = tmp.resolve("out");
        var result = CliProcess.run(tmp.resolve("process"), Duration.ofSeconds(15), "", "scan", "--repos", repo.root().toString(), "--out", out.toString());
        assertThat(result.timedOut()).as("pathological scan is killed if it exceeds the deadline").isFalse();
        assertThat(result.code()).isZero();
        assertThat(io.github.sunjirapath.ssg.output.JsonOutput.read(out).nodes().toString()).contains("/visible");
    }

    @Test
    void partialOutputFailureAttackCannotReplaceOnlyGraph() throws Exception {
        // FINDING: Main writes graph.json before validating/rendering the other report formats.
        // Kills publishing one new report file when another writer rejects the scan's arguments.
        var repo = TempRepo.at(tmp.resolve("repo")).app();
        Path out = tmp.resolve("out");
        io.github.sunjirapath.ssg.output.JsonOutput.write(new Graph(List.of(), List.of(), List.of()), out);
        byte[] previous = java.nio.file.Files.readAllBytes(out.resolve("graph.json"));
        assertThatThrownBy(() -> Main.run(new String[]{"scan", "--repos", repo.root().toString(), "--out", out.toString(),
                "--link-base", "javascript:bad"})).isInstanceOf(IllegalArgumentException.class);
        assertThat(java.nio.file.Files.readAllBytes(out.resolve("graph.json"))).isEqualTo(previous);
    }

    @Test
    void duplicateServiceWorkflowCommandAndAnsiInjectionAttackCannotReachLogs() throws Exception {
        // FINDING: Pipeline interpolates untrusted service names into stderr without control escaping.
        // Kills emitting a standalone GitHub workflow command from a duplicate-service warning.
        String config = "spring.application.name=duplicate\\n::error::FORGED\\u001b[31m\n";
        var first = TempRepo.at(tmp.resolve("first")).app().resource("application.properties", config);
        var second = TempRepo.at(tmp.resolve("second")).app().resource("application.properties", config);
        var bytes = new java.io.ByteArrayOutputStream();
        var original = System.err;
        try (var capture = new java.io.PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            TempRepo.scan(first, second);
        } finally { System.setErr(original); }
        assertThat(bytes.toString(StandardCharsets.UTF_8)).doesNotContain("\n::error::FORGED", "\u001b");
    }
}
