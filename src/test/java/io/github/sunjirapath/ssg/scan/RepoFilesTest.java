package io.github.sunjirapath.ssg.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RepoFilesTest {

    @Test
    void returnsOnlyRegularFilesInSortedOrder(@TempDir Path root) throws Exception {
        Path z = write(root, "z.txt");
        Path a = write(root, "a/a.txt");
        Files.createDirectories(root.resolve("empty"));

        assertThat(Repo.of(root).files(root)).containsExactly(a, z);
    }

    @Test
    void skipsGitAndNodeModulesDirectories(@TempDir Path root) throws Exception {
        Path kept = write(root, "src/App.java");
        write(root, ".git/config");
        write(root, "node_modules/pkg/index.js");
        write(root, "src/node_modules/pkg/nested.js");

        assertThat(Repo.of(root).files(root)).containsExactly(kept);
    }

    @Test
    void skipsNestedCheckoutWhoseGitEntryIsAFile(@TempDir Path root) throws Exception {
        Path kept = write(root, "src/App.java");
        write(root, ".claude/worktrees/old/.git");
        write(root, ".claude/worktrees/old/src/Old.java");

        assertThat(Repo.of(root).files(root)).containsExactly(kept);
    }

    @Test
    void skipsNestedCheckoutWhoseGitEntryIsADirectory(@TempDir Path root) throws Exception {
        Path kept = write(root, "src/App.java");
        write(root, "vendor/legacy/.git/config");
        write(root, "vendor/legacy/src/Old.java");

        assertThat(Repo.of(root).files(root)).containsExactly(kept);
    }

    @Test
    void rootGitEntryDoesNotCauseTheCheckoutItselfToBeSkipped(@TempDir Path root) throws Exception {
        Path kept = write(root, "src/App.java");
        write(root, ".git/config");

        assertThat(Repo.of(root).files(root)).containsExactly(kept);
    }

    @Test
    void missingStartDirectoryReturnsEmptyList(@TempDir Path root) throws Exception {
        assertThat(Repo.of(root).files(root.resolve("missing"))).isEmpty();
    }

    private static Path write(Path root, String relative) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, relative.equals(".claude/worktrees/old/.git") ? "gitdir: elsewhere" : "x");
    }
}
