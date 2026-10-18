package io.github.sunjirapath.ssg.model;

import java.util.Comparator;

/** A source location: repo name, repo-relative file path (forward slashes) and 1-based line. */
public record Evidence(String repo, String file, int line) implements Comparable<Evidence> {

    private static final Comparator<Evidence> ORDER = Comparator.comparing(Evidence::repo)
            .thenComparing(Evidence::file).thenComparingInt(Evidence::line);

    @Override
    public int compareTo(Evidence o) {
        return ORDER.compare(this, o);
    }

    @Override
    public String toString() {
        return repo + "/" + file + ":" + line;
    }
}
