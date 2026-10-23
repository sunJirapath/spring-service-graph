package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.extract.Extractor;
import io.github.sunjirapath.ssg.extract.ReachAnalysis;
import io.github.sunjirapath.ssg.link.Linker;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.scan.Discovery;
import io.github.sunjirapath.ssg.scan.JavaIndex;
import io.github.sunjirapath.ssg.scan.Repo;
import io.github.sunjirapath.ssg.scan.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** scan = discover services, run every extractor, link. */
public final class Pipeline {

    /** @param sqlMethods in-house wrapper methods that take SQL ({@code method} or {@code Type.method}) */
    public record Options(List<Path> repos, String profile, Path aliases, Path configRepo, List<String> sqlMethods) {
        public Options {
            sqlMethods = List.copyOf(sqlMethods);
        }

        public Options(List<Path> repos, String profile, Path aliases, Path configRepo) {
            this(repos, profile, aliases, configRepo, List.of());
        }
    }

    /** @param repoRoots repo name -> checkout root, for evidence links */
    public record Result(Graph graph, Map<String, Path> repoRoots) {}

    private Pipeline() {}

    public static Result scan(Options o) throws IOException {
        var roots = new TreeMap<String, Path>();
        var all = new ArrayList<Linker.ServiceFacts>();
        var extra = new ArrayList<Finding>();
        var seen = new TreeMap<String, Service>();
        if (o.configRepo() != null) addRoot(roots, Repo.of(o.configRepo()));
        for (Path p : o.repos()) addRoot(roots, Repo.of(p));
        for (Path p : o.repos()) {
            Repo repo = Repo.of(p);
            JavaIndex index = JavaIndex.build(repo);
            List<Service> services = Discovery.discover(index, o.profile(), o.configRepo());
            if (services.isEmpty()) System.err.println("warn: no Spring Boot app (@SpringBootApplication or SpringApplication in main) in " + repo.root());
            for (Service s : services) {
                Service previous = seen.put(s.name(), s);
                String prev = previous == null ? null : previous.repo().name() + "/" + previous.path();
                if (prev != null) {
                    System.err.println("warn: service name '" + s.name() + "' found twice: " + prev + " and " + repo.name() + "/" + s.path());
                    // two apps with one name merge into one graph node; say so instead of hiding it
                    extra.add(new Finding(Finding.Kind.DUPLICATE_SERVICE, s.name(), "service name '" + s.name()
                            + "' is used by " + prev + " and " + repo.name() + "/" + s.path(),
                            List.of(previous.nameEvidence(), s.nameEvidence())));
                }
                var facts = new ArrayList<Fact>();
                for (Extractor x : Extractor.all(java.util.Set.copyOf(o.sqlMethods()))) facts.addAll(x.extract(s));
                facts.addAll(ReachAnalysis.analyze(s, facts));
                all.add(new Linker.ServiceFacts(s.name(), repo.name(), s.path(), facts));
            }
            for (JavaIndex.Problem pr : index.problems()) {
                String owner = services.stream().filter(s -> pr.file().toAbsolutePath().normalize().startsWith(s.moduleRoot()))
                        .map(Service::name).findFirst().orElse(repo.name());
                extra.add(new Finding(Finding.Kind.PARSE_ERROR, owner, pr.message(), List.of(repo.evidence(pr.file(), pr.line()))));
            }
        }
        return new Result(Linker.link(all, aliases(o.aliases()), extra), roots);
    }

    private static void addRoot(Map<String, Path> roots, Repo repo) {
        Path previous = roots.putIfAbsent(repo.name(), repo.root());
        if (previous != null && !previous.equals(repo.root()))
            throw new IllegalArgumentException("repository name '" + repo.name() + "' is ambiguous: "
                    + previous + " and " + repo.root() + "; use distinct checkout directory names");
    }

    /** aliases.yml: a flat map of host -> service name (or a name for an external system). */
    static Map<String, String> aliases(Path file) throws IOException {
        if (file == null) return Map.of();
        Object loaded = new Yaml().load(Files.readString(file));
        var out = new TreeMap<String, String>();
        if (loaded instanceof Map<?, ?> m) m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        else if (loaded != null) throw new IllegalArgumentException(file + ": expected a map of host: target");
        return out;
    }
}
