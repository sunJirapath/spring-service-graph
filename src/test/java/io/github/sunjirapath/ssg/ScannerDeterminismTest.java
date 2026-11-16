package io.github.sunjirapath.ssg;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import io.github.sunjirapath.ssg.link.Linker;
import io.github.sunjirapath.ssg.model.*;
import io.github.sunjirapath.ssg.output.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ScannerDeterminismTest {
    @TempDir(factory = WorkspaceTempDirFactory.class) Path tmp;

    @Test
    void fileCreationAndRepositoryOrderProduceIdenticalReportTrees() throws Exception {
        var first = fixture(tmp.resolve("one/orders"), false);
        var second = fixture(tmp.resolve("two/orders"), true);
        var caller = TempRepo.at(tmp.resolve("caller")).app().java("app/Client.java", """
                package app; @Component class Client {
                    RestTemplate restTemplate;
                    void call() { restTemplate.getForObject("http://orders/สินค้า", String.class); }
                }
                """);
        Graph expected = TempRepo.scan(first, caller);
        Graph actual = TempRepo.scan(caller, second);
        assertThat(expected.nodes()).filteredOn(Node.Endpoint.class::isInstance).hasSize(2);
        assertThat(expected.edges()).anyMatch(e -> e.kind() == Edge.Kind.CALLS
                && e.to().equals(Node.Endpoint.idOf("orders", "GET", "/สินค้า")));
        assertThat(actual).isEqualTo(expected);
        assertTreesEqual(render(expected, tmp.resolve("out-one")), render(actual, tmp.resolve("out-two")));
        // Rewriting an existing report tree is the idempotence property.
        assertTreesEqual(render(expected, tmp.resolve("out-one")), render(actual, tmp.resolve("out-one")));
    }

    @Test
    void shuffledFactsAndAliasEntriesHaveCanonicalEdgesAndEvidence() throws Exception {
        var facts = new ArrayList<Fact>();
        for (int i = 0; i < 20; i++) {
            facts.add(new Fact.TopicUse(true, Value.exact("orders.created"),
                    new Evidence("repo", "app/Events.java", i + 1), "kafka-template", "kafka"));
            facts.add(new Fact.Call("rest-template", "GET", Value.exact("http://api/items"),
                    new Evidence("repo", "app/Client.java", i + 1)));
        }
        var aliases = new LinkedHashMap<String, String>();
        aliases.put("api", "vendor");
        aliases.put("unused", "other");
        Graph expected = Linker.link(List.of(new Linker.ServiceFacts("orders", "repo", ".", facts)), aliases, List.of());
        Collections.shuffle(facts, new Random(4217));
        var reverse = new LinkedHashMap<String, String>();
        reverse.put("unused", "other");
        reverse.put("api", "vendor");
        Graph actual = Linker.link(List.of(new Linker.ServiceFacts("orders", "repo", ".", facts)), reverse, List.of());
        assertThat(expected.edges()).hasSize(2).allSatisfy(e -> assertThat(e.evidence()).hasSize(20).isSorted());
        assertThat(actual).isEqualTo(expected);
        assertTreesEqual(render(expected, tmp.resolve("first")), render(actual, tmp.resolve("second")));
    }

    // FINDING: shared-table finding evidence retains service input order instead of canonical order.
    @Test
    void sharedTableFindingsAreIndependentOfServiceOrder() {
        var a = new Linker.ServiceFacts("a", "a", ".", List.of(new Fact.TableUse(true,
                Value.exact("shared"), "sql", new Evidence("a", "schema.sql", 1), "migration")));
        var b = new Linker.ServiceFacts("b", "b", ".", List.of(new Fact.TableUse(true,
                Value.exact("shared"), "sql", new Evidence("b", "schema.sql", 1), "migration")));
        Graph expected = Linker.link(List.of(a, b), Map.of(), List.of());
        assertThat(expected.findings()).singleElement().satisfies(f -> assertThat(f.kind()).isEqualTo(Finding.Kind.SHARED_TABLE));
        assertThat(Linker.link(List.of(b, a), Map.of(), List.of())).isEqualTo(expected);
    }

    @Test
    void concurrentScansAndIndependentOutputWritersAreIdentical() throws Exception {
        var repo = fixture(tmp.resolve("orders"), false);
        var expected = render(TempRepo.scan(repo), tmp.resolve("baseline"));
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<SortedMap<Path, byte[]>>>();
            for (int i = 0; i < 8; i++) {
                Path out = tmp.resolve("parallel-" + i);
                tasks.add(() -> render(TempRepo.scan(repo), out));
            }
            for (var future : pool.invokeAll(tasks, 60, TimeUnit.SECONDS))
                assertTreesEqual(expected, future.get());
        }
    }

    // FINDING: Nacos seed discovery lowercases CONFIG.sql with the default locale and skips it under tr-TR.
    @Test
    @ResourceLock("java.util.Locale.default")
    @ResourceLock("java.util.TimeZone.default")
    void reportsAreIndependentOfTurkishThaiLocaleAndTimezone() throws Exception {
        var repo = fixture(tmp.resolve("orders"), false);
        Locale original = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY), format = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone zone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.ROOT);
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            var expected = render(TempRepo.scan(repo), tmp.resolve("root"));
            for (String tag : List.of("th-TH", "tr-TR")) {
                Locale.setDefault(Locale.forLanguageTag(tag));
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Bangkok"));
                assertTreesEqual(expected, render(TempRepo.scan(repo), tmp.resolve(tag)));
            }
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
            TimeZone.setDefault(zone);
        }
    }

    @Test
    void freshJvmDefaultCharsetDoesNotChangeUtf8Reports() throws Exception {
        var repo = fixture(tmp.resolve("orders"), false);
        var classpath = new ArrayList<String>();
        for (Class<?> type : List.of(ScannerDeterminismTest.class, Main.class, JavaParser.class,
                Yaml.class, ObjectMapper.class, JsonFactory.class, JsonIgnore.class))
            classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Path java = Path.of(System.getProperty("java.home"), "bin", File.separatorChar == '\\' ? "java.exe" : "java");
        SortedMap<Path, byte[]> expected = null;
        for (String encoding : List.of("UTF-8", "US-ASCII")) {
            Path out = tmp.resolve(encoding), probe = tmp.resolve(encoding + ".charset"), log = tmp.resolve(encoding + ".log");
            var command = List.of(java.toString(), "-Xmx512m", "-Dfile.encoding=" + encoding, "-cp",
                    String.join(File.pathSeparator, classpath), CharsetProbe.class.getName(), probe.toString(),
                    "scan", "--repos", repo.root().toString(), "--out", out.toString());
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("fresh JVM completed").isTrue();
                assertThat(process.exitValue()).as(Files.readString(log)).isZero();
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
            }
            assertThat(Files.readString(probe)).isEqualTo(encoding);
            var actual = tree(out);
            if (expected == null) expected = actual;
            else assertTreesEqual(expected, actual);
        }
    }

    public static class CharsetProbe {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), Charset.defaultCharset().name());
            System.exit(Main.run(Arrays.copyOfRange(args, 1, args.length)));
        }
    }

    private static TempRepo fixture(Path root, boolean reverse) {
        var files = new LinkedHashMap<String, String>();
        files.put("src/main/java/app/App.java", "package app; @SpringBootApplication class App {}");
        files.put("src/main/resources/application.properties", "spring.application.name=orders\n");
        files.put("CONFIG.sql", "INSERT INTO config_info (data_id,content) VALUES ('orders.properties','bus.topic=orders.created');\n");
        files.put("src/main/java/app/Events.java", "package app; @Component class Events { @KafkaListener(topics=\"${bus.topic}\") void receive(String s) {} }");
        files.put("src/main/java/app/สินค้า.java", "package app; @RestController class สินค้า { @GetMapping(\"/สินค้า\") void get() {} }");
        files.put("src/main/java/app/Other.java", "package app; @RestController class Other { @GetMapping(\"/مرحبا/😀\") void get() {} }");
        files.put("src/main/java/app/Item.java", "package app; @Entity @Table(name=\"items\") class Item {}");
        var entries = new ArrayList<>(files.entrySet());
        if (reverse) Collections.reverse(entries);
        var repo = TempRepo.at(root);
        entries.forEach(e -> repo.file(e.getKey(), e.getValue()));
        return repo;
    }

    private static SortedMap<Path, byte[]> render(Graph graph, Path out) throws Exception {
        JsonOutput.write(graph, out);
        MarkdownOutput.write(graph, Map.of(), out);
        DiagramOutput.write(graph, out);
        return tree(out);
    }

    private static SortedMap<Path, byte[]> tree(Path root) throws Exception {
        var result = new TreeMap<Path, byte[]>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) result.put(root.relativize(file), Files.readAllBytes(file));
        }
        return result;
    }

    private static void assertTreesEqual(SortedMap<Path, byte[]> expected, SortedMap<Path, byte[]> actual) {
        assertThat(actual.keySet()).containsExactlyElementsOf(expected.keySet());
        expected.forEach((file, bytes) -> assertThat(actual.get(file)).as(file.toString()).isEqualTo(bytes));
    }
}
