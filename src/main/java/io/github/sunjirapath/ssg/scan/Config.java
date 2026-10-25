package io.github.sunjirapath.ssg.scan;

import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Value;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Flattened Spring configuration ({@code a.b[0].c} keys) with the file:line each key came from.
 * Values of secret-looking keys are never kept: the key stays, its value is {@code null}.
 */
public final class Config {

    /** {@code value} is null for secret keys. */
    public record Entry(String value, Evidence evidence) {}

    private static final Pattern SECRET = Pattern.compile(
            "(?i).*(password|passwd|secret|token|credential|api[-_.]?key|private[-_.]?key).*");
    private static final int MAX_DEPTH = 16;

    private final Map<String, Entry> entries = new TreeMap<>();

    public Config() {}

    /** Loads files in increasing precedence; missing files are skipped. */
    public static Config load(Repo repo, List<Path> files, String profile) {
        var c = new Config();
        for (Path f : files) c.addFile(repo, f, profile);
        return c;
    }

    public void addFile(Repo repo, Path file, String profile) {
        if (!Files.isRegularFile(file)) return;
        try {
            String text = readText(file);
            if (file.toString().endsWith(".properties")) addProperties(repo, file, text, profile);
            else addYaml(repo, file, text, profile);
        } catch (IOException | RuntimeException e) {
            // A malformed config file must not stop the scan; its keys are simply absent.
            System.err.println("warn: cannot read " + file + ": " + e.getMessage());
        }
    }

    /** Config text stored inside another file (a Nacos seed row): every key is attributed to that file and line. */
    void addEmbedded(Repo repo, Path file, int line, boolean properties, String text, String profile) {
        var inner = new Config();
        try {
            if (properties) inner.addProperties(repo, file, text, profile);
            else inner.addYaml(repo, file, text, profile);
        } catch (IOException | RuntimeException e) {
            System.err.println("warn: cannot read config embedded in " + file + ":" + line + ": " + e.getMessage());
            return;
        }
        inner.entries.forEach((k, e) -> put(k, e.value(), repo.evidence(file, line)));
    }

    /** UTF-8 without BOM; legacy files that are not valid UTF-8 are read as ISO-8859-1, as Spring does for .properties. */
    static String readText(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String text;
        try {
            text = java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    public void putAll(Config other) {
        entries.putAll(other.entries);
    }

    public Entry get(String key) {
        return entries.get(key);
    }

    /** Entries whose key starts with {@code prefix}, in key order. */
    public Map<String, Entry> withPrefix(String prefix) {
        var out = new LinkedHashMap<String, Entry>();
        entries.forEach((k, v) -> { if (k.startsWith(prefix)) out.put(k, v); });
        return out;
    }

    /** Resolves {@code ${key:default}} placeholders (nested allowed). SpEL {@code #{...}} is unresolved. */
    public Value resolve(String text) {
        return resolve(text, 0);
    }

    /**
     * A config key's value, as code that reads the property sees it: at best RESOLVED (it came from a
     * property), with the key's file:line added to {@code via}.
     */
    public Value valueOf(String key) {
        Entry e = entries.get(key);
        if (e == null || e.value() == null) return Value.unresolved("${" + key + "}");
        Value v = resolve(e.value()).withConfidence(Confidence.RESOLVED, e.evidence());
        return new Value(v.text(), v.confidence(), "${" + key + "}", v.via());
    }

    private Value resolve(String text, int depth) {
        if (text.contains("#{")) return Value.unresolved(text);
        int start = text.indexOf("${");
        if (start < 0) return Value.exact(text);
        if (depth > MAX_DEPTH) return Value.unresolved(text);
        int end = matchingBrace(text, start + 2);
        if (end < 0) return Value.unresolved(text);

        String inner = text.substring(start + 2, end);
        int sep = defaultSeparator(inner);
        String key = sep < 0 ? inner : inner.substring(0, sep);
        Value replacement;
        Entry e = entries.get(key);
        if (e != null && e.value() != null) {
            replacement = resolve(e.value(), depth + 1).withConfidence(Confidence.RESOLVED, e.evidence());
        } else if (sep >= 0) {
            replacement = resolve(inner.substring(sep + 1), depth + 1).withConfidence(Confidence.RESOLVED, null);
        } else {
            replacement = Value.unresolved("${" + inner + "}");
        }
        Value head = Value.exact(text.substring(0, start));
        Value tail = resolve(text.substring(end + 1), depth + 1);
        Value out = head.concat(replacement, text).concat(tail, text);
        return new Value(out.text(), out.confidence(), text, out.via());
    }

    private static int matchingBrace(String s, int from) {
        int depth = 1;
        for (int i = from; i < s.length(); i++) {
            if (s.startsWith("${", i)) { depth++; i++; }
            else if (s.charAt(i) == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private static int defaultSeparator(String inner) {
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            if (inner.startsWith("${", i)) { depth++; i++; }
            else if (inner.charAt(i) == '}') depth--;
            else if (inner.charAt(i) == ':' && depth == 0) return i;
        }
        return -1;
    }

    private void put(String key, String value, Evidence ev) {
        entries.put(key, new Entry(SECRET.matcher(key).matches() ? null : value, ev));
    }

    // --- .properties: parsed line by line so every key keeps its line number

    private void addProperties(Repo repo, Path file, String text, String profile) throws IOException {
        String[] lines = text.split("\\R", -1);
        var doc = new LinkedHashMap<String, Entry>();
        int previousComment = 0;
        for (int i = 0; i < lines.length; i++) {
            if (propertiesSeparator(lines, i, previousComment)) {
                addDocument(doc, profile);
                doc.clear();
                previousComment = 0;
                continue;
            }
            String t = lines[i].strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) {
                previousComment = t.isEmpty() ? 0 : t.charAt(0);
                continue;
            }
            previousComment = 0;
            int first = i;
            var logical = new StringBuilder(lines[i]);
            while (endsWithContinuation(logical) && i + 1 < lines.length) {
                logical.setLength(logical.length() - 1);
                logical.append(lines[++i].stripLeading());
            }
            var p = new Properties();
            p.load(new StringReader(logical.toString())); // reuse the JDK's escaping rules
            for (String k : p.stringPropertyNames()) doc.put(k, new Entry(p.getProperty(k), repo.evidence(file, first + 1)));
        }
        addDocument(doc, profile);
    }

    private static boolean propertiesSeparator(String[] lines, int i, int previousComment) {
        String line = lines[i].stripTrailing();
        if (!line.equals("#---") && !line.equals("!---")) return false;
        String prefix = line.substring(0, 1);
        return line.charAt(0) != previousComment
                && (i + 1 == lines.length || !lines[i + 1].stripLeading().startsWith(prefix));
    }

    private static boolean endsWithContinuation(CharSequence s) {
        int n = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) n++;
        return n % 2 == 1;
    }

    // --- YAML: composed to nodes (not objects) so we keep line marks

    private void addYaml(Repo repo, Path file, String text, String profile) {
        var yaml = new Yaml(new LoaderOptions());
        for (var doc : yaml.composeAll(new StringReader(unfiltered(repo, file, text)))) {
            if (doc == null) continue; // empty document between --- separators
            var flat = new LinkedHashMap<String, Entry>();
            flatten(repo, file, "", doc, flat, Collections.newSetFromMap(new IdentityHashMap<>()));
            addDocument(flat, profile);
        }
    }

    private void addDocument(Map<String, Entry> doc, String profile) {
        if (activeFor(doc, profile)) doc.forEach((k, v) -> put(k, v.value(), v.evidence()));
    }

    private static final Pattern MAVEN_TOKEN = Pattern.compile("@([A-Za-z][\\w.-]*)@");
    private static final Pattern POM_PARENT = Pattern.compile("(?s)<!--.*?-->|<parent>.*?</parent>");
    private static final Pattern POM_ARTIFACT = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");

    /**
     * Maven resource filtering tokens ({@code @pom.artifactId@}) are not valid YAML where a value starts with
     * {@code @}. The module's own artifactId is substituted; any other token becomes an unresolved {@code ${...}}.
     */
    static String unfiltered(Repo repo, Path file, String text) {
        if (!MAVEN_TOKEN.matcher(text).find()) return text;
        String artifactId = artifactId(repo, file);
        return MAVEN_TOKEN.matcher(text).replaceAll(m -> {
            String key = m.group(1);
            boolean isArtifact = key.equals("artifactId") || key.equals("project.artifactId") || key.equals("pom.artifactId");
            return java.util.regex.Matcher.quoteReplacement(isArtifact && artifactId != null ? artifactId : "${" + key + "}");
        });
    }

    /** The artifactId of the nearest pom.xml above {@code file} (inside the repo), or null. */
    private static String artifactId(Repo repo, Path file) {
        Path root = repo.root().toAbsolutePath().normalize();
        for (Path dir = file.toAbsolutePath().normalize().getParent(); dir != null && dir.startsWith(root); dir = dir.getParent()) {
            Path pom = dir.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) continue;
            try {
                var m = POM_ARTIFACT.matcher(POM_PARENT.matcher(readText(pom)).replaceAll(""));
                return m.find() ? m.group(1) : null;
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean activeFor(Map<String, Entry> doc, String profile) {
        Entry on = doc.getOrDefault("spring.config.activate.on-profile", doc.get("spring.profiles"));
        if (on == null || on.value() == null) return true;
        return Arrays.stream(on.value().split(",")).anyMatch(e -> new Expr(e, profile).or());
    }

    /** A Spring profile expression ({@code !a}, {@code a & b}, {@code a | b}, parentheses) against one active profile. */
    private static final class Expr {
        private static final Pattern TOKEN = Pattern.compile("[()!&|]|[^()!&|\\s]+");
        private final List<String> tokens = new ArrayList<>();
        private final String profile;
        private int pos;

        Expr(String text, String profile) {
            this.profile = profile;
            TOKEN.matcher(text).results().forEach(r -> tokens.add(r.group()));
        }

        private boolean next(String t) {
            boolean hit = pos < tokens.size() && tokens.get(pos).equals(t);
            if (hit) pos++;
            return hit;
        }

        boolean or() {
            boolean v = and();
            while (next("|")) v |= and(); // not short-circuited: the operand must be consumed
            return v;
        }

        private boolean and() {
            boolean v = not();
            while (next("&")) v &= not();
            return v;
        }

        private boolean not() {
            if (next("!")) return !not();
            if (next("(")) {
                boolean v = or();
                next(")");
                return v;
            }
            return pos < tokens.size() && tokens.get(pos++).equals(profile);
        }
    }

    private static void flatten(Repo repo, Path file, String prefix, Node node,
                                Map<String, Entry> out, Set<Node> ancestors) {
        int line = node.getStartMark().getLine() + 1;
        if (!ancestors.add(node)) throw new IllegalArgumentException("recursive YAML alias at line " + line);
        try {
            switch (node) {
                case MappingNode m -> {
                    // merge keys (<<: *defaults) first, so keys written explicitly in this mapping override them
                    for (NodeTuple t : m.getValue()) {
                        if (t.getKeyNode() instanceof ScalarNode k && k.getValue().equals("<<")) {
                            var merged = t.getValueNode() instanceof SequenceNode s ? s.getValue() : List.of(t.getValueNode());
                            merged.forEach(n -> flatten(repo, file, prefix, n, out, ancestors));
                        }
                    }
                    for (NodeTuple t : m.getValue()) {
                        if (!(t.getKeyNode() instanceof ScalarNode k) || k.getValue().equals("<<")) continue;
                        flatten(repo, file, prefix.isEmpty() ? k.getValue() : prefix + "." + k.getValue(),
                                t.getValueNode(), out, ancestors);
                    }
                }
                case SequenceNode s -> {
                    List<Node> items = s.getValue();
                    for (int i = 0; i < items.size(); i++) flatten(repo, file, prefix + "[" + i + "]", items.get(i), out, ancestors);
                }
                case ScalarNode sc -> {
                    if (!prefix.isEmpty()) out.put(prefix, new Entry(sc.getValue(), repo.evidence(file, line)));
                }
                default -> { }
            }
        } finally {
            ancestors.remove(node);
        }
    }

    /** Reads a whole YAML/JSON file as SnakeYAML nodes, for extractors that walk non-config YAML. */
    public static List<org.yaml.snakeyaml.nodes.Node> composeAll(Reader reader) {
        var out = new ArrayList<org.yaml.snakeyaml.nodes.Node>();
        new Yaml(new LoaderOptions()).composeAll(reader).forEach(out::add);
        return out;
    }
}
