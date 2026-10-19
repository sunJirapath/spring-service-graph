package io.github.sunjirapath.ssg.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which sibling modules a module depends on, read from its Maven {@code pom.xml} (dependencies that name another
 * pom in the repo by groupId:artifactId, plus the parent pom's dependencies and those of profiles active by
 * default) or Gradle build file ({@code project(':a:b')}, with {@code projectDir} remaps from settings.gradle).
 * Test-scoped dependencies are left out, and an exclusion drops a module only below the dependency declaring it.
 */
// shortcut: no real build model; profiles activated by property, JDK, OS, file or -P are ignored (and
// activeByDefault is assumed on even when another profile would switch it off), and BOM imports are not followed;
// run Maven's help:effective-pom if a repo relies on those.
final class Modules {

    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern SKIPPED = Pattern.compile("<(parent|dependencyManagement|build|reporting)\\b[^>]*>.*?</\\1>", Pattern.DOTALL);
    private static final Pattern NOT_OWN = Pattern.compile("<(parent|dependencyManagement|dependencies|build|reporting|profiles)\\b[^>]*>.*?</\\1>", Pattern.DOTALL);
    private static final Pattern PROFILE = Pattern.compile("<profile>.*?</profile>", Pattern.DOTALL);
    private static final Pattern DEFAULT_ON = Pattern.compile("<activeByDefault>\\s*true\\s*</activeByDefault>");
    private static final Pattern PARENT = Pattern.compile("<parent\\b[^>]*>(.*?)</parent>", Pattern.DOTALL);
    private static final Pattern MANAGED = Pattern.compile("<dependencyManagement>(.*?)</dependencyManagement>", Pattern.DOTALL);
    private static final Pattern PROPERTIES = Pattern.compile("<properties>(.*?)</properties>", Pattern.DOTALL);
    private static final Pattern PROPERTY = Pattern.compile("<([\\w.-]+)>\\s*([^<]*?)\\s*</\\1>");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern DEPENDENCY = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern EXCLUSIONS = Pattern.compile("<exclusions>(.*?)</exclusions>", Pattern.DOTALL);
    private static final Pattern EXCLUSION = Pattern.compile("<exclusion>(.*?)</exclusion>", Pattern.DOTALL);
    private static final Pattern GROUP = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>");
    private static final Pattern ARTIFACT = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    private static final Pattern VERSION = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");
    private static final Pattern TEST_SCOPE = Pattern.compile("<scope>\\s*test\\s*</scope>");
    private static final Pattern CODE_COMMENT = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern GRADLE_PROJECT = Pattern.compile("(\\w+)\\s*\\(?\\s*project\\s*\\(\\s*(?:path\\s*[:=]\\s*)?[\"'](:[^\"']*)[\"']");
    private static final Pattern GRADLE_EXCLUDES = Pattern.compile("\\s*\\)\\s*\\)?\\s*\\{([^{}]*)}");
    private static final Pattern GRADLE_EXCLUDE = Pattern.compile("exclude\\b[^\\n}]*?module\\s*[:=]\\s*[\"']([^\"']+)[\"']");
    // shortcut: only literal remaps (file('x'), new File(rootDir, 'x')), not ones computed in a loop over
    // rootProject.children; evaluate settings.gradle if a repo builds its layout that way.
    private static final Pattern PROJECT_DIR = Pattern.compile("(?:project|findProject)\\s*\\(\\s*[\"'](:[^\"']*)[\"']\\s*\\)\\s*[?!]*\\s*\\.projectDir"
            + "\\s*=\\s*(?:file\\s*\\(|(?:new\\s+)?File\\s*\\(\\s*(?:rootDir|settingsDir|rootProject\\.projectDir)\\s*,)"
            + "\\s*[\"'](?:\\$\\{?(?:rootDir|settingsDir)}?/)?([^\"']+)[\"']");

    /** A pom.xml without comments and inactive profiles, with its coordinates as written (maybe {@code ${...}}). */
    private record Pom(String text, String parent, String group, String artifact, String version, Map<String, String> props) {}

    private final Map<Path, Pom> poms = new LinkedHashMap<>();
    private final Map<String, Path> artifacts = new HashMap<>(); // artifactId, groupId:artifactId and g:a:version
    private final Set<String> twins = new HashSet<>(); // groupId:artifactId that several poms declare
    private final Map<Path, String> groups = new HashMap<>();
    private final Map<Path, Set<Path>> direct = new HashMap<>();
    private final Map<Path, Map<Path, Set<Path>>> excluded = new HashMap<>(); // declaring module -> dependency -> dropped below it
    private final Map<Path, Path> parent = new HashMap<>();

    private Modules() {}

    static Modules read(Repo repo) {
        var m = new Modules();
        var builds = new LinkedHashMap<Path, String>();
        var settings = new HashMap<Path, Map<String, Path>>();
        try {
            for (Path f : repo.files(repo.root())) {
                String name = f.getFileName().toString();
                String rel = "/" + repo.relative(f);
                if (rel.contains("/src/") || rel.contains("/target/") || rel.contains("/build/")) continue; // fixtures and build output
                try {
                    if (name.equals("pom.xml")) {
                        m.poms.put(f.getParent(), pom(Files.readString(f)));
                    } else if (name.equals("build.gradle") || name.equals("build.gradle.kts")) {
                        builds.put(f.getParent(), CODE_COMMENT.matcher(Files.readString(f)).replaceAll(" "));
                    } else if (name.equals("settings.gradle") || name.equals("settings.gradle.kts")) {
                        var remaps = new HashMap<String, Path>();
                        Matcher r = PROJECT_DIR.matcher(CODE_COMMENT.matcher(Files.readString(f)).replaceAll(" "));
                        while (r.find()) remaps.put(r.group(1), f.getParent().resolve(r.group(2)).normalize());
                        settings.put(f.getParent(), remaps);
                    }
                } catch (IOException | RuntimeException e) {
                    System.err.println("warn: cannot read " + f + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("warn: cannot list " + repo.root() + ": " + e.getMessage());
        }
        m.maven();
        builds.forEach((dir, build) -> m.gradle(repo, dir, build, settings, builds.keySet()));
        return m;
    }

    private static Pom pom(String raw) {
        String text = XML_COMMENT.matcher(raw).replaceAll(" ");
        text = PROFILE.matcher(text).replaceAll(p -> DEFAULT_ON.matcher(p.group()).find() ? Matcher.quoteReplacement(p.group()) : " ");
        String own = NOT_OWN.matcher(text).replaceAll(" ");
        String parent = first(PARENT, text).orElse("");
        var props = new HashMap<String, String>();
        Matcher ps = PROPERTIES.matcher(SKIPPED.matcher(text).replaceAll(" "));
        while (ps.find()) for (Matcher p = PROPERTY.matcher(ps.group(1)); p.find(); ) props.put(p.group(1), p.group(2));
        return new Pom(text, parent, first(GROUP, own).or(() -> first(GROUP, parent)).orElse(null),
                first(ARTIFACT, own).orElse(null), first(VERSION, own).or(() -> first(VERSION, parent)).orElse(null), props);
    }

    private void maven() {
        // walk order, so the first pom of a duplicated artifactId wins as before
        poms.forEach((dir, pom) -> {
            if (pom.artifact() == null) return;
            artifacts.putIfAbsent(pom.artifact(), dir);
            if (pom.group() != null && !pom.group().contains("${")) register(pom.group() + ":" + pom.artifact(), dir);
        });
        poms.forEach((dir, pom) -> Optional.ofNullable(module(dir, pom.parent())).filter(d -> !d.equals(dir)).ifPresent(d -> parent.put(dir, d)));
        poms.forEach((dir, pom) -> {
            String g = resolve(dir, pom.group()), v = resolve(dir, pom.version());
            groups.put(dir, g);
            if (g == null || pom.artifact() == null) return;
            register(g + ":" + pom.artifact(), dir);
            if (v != null) artifacts.putIfAbsent(g + ":" + pom.artifact() + ":" + v, dir);
        });
        poms.forEach((dir, pom) -> {
            Matcher d = DEPENDENCY.matcher(SKIPPED.matcher(pom.text()).replaceAll(" "));
            while (d.find()) {
                if (TEST_SCOPE.matcher(d.group(1)).find()) continue;
                Path to = module(dir, EXCLUSIONS.matcher(d.group(1)).replaceAll(" "));
                if (to == null || to.equals(dir)) continue;
                direct.computeIfAbsent(dir, k -> new LinkedHashSet<>()).add(to);
                var dropped = new HashSet<Path>();
                for (Matcher ex = EXCLUSION.matcher(d.group(1)); ex.find(); ) dropped.addAll(excludes(dir, ex.group(1)));
                if (!dropped.isEmpty()) excluded.computeIfAbsent(dir, k -> new HashMap<>()).put(to, dropped);
            }
        });
    }

    private void register(String key, Path dir) {
        Path had = artifacts.putIfAbsent(key, dir);
        if (had != null && !had.equals(dir)) twins.add(key);
    }

    /**
     * The repo module a dependency (or parent) block names: by groupId:artifactId, by version too when several
     * poms share those, else by artifactId alone unless the groupId says it is some other project's artifact.
     */
    private Path module(Path dir, String xml) {
        String a = first(ARTIFACT, xml).orElse(null), g = first(GROUP, xml).map(x -> resolve(dir, x)).orElse(null);
        if (a == null) return null;
        String key = g + ":" + a;
        if (twins.contains(key)) {
            String v = resolve(dir, first(VERSION, xml).orElseGet(() -> managed(dir, g, a)));
            if (artifacts.containsKey(key + ":" + v)) return artifacts.get(key + ":" + v);
        }
        if (g != null && artifacts.containsKey(key)) return artifacts.get(key);
        Path any = artifacts.get(a);
        return any != null && (g == null || groups.get(any) == null) ? any : null;
    }

    /** Modules an {@code <exclusion>} names; {@code *} matches any groupId or artifactId. */
    private Set<Path> excludes(Path dir, String xml) {
        String g = first(GROUP, xml).orElse("*");
        if (!first(ARTIFACT, xml).orElse("*").equals("*")) return Optional.ofNullable(module(dir, xml)).map(Set::of).orElse(Set.of());
        var out = new HashSet<Path>();
        groups.forEach((p, group) -> { if (g.equals("*") || g.equals(group)) out.add(p); });
        return out;
    }

    /** The version a parent's {@code <dependencyManagement>} gives groupId:artifactId. */
    private String managed(Path dir, String g, String a) {
        var seen = new HashSet<Path>();
        for (Path at = dir; at != null && seen.add(at); at = parent.get(at)) {
            for (Matcher mm = MANAGED.matcher(poms.get(at).text()); mm.find(); ) {
                for (Matcher d = DEPENDENCY.matcher(mm.group(1)); d.find(); ) {
                    String dep = EXCLUSIONS.matcher(d.group(1)).replaceAll(" ");
                    if (first(ARTIFACT, dep).filter(a::equals).isPresent()
                            && Objects.equals(g, first(GROUP, dep).map(x -> resolve(dir, x)).orElse(null))) return first(VERSION, dep).orElse(null);
                }
            }
        }
        return null;
    }

    /** {@code text} with its {@code ${...}} resolved from {@code dir}'s pom and its parents, or null if one is unknown. */
    private String resolve(Path dir, String text) {
        for (int i = 0; text != null && i < 10; i++) {
            Matcher p = PLACEHOLDER.matcher(text);
            if (!p.find()) return text;
            String value = property(dir, p.group(1));
            text = value == null ? null : text.substring(0, p.start()) + value + text.substring(p.end());
        }
        return null;
    }

    private String property(Path dir, String key) {
        if (key.equals("project.groupId") || key.equals("pom.groupId")) return poms.get(dir).group();
        if (key.equals("project.version") || key.equals("pom.version")) return poms.get(dir).version();
        var seen = new HashSet<Path>();
        for (Path at = dir; at != null && seen.add(at); at = parent.get(at)) {
            String value = poms.get(at).props().get(key);
            if (value != null) return value;
        }
        return null;
    }

    // shortcut: a Gradle exclude names a project by its folder name, and configurations.all { exclude } and
    // group-only excludes are not read; map project names through settings.gradle if folders and names differ.
    private void gradle(Repo repo, Path dir, String build, Map<Path, Map<String, Path>> settings, Set<Path> projects) {
        Path root = repo.root(); // project paths are relative to the nearest build that has a settings file
        for (Path at = dir; at != null && at.startsWith(repo.root()); at = at.getParent()) {
            if (settings.containsKey(at)) {
                root = at;
                break;
            }
        }
        Map<String, Path> remaps = settings.getOrDefault(root, Map.of());
        Matcher m = GRADLE_PROJECT.matcher(build);
        while (m.find()) {
            if (m.group(1).startsWith("test")) continue;
            Path dep = root;
            String path = "";
            for (String part : m.group(2).substring(1).split(":")) {
                if (part.isEmpty()) continue;
                path += ":" + part;
                dep = remaps.getOrDefault(path, dep.resolve(part)); // a child sits in its parent's (remapped) dir
            }
            dep = dep.normalize();
            if (dep.equals(dir)) continue;
            direct.computeIfAbsent(dir, k -> new LinkedHashSet<>()).add(dep);
            Matcher block = GRADLE_EXCLUDES.matcher(build).region(m.end(), build.length());
            if (!block.lookingAt()) continue;
            var dropped = new HashSet<Path>();
            for (Matcher ex = GRADLE_EXCLUDE.matcher(block.group(1)); ex.find(); ) {
                String name = ex.group(1);
                projects.stream().filter(p -> name.equals(String.valueOf(p.getFileName()))).forEach(dropped::add);
            }
            if (!dropped.isEmpty()) excluded.computeIfAbsent(dir, k -> new HashMap<>()).put(dep, dropped);
        }
    }

    /** The modules {@code module} declares itself or through its parent poms. */
    Set<Path> direct(Path module) {
        var out = new LinkedHashSet<Path>();
        var seen = new HashSet<Path>();
        for (Path at = module; at != null && seen.add(at); at = parent.get(at)) out.addAll(direct.getOrDefault(at, Set.of()));
        out.remove(module);
        return out;
    }

    /**
     * Every module {@code module} depends on, transitively, not passing through {@code stops} (other apps)
     * nor below a dependency that excludes it; a module excluded on one path still counts if another reaches it.
     */
    Set<Path> all(Path module, Set<Path> stops) {
        var out = new LinkedHashSet<Path>();
        var seen = new HashSet<Map.Entry<Path, Set<Path>>>();
        var queue = new ArrayDeque<Map.Entry<Path, Set<Path>>>();
        queue.add(Map.entry(module, Set.of()));
        while (!queue.isEmpty()) {
            var at = queue.pop();
            if (!seen.add(at)) continue;
            for (Path d : direct(at.getKey())) {
                if (d.equals(module) || stops.contains(d) || at.getValue().contains(d)) continue;
                out.add(d);
                var dropped = new HashSet<>(at.getValue());
                dropped.addAll(exclusions(at.getKey(), d));
                queue.add(Map.entry(d, dropped));
            }
        }
        return out;
    }

    /** What {@code module}'s own declaration of {@code dep} (or the nearest parent pom's) excludes below it. */
    private Set<Path> exclusions(Path module, Path dep) {
        var seen = new HashSet<Path>();
        for (Path at = module; at != null && seen.add(at); at = parent.get(at)) {
            if (direct.getOrDefault(at, Set.of()).contains(dep)) return excluded.getOrDefault(at, Map.of()).getOrDefault(dep, Set.of());
        }
        return Set.of();
    }

    private static Optional<String> first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
