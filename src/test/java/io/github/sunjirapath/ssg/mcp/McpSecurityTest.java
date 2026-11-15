package io.github.sunjirapath.ssg.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sunjirapath.ssg.model.Graph;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class McpSecurityTest {
    private final McpServer server = new McpServer(new GraphQueries(new Graph(List.of(), List.of(), List.of())), "test");

    @Test
    void structuredIdAndWrongProtocolAttacksAreInvalidRequests() {
        // Kills reflecting array/object/bool ids and dispatching requests without JSON-RPC 2.0.
        for (String id : List.of("[]", "{}", "true")) {
            var reply = server.handle("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"ping\"}");
            assertThat(reply.at("/error/code").asInt()).isEqualTo(-32600);
            assertThat(reply.path("id").isNull()).isTrue();
        }
        assertThat(server.handle("{\"id\":1,\"method\":\"ping\"}").at("/error/code").asInt()).isEqualTo(-32600);
    }

    @Test
    void malformedSecretAndDeepJsonAttacksReturnBoundedErrors() {
        // Kills Jackson source-snippet disclosure and unhandled empty/deep JSON input.
        for (String input : List.of("", "{\"CANARY_RPC_SECRET\": ?}", "[".repeat(1200) + "0" + "]".repeat(1200))) {
            var reply = server.handle(input);
            assertThat(reply.has("error")).isTrue();
            assertThat(reply.toString()).doesNotContain("CANARY_RPC_SECRET").hasSizeLessThan(256);
        }
    }

    @Test
    void pathLikeAndRegexLikeToolAttacksCannotReadFilesAndRemainSingleLine() throws Exception {
        // Kills treating target strings as files/regexes, or printing unescaped response controls.
        var json = new ObjectMapper();
        var bytes = new ByteArrayOutputStream();
        String input = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"who_calls","arguments":{"target":"../../CANARY_FILE"}}}
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"who_calls","arguments":{"target":"(a+)+$"}}}
                {"jsonrpc":"2.0","id":3,"method":"unknown\\n::error::bad\\u001b"}
                """;
        server.serve(new BufferedReader(new StringReader(input)), new PrintStream(bytes, true, StandardCharsets.UTF_8));
        var lines = bytes.toString(StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSize(3);
        for (String line : lines) {
            assertThat(line.length()).isLessThan(2000);
            assertThat(line).doesNotContain("\u001b");
            assertThat(json.readTree(line).path("jsonrpc").asText()).isEqualTo("2.0");
        }
        assertThat(json.readTree(lines.get(0)).at("/result/isError").asBoolean()).isTrue();
        assertThat(json.readTree(lines.get(1)).at("/result/isError").asBoolean()).isTrue();
        assertThat(json.readTree(lines.get(2)).at("/error/code").asInt()).isEqualTo(-32601);
    }
}
