package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.output.DiagramOutput;
import io.github.sunjirapath.ssg.output.JsonOutput;
import io.github.sunjirapath.ssg.output.MarkdownOutput;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@code ssg scan --repos <dir>... [--profile p] [--aliases f] [--config-repo d] --out d}. */
public final class Main {

    private static final String USAGE = """
            usage: ssg scan --repos <dir>... [--profile <name>] [--aliases <aliases.yml>]
                            [--config-repo <dir>] --out <dir>
                   ssg diff --base <dir> --head <dir>   (not implemented yet)
            """;

    private Main() {}

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.print(USAGE);
            System.exit(2);
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    static int run(String[] args) throws IOException {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            System.out.print(USAGE);
            return 0;
        }
        Map<String, List<String>> opts = parse(args);
        return switch (args[0]) {
            case "scan" -> scan(opts);
            case "diff" -> {
                System.err.println("ssg diff is planned for Phase 2");
                yield 2;
            }
            default -> throw new IllegalArgumentException("unknown command: " + args[0]);
        };
    }

    private static int scan(Map<String, List<String>> opts) throws IOException {
        List<Path> repos = new ArrayList<>();
        for (String r : required(opts, "--repos")) repos.addAll(expand(r));
        if (repos.isEmpty()) throw new IllegalArgumentException("--repos matched no directories");
        Path out = Path.of(single(opts, "--out", null));
        String aliases = single(opts, "--aliases", "");
        String configRepo = single(opts, "--config-repo", "");
        var options = new Pipeline.Options(repos, single(opts, "--profile", "default"),
                aliases.isEmpty() ? null : Path.of(aliases), configRepo.isEmpty() ? null : Path.of(configRepo));
        var result = Pipeline.scan(options);
        JsonOutput.write(result.graph(), out);
        MarkdownOutput.write(result.graph(), result.repoRoots(), out);
        DiagramOutput.write(result.graph(), out);
        var g = result.graph();
        System.out.printf("%d nodes, %d edges, %d findings -> %s%n", g.nodes().size(), g.edges().size(),
                g.findings().size(), out.toAbsolutePath().normalize());
        return 0;
    }

    /** {@code --name v1 v2 --other v}: each option collects values until the next {@code --}. */
    static Map<String, List<String>> parse(String[] args) {
        var out = new HashMap<String, List<String>>();
        List<String> current = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                current = out.computeIfAbsent(args[i], k -> new ArrayList<>());
            } else if (current == null) {
                throw new IllegalArgumentException("unexpected argument: " + args[i]);
            } else {
                current.add(args[i]);
            }
        }
        return out;
    }

    private static List<String> required(Map<String, List<String>> opts, String name) {
        var v = opts.get(name);
        if (v == null || v.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return v;
    }

    private static String single(Map<String, List<String>> opts, String name, String dflt) {
        var v = opts.get(name);
        if (v == null || v.isEmpty()) {
            if (dflt == null) throw new IllegalArgumentException(name + " is required");
            return dflt;
        }
        if (v.size() > 1) throw new IllegalArgumentException(name + " takes one value");
        return v.getFirst();
    }

    /** Expands a trailing glob segment ({@code ./fleet/*}) for shells that do not, like cmd.exe. */
    static List<Path> expand(String arg) throws IOException {
        int cut = Math.max(arg.lastIndexOf('/'), arg.lastIndexOf('\\'));
        String glob = arg.substring(cut + 1);
        if (!glob.contains("*") && !glob.contains("?")) {
            Path dir = Path.of(arg);
            if (!Files.isDirectory(dir)) throw new IllegalArgumentException("not a directory: " + arg);
            return List.of(dir);
        }
        Path parent = Path.of(cut < 0 ? "." : cut == 0 ? "/" : arg.substring(0, cut));
        var out = new ArrayList<Path>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(parent, glob)) {
            for (Path d : ds) if (Files.isDirectory(d)) out.add(d);
        }
        out.sort(null);
        return out;
    }
}
