package io.github.sunjirapath.ssg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

public final class TestFiles {
    public static Path symlink(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "symlinks unavailable");
            return link;
        }
    }

    private TestFiles() {}
}
