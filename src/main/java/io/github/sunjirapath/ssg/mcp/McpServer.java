package io.github.sunjirapath.ssg.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/**
 * A minimal MCP server over stdio: newline-delimited JSON-RPC 2.0 with {@code initialize},
 * {@code tools/list}, {@code tools/call} and {@code ping}. Hand-rolled on Jackson rather than an
 * MCP SDK, since three read-only tools need nothing more. Logs go to stderr; stdout is the protocol.
 */
public final class McpServer {

    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.INDENT_OUTPUT, false);
    private static final com.fasterxml.jackson.databind.ObjectWriter PRETTY = JSON.writer(
            new com.fasterxml.jackson.core.util.DefaultPrettyPrinter().withObjectIndenter(
                    new com.fasterxml.jackson.core.util.DefaultIndenter("  ", "\n")));
    private static final String TARGET_HELP = "A node id from graph.json, a service or topic name, 'METHOD /path', "
            + "'service METHOD /path', an endpoint path, or a table name.";

    private final GraphQueries queries;
    private final String version;

    public McpServer(GraphQueries queries, String version) {
        this.queries = queries;
        this.version = version;
    }

    public void serve(BufferedReader in, PrintStream out) throws IOException {
        for (String line; (line = in.readLine()) != null; ) {
            if (line.isBlank()) continue;
            JsonNode reply = handle(line);
            if (reply != null) {
                out.println(JSON.writeValueAsString(reply));
                out.flush();
            }
        }
    }

    /** One request line in, one response out (null for notifications). */
    JsonNode handle(String line) {
        JsonNode req;
        try {
            req = JSON.readTree(line);
        } catch (IOException e) {
            return error(null, -32700, "parse error: " + e.getMessage().lines().findFirst().orElse(""));
        }
        if (!req.isObject()) return error(null, -32600, "invalid request: expected one JSON object (batches are not supported)");
        JsonNode id = req.get("id");
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
        r.put("instructions", "Answers questions about a scanned Spring Boot fleet. Every edge carries file:line "
                + "evidence and a confidence (EXACT, RESOLVED, UNRESOLVED); cite the evidence instead of guessing.");
        return r;
    }

    private static List<Map<String, Object>> tools() {
        return List.of(
                tool("who_calls", "Who calls an endpoint or service, who produces/consumes a topic, who owns/uses a table. "
                        + "Returns the incoming edges with evidence.", Map.of("target", str(TARGET_HELP)), List.of("target")),
                tool("impact_of", "What may break if the target changes: walks callers backwards through endpoints "
                        + "(using endpoint reach) up to 'depth' hops.",
                        Map.of("target", str(TARGET_HELP), "depth", Map.of("type", "integer", "minimum", 1, "maximum", 10,
                                "default", 3, "description", "Maximum hops to walk back (default 3).")), List.of("target")),
                tool("service_card", "The onboarding page of one service: what it exposes, calls, publishes, consumes "
                        + "and stores, with evidence.", Map.of("service", str("Service name (spring.application.name).")),
                        List.of("service")));
    }

    private JsonNode call(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        ObjectNode r = JSON.createObjectNode();
        try {
            String text = switch (name) {
                case "who_calls" -> PRETTY.writeValueAsString(queries.whoCalls(required(args, "target")));
                case "impact_of" -> PRETTY.writeValueAsString(
                        queries.impactOf(required(args, "target"), args.path("depth").asInt(3)));
                case "service_card" -> queries.serviceCard(required(args, "service"));
                default -> throw new IllegalArgumentException("unknown tool: " + name);
            };
            r.putArray("content").addObject().put("type", "text").put("text", text);
            r.put("isError", false);
        } catch (IllegalArgumentException | IOException e) {
            // tool errors are results the model can read, not protocol errors
            r.putArray("content").addObject().put("type", "text").put("text", e.getMessage());
            r.put("isError", true);
        }
        return r;
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
