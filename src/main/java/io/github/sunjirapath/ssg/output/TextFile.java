package io.github.sunjirapath.ssg.output;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class TextFile {

    private TextFile() {}

    /** UTF-8 with LF line endings on every OS, so outputs diff cleanly. */
    static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text.replace("\r\n", "\n"), StandardCharsets.UTF_8);
    }
}
