package io.github.sunjirapath.ssg.output;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;

final class TextFile {

    private TextFile() {}

    /** UTF-8 with LF line endings on every OS, so outputs diff cleanly. */
    static void write(Path file, String text) throws IOException {
        file = file.toAbsolutePath().normalize();
        // Check every component: NOFOLLOW on the leaf alone does not protect linked directories/junctions.
        Path parent = file.getRoot();
        for (Path part : file) {
            Path next = parent.resolve(part);
            if (Files.exists(next, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(next) || !next.toRealPath().equals(parent.toRealPath().resolve(part))))
                throw new IOException("output path contains a link");
            if (!next.equals(file)) Files.createDirectories(next);
            parent = next;
        }
        Path pending = Files.createTempFile(file.getParent(), ".ssg-", ".tmp");
        try {
            Files.writeString(pending, text.replace("\r\n", "\n"), StandardCharsets.UTF_8);
            // Unsupported atomic replacement fails closed, retaining the old report.
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
    }
}
