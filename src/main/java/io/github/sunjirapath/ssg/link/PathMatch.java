package io.github.sunjirapath.ssg.link;

import io.github.sunjirapath.ssg.model.Value;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Matches a called path against an endpoint path template, segment by segment.
 * {@code {var}} matches one segment on either side; {@code **} matches the rest;
 * a trailing unknown segment ({@code {?}}) matches one or more segments.
 */
public final class PathMatch {

    private PathMatch() {}

    public static boolean matches(String call, String endpoint) {
        return match(segments(call), 0, segments(endpoint), 0);
    }

    private static String[] segments(String p) {
        return Arrays.stream(p.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    private static boolean match(String[] a, int i, String[] b, int j) {
        // ** swallows zero or more segments; whatever follows it must still match
        if (i < a.length && a[i].equals("**")) {
            for (int k = j; k <= b.length; k++) if (match(a, i + 1, b, k)) return true;
            return false;
        }
        if (j < b.length && b[j].equals("**")) {
            for (int k = i; k <= a.length; k++) if (match(a, k, b, j + 1)) return true;
            return false;
        }
        if (i == a.length || j == b.length) return i == a.length && j == b.length;
        if (a[i].equals(Value.UNKNOWN) && i == a.length - 1) return true;
        return segment(a[i], b[j]) && match(a, i + 1, b, j + 1);
    }

    private static boolean segment(String x, String y) {
        boolean xv = x.contains("{") || x.contains("*");
        boolean yv = y.contains("{") || y.contains("*");
        if (xv && yv) { // both templated: their literal prefixes and suffixes must not contradict
            String px = prefix(x), py = prefix(y), sx = suffix(x), sy = suffix(y);
            return (px.startsWith(py) || py.startsWith(px)) && (sx.endsWith(sy) || sy.endsWith(sx));
        }
        if (xv) return regex(x).matcher(y).matches();
        if (yv) return regex(y).matcher(x).matches();
        return x.equals(y);
    }

    private static String prefix(String template) {
        return template.split("[{*]", 2)[0];
    }

    private static String suffix(String template) {
        int i = Math.max(template.lastIndexOf('}'), template.lastIndexOf('*'));
        return template.substring(i + 1);
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
