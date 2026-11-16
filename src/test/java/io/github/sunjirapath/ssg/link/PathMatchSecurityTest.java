package io.github.sunjirapath.ssg.link;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PathMatchSecurityTest {
    @Test
    void repeatedRestWildcardAttackDoesNotBacktrackExponentially() {
        // Kills restoring the recursive ** branch that retries every suffix combination.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(PathMatch.matches("/**".repeat(24) + "/missing", "/a".repeat(24))).isFalse();
            assertThat(PathMatch.matches("/a".repeat(24), "/**".repeat(24) + "/missing")).isFalse();
        });
    }

    @Test
    void repeatedSegmentWildcardAttackDoesNotUseBacktrackingRegex() {
        // Kills restoring a chain of [^/]* expressions, even when the final literal matches.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(PathMatch.matches("/" + "*a".repeat(24) + "b", "/" + "a".repeat(48) + "b")).isTrue();
            assertThat(PathMatch.matches("/" + "*a".repeat(24) + "b", "/" + "a".repeat(48) + "c")).isFalse();
        });
    }
}
