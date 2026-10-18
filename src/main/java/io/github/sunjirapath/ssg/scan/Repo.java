package io.github.sunjirapath.ssg.scan;

import io.github.sunjirapath.ssg.model.Evidence;

import java.nio.file.Path;

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
}
