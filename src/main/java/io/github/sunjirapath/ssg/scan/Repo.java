package io.github.sunjirapath.ssg.scan;

import io.github.sunjirapath.ssg.model.Evidence;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** A scanned repository: its display name and checkout root. */
public record Repo(String name, Path root) {

    public static Repo of(Path root) {
        Path abs = root.toAbsolutePath().normalize();
        return new Repo(abs.getFileName().toString(), abs);
    }

    public String relative(Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    public Evidence evidence(Path file, int line) {
        return new Evidence(name, relative(file), line);
    }

    /**
     * Regular files under {@code start}, sorted. Skips {@code .git} and {@code node_modules}, and any
     * folder below the repo root that has its own {@code .git} (file or folder): nested worktrees,
     * submodules and vendored clones are other checkouts, not part of this repo.
     */
    public List<Path> files(Path start) throws IOException {
        var out = new ArrayList<Path>();
        if (!Files.isDirectory(start)) return out;
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                boolean nestedRepo = !dir.toAbsolutePath().normalize().equals(root) && Files.exists(dir.resolve(".git"));
                return n.equals(".git") || n.equals("node_modules") || nestedRepo ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) out.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE; // unreadable entries are skipped, not fatal
            }
        });
        out.sort(null);
        return out;
    }
}
