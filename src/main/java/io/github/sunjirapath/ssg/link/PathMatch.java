package io.github.sunjirapath.ssg.link;

import io.github.sunjirapath.ssg.model.Value;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Matches a called path against an endpoint path template, segment by segment.
 * {@code {var}} matches one segment on either side; {@code **} matches the rest;
 * a trailing unknown segment ({@code {?}}) matches one or more segments.
 */
final class PathMatch {

    private PathMatch() {}

    static boolean matches(String call, String endpoint) {
        return match(segments(call), 0, segments(endpoint), 0);
    }

    private static String[] segments(String p) {
        return Arrays.stream(p.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    private static boolean match(String[] a, int i, String[] b, int j) {
        if (i < a.length && a[i].equals("**")) return true;
        if (j < b.length && b[j].equals("**")) return true;
        if (i == a.length || j == b.length) return i == a.length && j == b.length;
        if (a[i].equals(Value.UNKNOWN) && i == a.length - 1) return true;
        return segment(a[i], b[j]) && match(a, i + 1, b, j + 1);
    }

    private static boolean segment(String x, String y) {
        boolean xv = x.contains("{") || x.contains("*");
        boolean yv = y.contains("{") || y.contains("*");
        if (xv && yv) return true;
        if (xv) return regex(x).matcher(y).matches();
        if (yv) return regex(y).matcher(x).matches();
        return x.equals(y);
    }

    /** {@code acc-{id}} -> {@code \Qacc-\E[^/]*}; {@code *} -> any. */
    private static Pattern regex(String template) {
        var sb = new StringBuilder();
        String[] parts = template.split("\\{[^}]*}|\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append("[^/]*");
            sb.append(Pattern.quote(parts[i]));
        }
        return Pattern.compile(sb.toString());
    }
}
