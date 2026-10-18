package io.github.sunjirapath.ssg.output;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.sunjirapath.ssg.model.Graph;

import java.io.IOException;
import java.nio.file.Path;

/** {@code graph.json}: nodes, edges (with evidence and confidence) and findings. */
public final class JsonOutput {

    private JsonOutput() {}

    public static void write(Graph graph, Path out) throws IOException {
        var indent = new DefaultIndenter("  ", "\n");
        var printer = new DefaultPrettyPrinter().withObjectIndenter(indent).withArrayIndenter(indent);
        String json = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writer(printer).writeValueAsString(graph);
        TextFile.write(out.resolve("graph.json"), json + "\n");
    }

    /** Reads a graph written by {@link #write}; {@code path} is the output directory or the graph.json file. */
    public static Graph read(Path path) throws IOException {
        Path file = java.nio.file.Files.isDirectory(path) ? path.resolve("graph.json") : path;
        return new ObjectMapper().readValue(file.toFile(), Graph.class);
    }
}
