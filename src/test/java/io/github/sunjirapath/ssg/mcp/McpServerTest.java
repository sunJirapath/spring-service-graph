package io.github.sunjirapath.ssg.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sunjirapath.ssg.TestFleet;
import org.junit.jupiter.api.Test;

import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final McpServer server = new McpServer(new GraphQueries(TestFleet.graph()), "test");

    @Test
    void initializesWithSupportedVersionOrFallsBack() throws Exception {
        var supported = handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}");
        var fallback = handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}");

        assertThat(supported.at("/result/protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(fallback.at("/result/protocolVersion").asText()).isEqualTo(McpServer.PROTOCOL_VERSIONS.getFirst());
        assertThat(supported.at("/result/capabilities/tools").isObject()).isTrue();
        assertThat(supported.at("/result/serverInfo/name").asText()).isEqualTo("spring-service-graph");
    }

    @Test
    void ignoresNotifications() {
        assertThat(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).isNull();
    }

    @Test
    void listsExactlyTheThreeObjectInputTools() throws Exception {
        var tools = handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").at("/result/tools");
        var entries = StreamSupport.stream(tools.spliterator(), false).toList();

        assertThat(entries).extracting(t -> t.path("name").asText()).containsExactlyInAnyOrder("who_calls", "impact_of", "service_card");
        assertThat(entries).allSatisfy(t -> assertThat(t.at("/inputSchema/type").asText()).isEqualTo("object"));
    }

    @Test
    void callsWhoCallsAndReturnsJsonText() throws Exception {
        var result = handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"who_calls\",\"arguments\":{\"target\":\"account.created\"}}}").path("result");

        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.at("/content/0/type").asText()).isEqualTo("text");
        assertThat(JSON.readTree(result.at("/content/0/text").asText()).has("matches")).isTrue();
    }

    @Test
    void returnsToolErrorsForUnknownToolAndMissingTarget() throws Exception {
        var unknown = handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\",\"arguments\":{}}}");
        var missing = handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"who_calls\",\"arguments\":{}}}");

        assertThat(unknown.at("/result/isError").asBoolean()).isTrue();
        assertThat(missing.at("/result/isError").asBoolean()).isTrue();
    }

    @Test
    void returnsProtocolErrorsAndEmptyPingResult() throws Exception {
        assertThat(handle("{").at("/error/code").asInt()).isEqualTo(-32700);
        assertThat(handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"nope\"}").at("/error/code").asInt()).isEqualTo(-32601);
        assertThat(handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").path("result").isEmpty()).isTrue();
    }

    private com.fasterxml.jackson.databind.JsonNode handle(String request) throws Exception {
        return JSON.readTree(server.handle(request).toString());
    }
}
