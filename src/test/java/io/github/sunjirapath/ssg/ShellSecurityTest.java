package io.github.sunjirapath.ssg;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class ShellSecurityTest {
    @TempDir(factory = io.github.sunjirapath.ssg.WorkspaceTempDirFactory.class) Path tmp;

    @Test
    void fleetQuoteCommandSubstitutionAndLeadingDashAttacksStayLiteral() throws Exception {
        // Kills eval/unquoted expansion of fleet/profile/config/sql-method inputs.
        String canary = path(tmp.resolve("EXECUTED"));
        String attack = "fleet/Order ' \" $(touch '" + canary + "') `touch '" + canary + "'`";
        Path args = tmp.resolve("args");
        int status = bash("""
                ssg() {
                  printf '%s\\n' "$@" >> "$ARGS"
                  return 0
                }
                source "$IMPACT_SCRIPT"
                """, Map.of("SSG_FLEET", attack + "\n-leading-dash\n", "SSG_PROFILE", attack,
                "SSG_ALIASES", attack, "SSG_CONFIG_REPO", attack, "SSG_SQL_METHODS", "Type.query $(false) `false`",
                "ARGS", path(args)));
        assertThat(status).as(log()).isZero();
        assertThat(Files.readString(args)).contains(attack, "-leading-dash", "$(false)", "`false`");
        assertThat(tmp.resolve("EXECUTED")).doesNotExist();
    }

    @Test
    void forgedMarkdownSectionAttackCannotSuppressBreakingExitStatus() throws Exception {
        // Kills trusting Markdown parsing instead of the authoritative exit 3 for the breaking flag.
        int status = bash("""
                ssg() {
                  if [ "$1" = diff ]; then printf '## Other section\\n_None._\\n'; return 3; fi
                  return 0
                }
                source "$IMPACT_SCRIPT"
                """, Map.of("SSG_FAIL_ON_BREAKING", "true"));
        assertThat(status).as(log()).isEqualTo(3);
        assertThat(Files.readString(tmp.resolve("out/breaking")).strip()).isEqualTo("true");
    }

    @Test
    void unexpectedExitCodeAttackCannotPassImpactGate() throws Exception {
        // Kills treating scanner/diff errors (1/2) as a clean gate result.
        for (int code : List.of(1, 2, 4)) {
            int status = bash("ssg() { if [ \"$1\" = diff ]; then return " + code
                    + "; fi; return 0; }\nsource \"$IMPACT_SCRIPT\"\n", Map.of("SSG_FAIL_ON_BREAKING", "true"));
            assertThat(status).as(log()).isEqualTo(code);
        }
    }

    @Test
    void actionInputInterpolationAndReportShellInjectionRemainData() throws Exception {
        // Kills moving inputs into run expressions, evaluating report text, or echoing the token.
        Map<?, ?> action = new Yaml().load(Files.readString(Path.of("action.yml")));
        var runs = (Map<?, ?>) action.get("runs");
        var steps = (List<?>) runs.get("steps");
        for (Object value : steps) {
            var step = (Map<?, ?>) value;
            if (step.get("run") != null) assertThat(step.get("run").toString())
                    .doesNotContain("${{ inputs.", "eval ", "echo \"$GH_TOKEN", "curl ");
        }
        String comment = steps.stream().map(Map.class::cast).filter(s -> "Post PR comment".equals(s.get("name")))
                .findFirst().orElseThrow().get("run").toString();
        assertThat(comment).contains("-F body=@\"$body\"", "cat \"$REPORT\"");
        assertThat(action.containsKey("permissions")).isFalse(); // Composite callers own token permissions.
    }

    @Test
    void benchmarkUrlAndRefInjectionRemainPinnedArguments() throws Exception {
        // Kills replacing quoted fetch arguments with eval or parameterized shell command text.
        String script = Files.readString(Path.of("scripts/bench-clone.sh"));
        assertThat(script).contains("\"https://github.com/$2\"", "fetch -q --depth 1 origin \"$3\"")
                .doesNotContain("eval ", "sh -c", "bash -c", "curl ");
        for (String line : script.lines().filter(l -> l.startsWith("clone ")).toList())
            assertThat(line).matches("clone\\s+[a-z0-9-]+\\s+[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\\s+[0-9a-f]{40}\\s+#.*");
    }

    private int bash(String text, Map<String, String> env) throws Exception {
        Path file = Files.writeString(tmp.resolve("shell.sh"), text.replace("\r", ""));
        Path gitBash = Path.of("C:/Program Files/Git/bin/bash.exe");
        var builder = new ProcessBuilder(Files.isRegularFile(gitBash) ? gitBash.toString() : "bash",
                "--noprofile", "--norc", "-e", "-o", "pipefail", path(file));
        builder.environment().putAll(Map.of("SSG", "ssg", "SSG_BASE", "base/repo", "SSG_HEAD", "head/repo",
                "SSG_OUT", path(tmp.resolve("out")), "SSG_FLEET", "", "SSG_PROFILE", "", "SSG_ALIASES", "",
                "SSG_CONFIG_REPO", "", "SSG_SQL_METHODS", "", "SSG_FAIL_ON_BREAKING", "false"));
        builder.environment().put("IMPACT_SCRIPT", path(Path.of("scripts/ssg-impact.sh").toAbsolutePath()));
        builder.environment().putAll(env);
        Process process = builder.redirectErrorStream(true).redirectOutput(tmp.resolve("shell.log").toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("Bash security probe terminated").isTrue();
            return process.exitValue();
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }

    private String log() throws Exception { return Files.readString(tmp.resolve("shell.log")); }
    private static String path(Path file) { return file.toString().replace('\\', '/'); }
}
