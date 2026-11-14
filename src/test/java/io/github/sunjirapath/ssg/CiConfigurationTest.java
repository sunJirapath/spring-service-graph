package io.github.sunjirapath.ssg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CiConfigurationTest {
    @TempDir Path tmp;

    @Test
    void releasePublishesArchiveChecksumsAfterBuilding() throws Exception {
        var jobs = (Map<?, ?>) yaml(".github/workflows/release.yml").get("jobs");
        var release = (Map<?, ?>) jobs.get("release");
        var steps = steps(release);
        var checksums = named(steps, "Checksum distributions");
        assertThat(checksums.get("working-directory")).isEqualTo("build/distributions");
        assertThat(checksums.get("run")).isEqualTo("sha256sum -- *.zip *.tar > SHA256SUMS");
        assertThat(steps.indexOf(checksums)).isGreaterThan(steps.indexOf(named(steps, "Build distributions")))
                .isLessThan(steps.indexOf(named(steps, "Publish release")));
        assertThat(named(steps, "Publish release").get("run").toString()).contains("build/distributions/*");
    }

    @Test
    void compositeActionConfiguresGradleCacheBeforeBuilding() throws Exception {
        var steps = actionSteps();
        var cache = steps.stream().filter(s -> "gradle/actions/setup-gradle@v6".equals(s.get("uses"))).findFirst();
        assertThat(cache).isPresent();
        assertThat(steps.indexOf(cache.orElseThrow())).isLessThan(steps.indexOf(named(steps, "Build ssg")));
    }

    @Test
    void failedCommentLookupIsBestEffortAndGateRunsAfterCommentFailure() throws Exception {
        var steps = actionSteps();
        var comment = named(steps, "Post PR comment").get("run").toString();
        int code = bash("gh() { return 7; }\n" + comment, Map.of(
                "RUNNER_TEMP", shellPath(tmp), "REPORT", shellPath(report()), "REPO", "owner/repo", "PR", "1"));
        assertThat(code).isZero();
        assertThat(output()).contains("could not list PR comments");
        assertThat(named(steps, "Fail on incomplete analysis or breaking changes").get("if").toString())
                .startsWith("always() &&").contains("outputs.incomplete", "outputs.breaking");
    }

    @Test
    void missingGhDoesNotAttemptACommentOrAbortTheGate() throws Exception {
        var comment = named(actionSteps(), "Post PR comment").get("run").toString();
        int code = bash("""
                command() {
                  if [ "$1" = -v ] && [ "$2" = gh ]; then return 1; fi
                  builtin command "$@"
                }
                gh() { echo unexpected-gh-call; return 7; }
                """ + comment, Map.of());
        assertThat(code).isZero();
        assertThat(output()).contains("gh is unavailable").doesNotContain("unexpected-gh-call");
    }

    @Test
    void impactScriptPreservesLiteralFleetPathsWithSpacesAndCrLf() throws Exception {
        String base = shellPath(tmp.resolve("base/orders"));
        String head = shellPath(tmp.resolve("head/orders"));
        String first = "fleet/Order Service";
        String second = "fleet/Billing [literal]";
        Path calls = tmp.resolve("calls.txt");
        int code = bash("""
                ssg() {
                  printf '%s\\n' "$@" >> "$CALLS"
                  if [ "$1" = diff ]; then printf '# Impact report\\n\\n## Breaking changes\\n\\n_None found._\\n'; fi
                  return 0
                }
                source "$IMPACT_SCRIPT"
                """, Map.ofEntries(Map.entry("SSG", "ssg"), Map.entry("SSG_BASE", base), Map.entry("SSG_HEAD", head),
                Map.entry("SSG_FLEET", first + "\r\n\r\n" + second + "\r\n"),
                Map.entry("SSG_OUT", shellPath(tmp.resolve("out"))), Map.entry("CALLS", shellPath(calls)),
                Map.entry("SSG_PROFILE", ""), Map.entry("SSG_ALIASES", ""), Map.entry("SSG_CONFIG_REPO", ""),
                Map.entry("SSG_SQL_METHODS", ""), Map.entry("SSG_FAIL_ON_BREAKING", "false"),
                Map.entry("IMPACT_SCRIPT", shellPath(Path.of("scripts/ssg-impact.sh").toAbsolutePath()))));
        assertThat(code).withFailMessage("%s", output()).isZero();
        var args = Files.readAllLines(calls);
        assertThat(args).containsSequence("--repos", base, first, second, "--out")
                .containsSequence("--repos", head, first, second, "--out");
        assertThat(Files.readString(tmp.resolve("out/incomplete")).strip()).isEqualTo("false");
    }

    @Test
    void readmeDocumentsEveryHandledCliExitStatus() throws Exception {
        assertThat(Files.readString(Path.of("README.md"))).contains("exit **0**", "**1** for I/O",
                "**2** for invalid arguments", "exit **3**", "exit **4**", "external termination");
    }

    private Path report() throws Exception {
        Files.createDirectories(tmp.resolve("ssg-impact"));
        return Files.writeString(tmp.resolve("impact.md"), "# Impact report\n");
    }

    private int bash(String script, Map<String, String> environment) throws Exception {
        Path scriptFile = Files.writeString(tmp.resolve("shell.sh"), script.replace("\r", ""));
        Path gitBash = Path.of("C:/Program Files/Git/bin/bash.exe");
        var builder = new ProcessBuilder(Files.isRegularFile(gitBash) ? gitBash.toString() : "bash",
                "--noprofile", "--norc", "-e", "-o", "pipefail", shellPath(scriptFile));
        builder.environment().putAll(environment);
        Process process = builder.redirectErrorStream(true).redirectOutput(tmp.resolve("shell.log").toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("shell regression completed").isTrue();
            return process.exitValue();
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    private String output() throws Exception { return Files.readString(tmp.resolve("shell.log")); }
    private static String shellPath(Path path) { return path.toString().replace('\\', '/'); }

    private static Map<?, ?> yaml(String file) throws Exception {
        return new Yaml().load(Files.readString(Path.of(file)));
    }

    private static List<Map<String, Object>> actionSteps() throws Exception {
        return steps((Map<?, ?>) yaml("action.yml").get("runs"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(Map<?, ?> parent) {
        return (List<Map<String, Object>>) parent.get("steps");
    }

    private static Map<String, Object> named(List<Map<String, Object>> steps, String name) {
        return steps.stream().filter(s -> name.equals(s.get("name"))).findFirst().orElseThrow();
    }
}
