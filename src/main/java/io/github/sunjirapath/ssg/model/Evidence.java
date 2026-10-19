package io.github.sunjirapath.ssg.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Comparator;

/**
 * A source location: repo name, repo-relative file path (forward slashes), 1-based line and 1-based
 * column (0 when unknown, e.g. config files). Ordering, {@link #toString()} and JSON use the line only,
 * so output and merged evidence lists stay one entry per line; equality includes the column.
 */
public record Evidence(String repo, String file, int line, @JsonIgnore int column) implements Comparable<Evidence> {

    private static final Comparator<Evidence> ORDER = Comparator.comparing(Evidence::repo)
            .thenComparing(Evidence::file).thenComparingInt(Evidence::line);

    public Evidence(String repo, String file, int line) {
        this(repo, file, line, 0);
    }

    @Override
    public int compareTo(Evidence o) {
        return ORDER.compare(this, o);
    }

    @Override
    public String toString() {
        return repo + "/" + file + ":" + line;
    }
}
