package io.github.sunjirapath.ssg.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which sibling modules a module depends on, read from its Maven {@code pom.xml} (dependency artifactIds that
 * name another pom in the repo, plus the parent pom's dependencies) or Gradle build file ({@code project(':a:b')}).
 * Test-scoped dependencies are left out.
 */
// shortcut: no real build model; Gradle projectDir remaps in settings.gradle and Maven version/profile activation
// are not read, and an exclusion drops that module from the whole closure, not only below its own dependency.
final class Modules {

    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern SKIPPED = Pattern.compile("<(parent|dependencyManagement|build|reporting)\\b[^>]*>.*?</\\1>", Pattern.DOTALL);
    private static final Pattern PARENT = Pattern.compile("<parent\\b[^>]*>(.*?)</parent>", Pattern.DOTALL);
    private static final Pattern DEPENDENCY = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern EXCLUSIONS = Pattern.compile("<exclusions>(.*?)</exclusions>", Pattern.DOTALL);
    private static final Pattern ARTIFACT = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    private static final Pattern TEST_SCOPE = Pattern.compile("<scope>\\s*test\\s*</scope>");
    private static final Pattern CODE_COMMENT = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern GRADLE_PROJECT = Pattern.compile("(\\w+)\\s*\\(?\\s*project\\s*\\(\\s*(?:path\\s*[:=]\\s*)?[\"'](:[^\"']*)[\"']");

    private final Map<Path, Set<Path>> direct = new HashMap<>();
    private final Map<Path, Set<Path>> excluded = new HashMap<>();
    private final Map<Path, Path> parent = new HashMap<>();

    private Modules() {}

    static Modules read(Repo repo) {
        var m = new Modules();
        var poms = new HashMap<Path, String>();
        var artifacts = new HashMap<String, Path>();
        try {
            for (Path f : repo.files(repo.root())) {
                String name = f.getFileName().toString();
                String rel = "/" + repo.relative(f);
                if (rel.contains("/src/") || rel.contains("/target/") || rel.contains("/build/")) continue; // fixtures and build output
                try {
                    if (name.equals("pom.xml")) {
                        String pom = XML_COMMENT.matcher(Files.readString(f)).replaceAll(" ");
                        poms.put(f.getParent(), pom);
                        first(ARTIFACT, SKIPPED.matcher(pom).replaceAll(" ")).ifPresent(a -> artifacts.putIfAbsent(a, f.getParent()));
                    } else if (name.equals("build.gradle") || name.equals("build.gradle.kts")) {
                        m.gradle(repo, f.getParent(), Files.readString(f));
                    }
                } catch (IOException | RuntimeException e) {
                    System.err.println("warn: cannot read " + f + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("warn: cannot list " + repo.root() + ": " + e.getMessage());
        }
        poms.forEach((dir, pom) -> m.maven(dir, pom, artifacts));
        return m;
    }

    private void maven(Path dir, String pom, Map<String, Path> artifacts) {
        Matcher p = PARENT.matcher(pom);
        if (p.find()) first(ARTIFACT, p.group(1)).map(artifacts::get).filter(d -> !d.equals(dir)).ifPresent(d -> parent.put(dir, d));
        Matcher d = DEPENDENCY.matcher(SKIPPED.matcher(pom).replaceAll(" "));
        while (d.find()) {
            String dep = d.group(1);
            if (TEST_SCOPE.matcher(dep).find()) continue;
            Matcher ex = EXCLUSIONS.matcher(dep);
            if (ex.find()) {
                Matcher a = ARTIFACT.matcher(ex.group(1));
                while (a.find()) if (artifacts.containsKey(a.group(1))) excluded.computeIfAbsent(dir, k -> new HashSet<>()).add(artifacts.get(a.group(1)));
            }
            first(ARTIFACT, EXCLUSIONS.matcher(dep).replaceAll(" ")).map(artifacts::get).filter(m -> !m.equals(dir))
                    .ifPresent(m -> direct.computeIfAbsent(dir, k -> new LinkedHashSet<>()).add(m));
        }
    }

    private void gradle(Repo repo, Path dir, String build) {
        Matcher m = GRADLE_PROJECT.matcher(CODE_COMMENT.matcher(build).replaceAll(" "));
        while (m.find()) {
            if (m.group(1).startsWith("test")) continue;
            Path dep = repo.root().resolve(m.group(2).substring(1).replace(':', '/')).normalize();
            if (!dep.equals(dir)) direct.computeIfAbsent(dir, k -> new LinkedHashSet<>()).add(dep);
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
     * nor modules its own pom (or a parent pom) excludes.
     */
    Set<Path> all(Path module, Set<Path> stops) {
        var skip = new HashSet<>(stops);
        var seen = new HashSet<Path>();
        for (Path at = module; at != null && seen.add(at); at = parent.get(at)) skip.addAll(excluded.getOrDefault(at, Set.of()));
        skip.add(module);
        var out = new LinkedHashSet<Path>();
        var queue = new ArrayDeque<>(direct(module));
        while (!queue.isEmpty()) {
            Path m = queue.pop();
            if (skip.contains(m) || !out.add(m)) continue;
            queue.addAll(direct(m));
        }
        return out;
    }

    private static java.util.Optional<String> first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? java.util.Optional.of(m.group(1)) : java.util.Optional.empty();
    }
}
