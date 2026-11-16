package io.github.sunjirapath.ssg.real;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sunjirapath.ssg.CliProcess;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.output.JsonOutput;
import org.junit.jupiter.api.Assumptions;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

final class RealProjects {
    record Project(String name, Path path, String sha, String bootVersion) {
        @Override public String toString() { return name + " @ " + sha; }
        boolean unsupported() { return bootVersion.startsWith("2."); }
    }

    static Stream<Project> projects() throws Exception {
        String env = System.getenv("REAL_PROJECTS_DIR");
        Assumptions.assumeTrue(env != null && !env.isBlank(), "REAL_PROJECTS_DIR is unset; offline real-project tests skipped");
        Assumptions.assumeTrue(Files.isDirectory(Path.of(env)), "REAL_PROJECTS_DIR is missing; real-project tests skipped");
        Path root = Path.of(env).toRealPath(), manifest = root.resolve("MANIFEST.json");
        Assumptions.assumeTrue(Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS), "MANIFEST.json is missing; real-project tests skipped");
        var projects = new ObjectMapper().readTree(Files.readString(manifest)).path("projects");
        assertThat(projects.isArray()).as("manifest.projects must be an array").isTrue();
        var out = new ArrayList<Project>();
        for (var entry : projects) {
            String name = entry.path("name").asText();
            assertThat(name).matches("[A-Za-z0-9_-]+");
            Path path = root.resolve(entry.path("path").asText()).normalize();
            assertThat(path.startsWith(root)).as("manifest project must stay under REAL_PROJECTS_DIR").isTrue();
            out.add(new Project(name, path, entry.path("sha").asText(), entry.path("bootVersion").asText()));
        }
        Assumptions.assumeFalse(out.isEmpty(), "manifest has no projects; real-project tests skipped");
        return out.stream();
    }

    static Path copy(Project project, Path work) throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(project.path()), "missing real project: " + project.name());
        Path source = project.path().toRealPath();
        assertThat(source.startsWith(project.path().getParent().toRealPath())).as("project link cannot escape manifest root").isTrue();
        Path copy = work.resolve("copy").resolve(project.name());
        Files.createDirectories(copy);
        for (Path relative : tree(source).keySet()) {
            Path target = copy.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.copy(source.resolve(relative), target);
        }
        return copy;
    }

    static Stream<Project> supportedProjects() throws Exception { return projects().filter(p -> !p.unsupported()); }

    static Stream<Project> fleets() throws Exception {
        return projects().filter(p -> p.name().equals("spring-petclinic-microservices"));
    }

    /** Only scanner inputs; no VCS, wrappers, private keys, dotenv files or build outputs are read/copied. */
    static SortedMap<Path, String> tree(Path root) throws Exception {
        var tree = new TreeMap<Path, String>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String n = dir.getFileName().toString();
                return excluded(n) || !dir.toRealPath().startsWith(root.toRealPath())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (!attrs.isRegularFile() || excluded(name) || !input(name)) return FileVisitResult.CONTINUE;
                if (!file.toRealPath().startsWith(root.toRealPath())) throw new IOException("input escapes checkout");
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    tree.put(root.relativize(file), bytes.length + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
                return FileVisitResult.CONTINUE;
            }
        });
        return tree;
    }

    private static boolean excluded(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return Set.of(".git", ".gradle", ".secrets", ".ssh", "node_modules", "target", "build", "gradle", "gradlew", "gradlew.bat").contains(n)
                || n.startsWith(".env") || n.endsWith("_ssh.env") || n.endsWith(".pem") || n.endsWith(".key")
                || n.endsWith(".p12") || n.endsWith(".jks") || n.startsWith("id_rsa") || n.startsWith("id_ed25519");
    }

    private static boolean input(String name) {
        return name.matches(".*\\.(java|kt|ya?ml|properties|sql|xml|json|gradle|gradle\\.kts|factories|imports)$");
    }

    static CliProcess.Result cli(Path work, String stdin, String... args) throws Exception {
        var result = CliProcess.run(work, Duration.ofSeconds(60), stdin, args);
        assertThat(result.timedOut()).as("Main completed within 60 seconds").isFalse();
        return result;
    }

    static Graph scan(Path copy, Path out, Path process, Path... additional) throws Exception {
        var args = new ArrayList<>(List.of("scan", "--repos", copy.toString()));
        for (Path repo : additional) args.add(repo.toString());
        args.addAll(List.of("--out", out.toString()));
        var result = cli(process, "", args.toArray(String[]::new));
        assertThat(result.code()).as("Main scan exit status").isZero();
        return JsonOutput.read(out);
    }

    static SortedMap<Path, byte[]> output(Path out) throws Exception {
        var result = new TreeMap<Path, byte[]>();
        try (var files = Files.walk(out)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) result.put(out.relativize(file), Files.readAllBytes(file));
        }
        return result;
    }

    static Set<Path> paths(Path root) throws Exception {
        try (var files = Files.walk(root)) { return new TreeSet<>(files.map(root::relativize).toList()); }
    }

    private RealProjects() {}
}
