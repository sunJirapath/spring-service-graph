package io.github.sunjirapath.ssg.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class McpEdgeCasesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void stringRequestIdIsEchoedAsString() {
        JsonNode reply = server(graph()).handle("{\"jsonrpc\":\"2.0\",\"id\":\"req-7\",\"method\":\"ping\"}");

        assertThat(reply.path("id").isTextual()).isTrue();
        assertThat(reply.path("id").asText()).isEqualTo("req-7");
    }

    @Test
    void numericRequestIdIsEchoedAsNumber() {
        JsonNode reply = server(graph()).handle("{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"ping\"}");

        assertThat(reply.path("id").isIntegralNumber()).isTrue();
        assertThat(reply.path("id").asInt()).isEqualTo(42);
    }

    @Test
    void nullIdIsTreatedAsNotification() {
        assertThat(server(graph()).handle("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}")).isNull();
    }

    @Test
    void jsonRpcBatchReturnsErrorInsteadOfCrashingOrBeingIgnored() {
        JsonNode reply = server(graph()).handle("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]");

        // EXPECTATION?: This newline protocol does not support batches, so JSON-RPC invalid-request is safer than silence.
        assertThat(reply).isNotNull();
        assertThat(reply.has("error")).isTrue();
    }

    @Test
    void missingParamsOnToolCallReturnsReadableToolError() {
        JsonNode reply = server(graph()).handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"}");

        assertThat(reply.at("/result/isError").asBoolean()).isTrue();
        assertThat(reply.at("/result/content/0/text").asText()).contains("unknown tool");
    }

    @Test
    void nonObjectArgumentsReturnMissingArgumentToolError() {
        JsonNode reply = server(graph()).handle("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"who_calls","arguments":"orders"}}
                """);

        assertThat(reply.at("/result/isError").asBoolean()).isTrue();
        assertThat(reply.at("/result/content/0/text").asText()).isEqualTo("missing argument: target");
    }

    @Test
    void impactDepthBelowOneIsClampedToOne() throws Exception {
        JsonNode result = toolText(server(chainGraph(3)), "impact_of",
                "{\"target\":\"topic:target\",\"depth\":0}");

        assertThat(result.path("affected")).hasSize(1);
        assertThat(result.at("/affected/0/depth").asInt()).isEqualTo(1);
    }

    @Test
    void impactDepthAboveTenIsClampedToTen() throws Exception {
        JsonNode result = toolText(server(chainGraph(12)), "impact_of",
                "{\"target\":\"topic:target\",\"depth\":99}");

        assertThat(result.path("affected")).hasSize(10);
        assertThat(result.path("affected").get(9).path("depth").asInt()).isEqualTo(10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void impactOfServiceExpandsToCallsIntoItsEndpoints() {
        var orders = service("orders");
        var caller = service("gateway");
        var endpoint = endpoint("orders", "GET", "/orders");
        var graph = new Graph(List.of(orders, caller, endpoint),
                List.of(edge(Edge.Kind.CALLS, caller.id(), endpoint.id(), 1)), List.of());

        var affected = (List<Map<String, Object>>) new GraphQueries(graph).impactOf("orders", 1).get("affected");
        assertThat(affected).extracting(row -> row.get("node")).containsExactly(caller.id());
    }

    @Test
    @SuppressWarnings("unchecked")
    void whoCallsExternalNodeReturnsIncomingHttpCall() {
        var caller = service("orders");
        var external = new Node.External(Node.External.idOf("stripe"), "stripe");
        var graph = new Graph(List.of(caller, external),
                List.of(edge(Edge.Kind.CALLS, caller.id(), external.id(), 1)), List.of());

        var incoming = (List<Map<String, Object>>) new GraphQueries(graph).whoCalls("stripe").get("incoming");
        assertThat(incoming).singleElement().satisfies(row -> {
            assertThat(row.get("from")).isEqualTo(caller.id());
            assertThat(row.get("to")).isEqualTo(external.id());
        });
    }

    @Test
    void resolveStripsSurroundingWhitespace() {
        assertThat(new GraphQueries(new Graph(List.of(service("orders")), List.of(), List.of())).resolve(" \t orders \n"))
                .containsExactly(Node.Service.idOf("orders"));
    }

    @Test
    void resolveTableByServiceAndTableNameDisambiguatesDuplicates() {
        var first = table("orders", "audit");
        var second = table("billing", "audit");
        var queries = new GraphQueries(new Graph(List.of(first, second), List.of(), List.of()));

        assertThat(queries.resolve("billing/audit")).containsExactly(second.id());
    }

    @Test
    void serveSkipsBlanksAndNotificationsAndWritesOneLinePerResponse() throws Exception {
        String input = "\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                + "  \n"
                + "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":\"two\",\"method\":\"ping\"}\n";
        var bytes = new ByteArrayOutputStream();

        server(graph()).serve(new BufferedReader(new StringReader(input)),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));

        List<String> lines = bytes.toString(StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSize(2);
        assertThat(JSON.readTree(lines.get(0)).path("id").asInt()).isEqualTo(1);
        assertThat(JSON.readTree(lines.get(1)).path("id").asText()).isEqualTo("two");
    }

    @Test
    @SuppressWarnings("unchecked")
    void whoCallsAnswersForGraphWithTwoThousandEndpoints() {
        var nodes = new ArrayList<Node>();
        var edges = new ArrayList<Edge>();
        var target = service("target");
        var caller = service("caller");
        nodes.add(target);
        nodes.add(caller);
        for (int i = 0; i < 2_000; i++) {
            var endpoint = endpoint("target", "GET", "/items/" + i);
            nodes.add(endpoint);
            edges.add(edge(Edge.Kind.CALLS, caller.id(), endpoint.id(), i + 1));
        }

        var incoming = (List<Map<String, Object>>) new GraphQueries(new Graph(nodes, edges, List.of()))
                .whoCalls("target").get("incoming");
        assertThat(incoming).hasSize(2_000);
    }

    private static JsonNode toolText(McpServer server, String name, String arguments) throws Exception {
        JsonNode reply = server.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + name + "\",\"arguments\":" + arguments + "}}");
        assertThat(reply.at("/result/isError").asBoolean()).isFalse();
        return JSON.readTree(reply.at("/result/content/0/text").asText());
    }

    private static McpServer server(Graph graph) {
        return new McpServer(new GraphQueries(graph), "test");
    }

    private static Graph graph() {
        return new Graph(List.of(), List.of(), List.of());
    }

    private static Graph chainGraph(int length) {
        var nodes = new ArrayList<Node>();
        var edges = new ArrayList<Edge>();
        var topic = new Node.Topic(Node.Topic.idOf("target"), "target");
        nodes.add(topic);
        String previous = topic.id();
        for (int i = 1; i <= length; i++) {
            var endpoint = endpoint("service" + i, "GET", "/" + i);
            nodes.add(endpoint);
            edges.add(edge(Edge.Kind.REACHES, endpoint.id(), previous, i));
            previous = endpoint.id();
        }
        return new Graph(nodes, edges, List.of());
    }

    private static Node.Service service(String name) {
        return new Node.Service(Node.Service.idOf(name), name, name, ".");
    }

    private static Node.Endpoint endpoint(String service, String method, String path) {
        return new Node.Endpoint(Node.Endpoint.idOf(service, method, path), service, method, path);
    }

    private static Node.Table table(String service, String name) {
        return new Node.Table(Node.Table.idOf(service, name), service, name, "sql");
    }

    private static Edge edge(Edge.Kind kind, String from, String to, int line) {
        return new Edge(kind, from, to, Confidence.EXACT, new TreeMap<>(),
                List.of(new Evidence("repo", "Source.java", line)));
    }
}
