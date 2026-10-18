package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.scan.Discovery;
import io.github.sunjirapath.ssg.scan.JavaIndex;
import io.github.sunjirapath.ssg.scan.Repo;
import io.github.sunjirapath.ssg.scan.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/** The synthetic fleet in src/test/resources/fleet. */
public final class TestFleet {

    public static final Path ROOT = Path.of("src/test/resources/fleet");
    public static final List<String> REPOS = List.of("account", "gateway", "notification");

    private static Pipeline.Result result;

    private TestFleet() {}

    public static synchronized Pipeline.Result scan() {
        if (result == null) {
            try {
                result = Pipeline.scan(new Pipeline.Options(REPOS.stream().map(ROOT::resolve).toList(), "default", null, null));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return result;
    }

    public static Graph graph() {
        return scan().graph();
    }

    public static Service service(String repo, String name) {
        var index = JavaIndex.build(Repo.of(ROOT.resolve(repo)));
        return Discovery.discover(index, "default", null).stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no service " + name + " in " + repo));
    }
}
