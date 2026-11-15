package io.github.sunjirapath.ssg;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import org.yaml.snakeyaml.Yaml;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Real Main entry point, with isolated stdio and a killable deadline; never invokes project builds. */
public final class CliProcess {
    public record Result(int code, boolean timedOut, String stdout, String stderr) {}

    public static Result run(Path work, Duration timeout, String input, String... args) throws Exception {
        Files.createDirectories(work);
        var classpath = new ArrayList<String>();
        for (Class<?> type : List.of(Main.class, JavaParser.class, Yaml.class, ObjectMapper.class, JsonFactory.class, JsonIgnore.class))
            classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        var command = new ArrayList<>(List.of(executable.toString(), "-Xmx512m", "-Dfile.encoding=UTF-8", "-cp",
                String.join(File.pathSeparator, classpath), Main.class.getName()));
        command.addAll(List.of(args));
        Path stdout = work.resolve("stdout.log"), stderr = work.resolve("stderr.log"), stdin = work.resolve("stdin.jsonl");
        Files.writeString(stdin, input, StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(command).directory(work.toFile())
                .redirectInput(stdin.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) process.destroyForcibly().waitFor();
            if (Files.size(stdout) > 2_000_000 || Files.size(stderr) > 2_000_000)
                throw new AssertionError("CLI exceeded test log budget");
            return new Result(finished ? process.exitValue() : -1, !finished,
                    Files.readString(stdout), Files.readString(stderr));
        } finally { if (process.isAlive()) process.destroyForcibly().waitFor(); }
    }

    private CliProcess() {}
}
