package io.github.sunjirapath.ssg.output;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.sunjirapath.ssg.model.Graph;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;

/** {@code graph.json}: nodes, edges (with evidence and confidence) and findings. */
public final class JsonOutput {
    public static final int MAX_SNAPSHOT_BYTES = 32 * 1024 * 1024;

    private JsonOutput() {}

    public static void write(Graph graph, Path out) throws IOException {
        var indent = new DefaultIndenter("  ", "\n");
        var printer = new DefaultPrettyPrinter().withObjectIndenter(indent).withArrayIndenter(indent);
        String json = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writer(printer).writeValueAsString(graph);
        TextFile.write(out.resolve("graph.json"), json + "\n");
    }

    /**
     * Reads current or legacy graphs without rewriting IDs. Legacy topics retain unknown broker
     * provenance because old nodes may have collapsed several brokers into one.
     * {@code path} is the output directory or the graph.json file.
     */
    public static Graph read(Path path) throws IOException {
        Path file = java.nio.file.Files.isDirectory(path) ? path.resolve("graph.json") : path;
        return parseSnapshot(readSnapshot(file));
    }

    /** Bounded even if a file grows between the metadata check and the read. */
    public static byte[] readSnapshot(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_SNAPSHOT_BYTES)
            throw new IOException("snapshot must be a regular file of at most 33554432 bytes");
        try (var in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(MAX_SNAPSHOT_BYTES + 1);
            if (bytes.length > MAX_SNAPSHOT_BYTES) throw new IOException("snapshot exceeds 33554432 bytes");
            return bytes;
        }
    }

    public static Graph parseSnapshot(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_SNAPSHOT_BYTES) throw new IOException("invalid graph snapshot");
        try {
            // Unknown fields are ignored so a snapshot from a newer version (additive fields) still loads.
            Graph graph = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(bytes, Graph.class);
            if (graph == null || graph.nodes() == null || graph.edges() == null || graph.findings() == null)
                throw new IOException("invalid graph snapshot");
            var ids = new java.util.HashSet<String>();
            for (var node : graph.nodes()) if (node == null || node.id() == null || !ids.add(node.id()))
                throw new IOException("invalid graph snapshot");
            for (var edge : graph.edges()) if (edge == null || edge.kind() == null || edge.confidence() == null
                    || !ids.contains(edge.from()) || !ids.contains(edge.to()) || edge.attrs() == null || edge.evidence() == null)
                throw new IOException("invalid graph snapshot");
            for (var finding : graph.findings()) if (finding == null || finding.kind() == null || finding.service() == null
                    || finding.message() == null || finding.evidence() == null)
                throw new IOException("invalid graph snapshot");
            return graph;
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            // Jackson diagnostics include input snippets and values (possibly credentials).
            throw new IOException("invalid graph snapshot");
        }
    }
}
