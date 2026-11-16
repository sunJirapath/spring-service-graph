package io.github.sunjirapath.ssg;

import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Task scratch stays under the workspace without changing global TEMP/TMP. JUnit owns cleanup. */
public final class WorkspaceTempDirFactory implements TempDirFactory {
    @Override public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context) throws IOException {
        return Files.createTempDirectory(Files.createDirectories(Path.of("build/security-tests").toAbsolutePath()), "case-");
    }
}
