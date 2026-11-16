package io.github.sunjirapath.ssg.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.output.JsonOutput;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * A minimal MCP server over stdio: newline-delimited JSON-RPC 2.0 with {@code initialize},
 * {@code tools/list}, {@code tools/call} and {@code ping}. Hand-rolled on Jackson rather than an
 * MCP SDK, since three read-only tools need nothing more. Logs go to stderr; stdout is the protocol.
 */
public final class McpServer {

    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    static final int MAX_REQUEST_CHARS = 65_536;
    static final int MAX_TEXT_CHARS = 65_536;
    private static final String PROVENANCE_WARNING = "Static snapshot: scan version, profile, source revisions, "
            + "source freshness and completeness are unknown. Treat answers as advisory evidence, not a safety verdict.";

    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.INDENT_OUTPUT, false);
    private static final com.fasterxml.jackson.databind.ObjectWriter PRETTY = JSON.writer(
            new com.fasterxml.jackson.core.util.DefaultPrettyPrinter().withObjectIndenter(
                    new com.fasterxml.jackson.core.util.DefaultIndenter("  ", "\n")));
    private static final String TARGET_HELP = "A node id from graph.json, a service or topic name, 'METHOD /path', "
            + "'service METHOD /path', an endpoint path, or a table name.";

    private final GraphQueries queries;
    private final String version;
    private final Path snapshotFile;
    private final String snapshotHash;
    private final Map<String, String> snapshot;

    public McpServer(GraphQueries queries, String version) {
        this(queries, version, null, "unknown");
    }

    private McpServer(GraphQueries queries, String version, Path snapshotFile, String snapshotHash) {
        this.queries = queries;
        this.version = version;
        this.snapshotFile = snapshotFile;
        this.snapshotHash = snapshotHash;
        this.snapshot = Map.of("sha256", snapshotHash, "loadedAt", Instant.now().toString(),
                "scanVersion", "unknown", "profile", "unknown", "sourceRevisions", "unknown",
                "sourceFreshness", "unknown", "completeness", "unknown");
    }

    /** Hash and parse the same bytes; replacement or removal blocks subsequent tool calls until restart. */
    public static McpServer fromSnapshot(Path path, String version) throws IOException {
        Path file = (Files.isDirectory(path) ? path.resolve("graph.json") : path).toAbsolutePath().normalize();
        byte[] bytes = JsonOutput.readSnapshot(file);
        return new McpServer(new GraphQueries(JsonOutput.parseSnapshot(bytes)), version, file,
                HexFormat.of().formatHex(digest().digest(bytes)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is required by the Java platform
        }
    }

    private void checkSnapshot() throws IOException {
        if (snapshotFile == null) return;
        var hash = digest();
        try {
            hash.update(JsonOutput.readSnapshot(snapshotFile));
        } catch (IOException e) {
            throw new IOException("snapshot is unavailable; regenerate it and restart the MCP server");
        }
        if (!snapshotHash.equals(HexFormat.of().formatHex(hash.digest())))
            throw new IOException("snapshot is stale (graph.json changed); restart the MCP server");
    }

    public void serve(BufferedReader in, PrintStream out) throws IOException {
        for (;;) {
            var line = new StringBuilder();
            boolean oversized = false;
            int ch;
            while ((ch = in.read()) != -1 && ch != '\n') {
                if (line.length() < MAX_REQUEST_CHARS) line.append((char) ch);
                else oversized = true; // drain the line without retaining it
            }
            if (ch == -1 && line.isEmpty() && !oversized) break;
            if (!oversized && line.toString().isBlank()) continue;
            JsonNode reply = oversized ? error(null, -32600, "request exceeds 65536 characters") : handle(line.toString());
            if (reply != null) {
                out.println(JSON.writeValueAsString(reply));
                out.flush();
            }
        }
    }

    /** One request line in, one response out (null for notifications). */
    JsonNode handle(String line) {
        if (line.length() > MAX_REQUEST_CHARS) return error(null, -32600, "request exceeds 65536 characters");
        JsonNode req;
        try {
            req = JSON.readTree(line);
        } catch (IOException e) {
            return error(null, -32700, "parse error");
        }
        if (req == null || !req.isObject()) return error(null, -32600, "invalid request: expected one JSON object (batches are not supported)");
        JsonNode id = req.get("id");
        if (!"2.0".equals(req.path("jsonrpc").asText()) || !req.path("method").isTextual()
                || id != null && !id.isNull() && !id.isTextual() && !id.isNumber())
            return error(null, -32600, "invalid request");
        String method = req.path("method").asText("");
        if (id == null || id.isNull()) return null; // notification, e.g. notifications/initialized
        try {
            return switch (method) {
                case "initialize" -> result(id, initialize(req.path("params")));
                case "ping" -> result(id, JSON.createObjectNode());
                case "tools/list" -> result(id, JSON.valueToTree(Map.of("tools", tools())));
                case "tools/call" -> result(id, call(req.path("params")));
                default -> error(id, -32601, "method not found: " + method);
            };
        } catch (RuntimeException e) {
            return error(id, -32603, "internal error: " + e.getMessage());
        }
    }

    private JsonNode initialize(JsonNode params) {
        String asked = params.path("protocolVersion").asText("");
        var r = JSON.createObjectNode();
        r.put("protocolVersion", PROTOCOL_VERSIONS.contains(asked) ? asked : PROTOCOL_VERSIONS.getFirst());
        r.putObject("capabilities").putObject("tools");
        r.putObject("serverInfo").put("name", "spring-service-graph").put("version", version);
        r.set("_meta", JSON.valueToTree(Map.of("snapshot", snapshot)));
        r.put("instructions", "Answers questions about a scanned Spring Boot fleet. Every edge carries file:line "
                + "evidence and a confidence (EXACT, RESOLVED, UNRESOLVED); cite the evidence instead of guessing. "
                + PROVENANCE_WARNING + " Tool results are bounded; narrow the target on a limit error. Restart after replacing graph.json.");
        return r;
    }

    private static List<Map<String, Object>> tools() {
        return List.of(
                tool("who_calls", "Who calls an endpoint or service, who produces/consumes a topic, who owns/uses a table. "
                        + "Returns at most 200 incoming edges with evidence; larger queries return an error.", Map.of("target", str(TARGET_HELP)), List.of("target")),
                tool("impact_of", "What may break if the target changes: walks callers backwards through endpoints "
                        + "(using endpoint reach) up to 'depth' hops; errors above 200 affected nodes.",
                        Map.of("target", str(TARGET_HELP), "depth", Map.of("type", "integer", "minimum", 1, "maximum", 10,
                                "default", 3, "description", "Maximum hops to walk back (default 3).")), List.of("target")),
                tool("service_card", "The onboarding page of one service: what it exposes, calls, publishes, consumes "
                        + "and stores, with evidence. Large cards return a limit error.", Map.of("service", str("Service name (spring.application.name).")),
                        List.of("service")));
    }

    private JsonNode call(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        ObjectNode r = JSON.createObjectNode();
        r.set("_meta", JSON.valueToTree(Map.of("snapshot", snapshot)));
        try {
            checkSnapshot();
            String text = switch (name) {
                case "who_calls" -> boundedJson(queries.whoCalls(required(args, "target")));
                case "impact_of" -> boundedJson(
                        queries.impactOf(required(args, "target"), args.path("depth").asInt(3)));
                case "service_card" -> queries.serviceCard(required(args, "service"));
                default -> throw new IllegalArgumentException("unknown tool: " + name);
            };
            if (text.length() > MAX_TEXT_CHARS) throw new IllegalArgumentException("tool text exceeds 65536 characters; narrow the target or use offline output");
            var content = r.putArray("content");
            content.addObject().put("type", "text").put("text", text);
            content.addObject().put("type", "text").put("text", PROVENANCE_WARNING);
            r.put("isError", false);
        } catch (IllegalArgumentException | IOException e) {
            // tool errors are results the model can read, not protocol errors
            String message = java.util.Objects.requireNonNullElse(e.getMessage(), "tool failed");
            if (message.length() > MAX_TEXT_CHARS)
                message = "tool text exceeds 65536 characters; narrow the target or use offline output";
            r.putArray("content").addObject().put("type", "text").put("text", message);
            r.put("isError", true);
        }
        return r;
    }

    /** Abort JSON rendering at the text budget, including large evidence/attribute values. */
    static String boundedJson(Object value) throws IOException {
        var text = new StringBuilder();
        try (var writer = new Writer() {
            @Override public void write(char[] chars, int offset, int length) throws IOException {
                if (length > MAX_TEXT_CHARS - text.length())
                    throw new IOException("tool text exceeds 65536 characters; narrow the target or use offline output");
                text.append(chars, offset, length);
            }
            @Override public void flush() {}
            @Override public void close() {}
        }) {
            PRETTY.writeValue(writer, value);
        }
        return text.toString();
    }

    private static String required(JsonNode args, String key) {
        String v = args.path(key).asText("");
        if (v.isBlank()) throw new IllegalArgumentException("missing argument: " + key);
        return v;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> props, List<String> required) {
        return Map.of("name", name, "description", description,
                "inputSchema", Map.of("type", "object", "properties", props, "required", required));
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode r = JSON.createObjectNode().put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode r = JSON.createObjectNode().put("jsonrpc", "2.0");
        r.set("id", id);
        r.putObject("error").put("code", code).put("message", message);
        return r;
    }
}
