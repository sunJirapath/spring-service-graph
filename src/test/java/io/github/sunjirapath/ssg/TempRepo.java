package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.extract.Extractor;
import io.github.sunjirapath.ssg.extract.ReachAnalysis;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.scan.Discovery;
import io.github.sunjirapath.ssg.scan.JavaIndex;
import io.github.sunjirapath.ssg.scan.Repo;
import io.github.sunjirapath.ssg.scan.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds a throwaway repo on disk for a test and scans it.
 *
 * <pre>{@code
 * var repo = TempRepo.at(tmp.resolve("orders")).app()
 *         .java("x/C.java", "package x; @RestController class C { @GetMapping(\"/a\") void a() {} }")
 *         .resource("application.yml", "spring.application.name: orders");
 * var facts = repo.facts(new InboundHttpExtractor());
 * }</pre>
 * Paths given to {@link #java} are under {@code src/main/java}, to {@link #resource} under
 * {@code src/main/resources}, to {@link #file} relative to the repo root (use it for modules,
 * tests, build dirs). Fake sources need no imports: annotations match by simple name.
 */
public final class TempRepo {

    private final Path root;

    private TempRepo(Path root) {
        this.root = root;
    }

    /** The directory name becomes the repo name in evidence. */
    public static TempRepo at(Path root) {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new TempRepo(root);
    }

    public Path root() {
        return root;
    }

    /** Adds {@code src/main/java/app/App.java} annotated {@code @SpringBootApplication}. */
    public TempRepo app() {
        return java("app/App.java", "package app; @SpringBootApplication public class App {}");
    }

    public TempRepo java(String path, String code) {
        return file("src/main/java/" + path, code);
    }

    public TempRepo resource(String path, String text) {
        return file("src/main/resources/" + path, text);
    }

    public TempRepo file(String path, String text) {
        return bytes(path, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Raw bytes, for BOMs, odd encodings or binary junk. */
    public TempRepo bytes(String path, byte[] content) {
        try {
            Path f = root.resolve(path);
            Files.createDirectories(f.getParent());
            Files.write(f, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public JavaIndex index() {
        return JavaIndex.build(Repo.of(root));
    }

    public List<Service> services() {
        return services("default", null);
    }

    public List<Service> services(String profile, Path configRepo) {
        return Discovery.discover(index(), profile, configRepo);
    }

    /** The only service; fails if there is not exactly one. */
    public Service service() {
        var all = services();
        if (all.size() != 1) throw new AssertionError("expected one service in " + root + " but found " + all.stream().map(Service::name).toList());
        return all.getFirst();
    }

    public Service service(String name) {
        return services().stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no service " + name + " in " + root));
    }

    /** Facts of one extractor on the only service. */
    public List<Fact> facts(Extractor extractor) {
        return extractor.extract(service());
    }

    /** All extractors plus reach analysis on the only service, as the pipeline runs them. */
    public List<Fact> allFacts() {
        Service s = service();
        var facts = new ArrayList<Fact>();
        for (Extractor x : Extractor.all()) facts.addAll(x.extract(s));
        facts.addAll(ReachAnalysis.analyze(s, facts));
        return facts;
    }

    /** Full pipeline (discover, extract, reach, link) over several repos, default profile, no aliases. */
    public static Graph scan(TempRepo... repos) {
        return scan(new Pipeline.Options(Arrays.stream(repos).map(TempRepo::root).toList(), "default", null, null));
    }

    public static Graph scan(Pipeline.Options options) {
        try {
            return Pipeline.scan(options).graph();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
