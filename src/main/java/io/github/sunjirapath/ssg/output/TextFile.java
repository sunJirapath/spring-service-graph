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
        file = checked(file, true);
        Path pending = Files.createTempFile(file.getParent(), ".ssg-", ".tmp");
        try {
            Files.writeString(pending, text.replace("\r\n", "\n"), StandardCharsets.UTF_8);
            // Unsupported atomic replacement fails closed, retaining the old report.
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private static Path checked(Path file, boolean createParents) throws IOException {
        file = file.toAbsolutePath().normalize();
        // Check every component: NOFOLLOW on the leaf alone does not protect linked directories/junctions.
        Path parent = file.getRoot();
        for (Path part : file) {
            Path next = parent.resolve(part);
            if (Files.exists(next, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(next) || !next.toRealPath().equals(parent.toRealPath().resolve(part))))
                throw new IOException("output path contains a link");
            if (createParents && !next.equals(file)) Files.createDirectories(next);
            parent = next;
        }
        return file;
    }

    /** Only retire pages named by the previous snapshot; preserve unrelated files in the output directory. */
    static void removeRetired(io.github.sunjirapath.ssg.model.Graph graph, Path out, String directory, String extension) throws IOException {
        Path snapshot = checked(out.resolve("graph.json"), false);
        if (!Files.exists(snapshot)) return;
        var before = new GraphView(JsonOutput.read(snapshot));
        var after = new GraphView(graph);
        var current = after.services().stream().map(s -> out.resolve(directory).resolve(after.fileName(s.name()) + extension)
                .toAbsolutePath().normalize()).collect(java.util.stream.Collectors.toSet());
        for (var service : before.services()) {
            Path retired = out.resolve(directory).resolve(before.fileName(service.name()) + extension).toAbsolutePath().normalize();
            if (!current.contains(retired)) {
                checked(retired, false);
                if (Files.isRegularFile(retired, LinkOption.NOFOLLOW_LINKS)) Files.delete(retired);
            }
        }
    }
}
