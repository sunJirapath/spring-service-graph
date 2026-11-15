package io.github.sunjirapath.ssg.link;

import io.github.sunjirapath.ssg.model.Value;

import java.util.Arrays;

/**
 * Matches a called path against an endpoint path template, segment by segment.
 * {@code {var}} matches one segment on either side; {@code **} matches the rest;
 * a trailing unknown segment ({@code {?}}) matches one or more segments.
 */
public final class PathMatch {

    private PathMatch() {}

    public static boolean matches(String call, String endpoint) {
        String[] a = segments(call), b = segments(endpoint);
        boolean[] next = new boolean[b.length + 1];
        // Dynamic programming avoids exponential ** backtracking and recursion on long paths.
        for (int i = a.length; i >= 0; i--) {
            boolean[] row = new boolean[b.length + 1];
            for (int j = b.length; j >= 0; j--) {
                if (i < a.length && isRest(a[i]))
                    row[j] = next[j] || j < b.length && row[j + 1];
                else if (j < b.length && isRest(b[j]))
                    row[j] = row[j + 1] || i < a.length && next[j];
                else if (i == a.length || j == b.length)
                    row[j] = i == a.length && j == b.length;
                else row[j] = a[i].equals(Value.UNKNOWN) && i == a.length - 1
                            || segment(a[i], b[j]) && next[j + 1];
            }
            next = row;
        }
        return next[0];
    }

    private static String[] segments(String p) {
        return Arrays.stream(p.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    /** {@code **} and Spring's {@code {*rest}} capture-the-rest variable. */
    private static boolean isRest(String s) {
        return s.equals("**") || (s.startsWith("{*") && s.endsWith("}"));
    }

    private static boolean segment(String x, String y) {
        boolean xv = x.contains("{") || x.contains("*");
        boolean yv = y.contains("{") || y.contains("*");
        if (xv && yv) { // both templated: their literal prefixes and suffixes must not contradict
            String px = prefix(x), py = prefix(y), sx = suffix(x), sy = suffix(y);
            return (px.startsWith(py) || py.startsWith(px)) && (sx.endsWith(sy) || sy.endsWith(sx));
        }
        if (xv) return templateMatches(x, y);
        if (yv) return templateMatches(y, x);
        return x.equals(y);
    }

    private static String prefix(String template) {
        return template.split("[{*]", 2)[0];
    }

    private static String suffix(String template) {
        int i = Math.max(template.lastIndexOf('}'), template.lastIndexOf('*'));
        return template.substring(i + 1);
    }

    /** Each placeholder is a wildcard; literal chunks can be located without a backtracking regex. */
    private static boolean templateMatches(String template, String value) {
        String[] parts = template.split("\\{[^}]*}|\\*", -1);
        if (parts.length == 1) return template.equals(value);
        if (!value.startsWith(parts[0]) || !value.endsWith(parts[parts.length - 1])) return false;
        int at = parts[0].length();
        int end = value.length() - parts[parts.length - 1].length();
        for (int i = 1; i < parts.length - 1; i++) {
            at = value.indexOf(parts[i], at);
            if (at < 0) return false;
            at += parts[i].length();
        }
        return at <= end;
    }
}
