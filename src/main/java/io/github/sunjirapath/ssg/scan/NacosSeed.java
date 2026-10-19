package io.github.sunjirapath.ssg.scan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service config kept in a Nacos SQL seed: the {@code INSERT INTO config_info (data_id, ..., content, ...)} rows,
 * with {@code data_id}s like {@code application-dev.yml} and {@code <app>-dev.yml}.
 */
final class NacosSeed {

    /** One config_info row; {@code line} is where its INSERT value list starts. */
    record Row(Path file, String dataId, String content, int line) {
        String stem() {
            return dataId.substring(0, dataId.lastIndexOf('.') < 0 ? dataId.length() : dataId.lastIndexOf('.'));
        }

        boolean isConfig() {
            return dataId.endsWith(".yml") || dataId.endsWith(".yaml") || dataId.endsWith(".properties");
        }
    }

    private static final Pattern INSERT =
            Pattern.compile("(?is)INSERT\\s+INTO\\s+`?config_info`?\\s*\\(([^)]*)\\)\\s*VALUES");

    private NacosSeed() {}

    /** The config rows of every {@code *.sql} file named like nacos/config in the repo. */
    static List<Row> read(Repo repo) {
        var rows = new ArrayList<Row>();
        try {
            for (Path f : repo.files(repo.root())) {
                String n = f.getFileName().toString().toLowerCase();
                if (!n.endsWith(".sql") || !(n.contains("nacos") || n.contains("config"))) continue;
                rows.addAll(parse(f, Config.readText(f)));
            }
        } catch (IOException e) {
            System.err.println("warn: cannot read Nacos seed in " + repo.root() + ": " + e.getMessage());
        }
        return rows;
    }

    // shortcut: only INSERT rows are read; later UPDATE ... REPLACE(content, ...) patches in a seed are ignored,
    // upgrade when a seed fixes its config that way.
    static List<Row> parse(Path file, String sql) {
        var rows = new ArrayList<Row>();
        Matcher m = INSERT.matcher(sql);
        int pos = 0;
        while (m.find(pos)) {
            var cols = Arrays.stream(m.group(1).split(",")).map(c -> c.replace("`", "").strip()).toList();
            int idCol = cols.indexOf("data_id");
            int contentCol = cols.indexOf("content");
            pos = m.end();
            while (idCol >= 0 && contentCol >= 0 && (pos = skipSpace(sql, pos)) < sql.length() && sql.charAt(pos) == '(') {
                int line = 1 + (int) sql.substring(0, pos).chars().filter(c -> c == '\n').count();
                var values = new ArrayList<String>();
                pos = tuple(sql, pos + 1, values);
                if (values.size() > Math.max(idCol, contentCol)) {
                    rows.add(new Row(file, values.get(idCol), values.get(contentCol), line));
                }
                pos = skipSpace(sql, pos);
                if (pos < sql.length() && sql.charAt(pos) == ',') pos++;
            }
        }
        return rows;
    }

    /** Reads values up to the closing parenthesis; returns the index after it. */
    private static int tuple(String s, int i, List<String> out) {
        int n = s.length();
        while (i < n) {
            i = skipSpace(s, i);
            var v = new StringBuilder();
            if (i < n && s.charAt(i) == '\'') {
                i = string(s, i, v);
            } else {
                int depth = 0; // NOW(), 0, NULL: kept raw
                for (; i < n && (depth > 0 || (s.charAt(i) != ',' && s.charAt(i) != ')')); i++) {
                    if (s.charAt(i) == '(') depth++;
                    else if (s.charAt(i) == ')') depth--;
                    v.append(s.charAt(i));
                }
            }
            out.add(v.toString().strip());
            i = skipSpace(s, i);
            if (i < n && s.charAt(i) == ',') i++;
            else return i < n ? i + 1 : n;
        }
        return n;
    }

    /** A single-quoted SQL string at {@code i} with backslash and doubled-quote escapes; returns the index after it. */
    private static int string(String s, int i, StringBuilder out) {
        int n = s.length();
        for (i++; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char e = s.charAt(++i);
                out.append(switch (e) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case '0' -> '\0';
                    default -> e;
                });
            } else if (c == '\'') {
                if (i + 1 < n && s.charAt(i + 1) == '\'') {
                    out.append('\'');
                    i++;
                } else {
                    return i + 1;
                }
            } else {
                out.append(c);
            }
        }
        return n;
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /**
     * The config Nacos serves {@code app}: {@code application} then {@code <app>} data ids, each with its profile
     * variant on top. When the scan has no profile ({@code default}) and the seed uses exactly one profile, that one applies.
     */
    static Config load(Repo repo, List<Row> rows, String app, String profile) {
        String active = profile;
        if (profile.equals("default")) {
            var profiles = new TreeSet<String>();
            for (Row r : rows) {
                for (String base : List.of("application", app)) {
                    if (r.isConfig() && r.stem().startsWith(base + "-")) profiles.add(r.stem().substring(base.length() + 1));
                }
            }
            if (profiles.size() == 1) active = profiles.first();
        }
        var config = new Config();
        for (String base : List.of("application", app)) {
            for (String stem : List.of(base, base + "-" + active)) {
                for (Row r : rows) {
                    if (r.isConfig() && r.stem().equals(stem)) {
                        config.addEmbedded(repo, r.file(), r.line(), r.dataId().endsWith(".properties"), r.content(), active);
                    }
                }
            }
        }
        return config;
    }
}
