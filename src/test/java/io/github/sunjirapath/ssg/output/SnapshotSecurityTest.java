package io.github.sunjirapath.ssg.output;

import io.github.sunjirapath.ssg.mcp.McpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import static org.assertj.core.api.Assertions.*;

class SnapshotSecurityTest {
    @TempDir(factory = io.github.sunjirapath.ssg.WorkspaceTempDirFactory.class) Path tmp;

    @Test
    void oversizedSnapshotAttackIsRejectedByDiffAndMcpReaders() throws Exception {
        // Kills readAllBytes/unbounded databinding before the shared snapshot byte limit.
        Path file = tmp.resolve("large.json");
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(JsonOutput.MAX_SNAPSHOT_BYTES);
            channel.write(ByteBuffer.wrap(new byte[]{' '}));
        }
        assertThatThrownBy(() -> JsonOutput.read(file)).isInstanceOf(IOException.class).hasMessageContaining("33554432");
        assertThatThrownBy(() -> McpServer.fromSnapshot(file, "test")).isInstanceOf(IOException.class).hasMessageContaining("33554432");
    }

    @Test
    void malformedAndDeepSnapshotAttacksDoNotEchoSecretsOrPassAsGraph() throws Exception {
        // Kills raw Jackson exception propagation and accepting snapshots with missing graph lists.
        Path file = tmp.resolve("graph.json");
        for (String payload : new String[]{"{}", "null", "{\"CANARY_SNAPSHOT_SECRET\": ?}",
                "{\"nodes\":[],\"edges\":[],\"findings\":[]} {}",
                "{\"nodes\":[{\"kind\":\"external\",\"id\":\"CANARY_SNAPSHOT_SECRET\",\"name\":\"a\"},"
                        + "{\"kind\":\"external\",\"id\":\"CANARY_SNAPSHOT_SECRET\",\"name\":\"b\"}],\"edges\":[],\"findings\":[]}",
                "{\"nodes\":[],\"edges\":[{\"kind\":\"CALLS\",\"from\":\"missing\",\"to\":\"missing\",\"confidence\":\"EXACT\",\"attrs\":{},\"evidence\":[]}],\"findings\":[]}",
                "[".repeat(1200) + "0" + "]".repeat(1200),
                "{\"nodes\":[{\"kind\":\"java.lang.Runtime\"}],\"edges\":[],\"findings\":[]}"}) {
            Files.writeString(file, payload);
            assertThatThrownBy(() -> JsonOutput.read(file)).isInstanceOf(IOException.class)
                    .hasMessage("invalid graph snapshot");
            assertThatThrownBy(() -> McpServer.fromSnapshot(file, "test")).isInstanceOf(IOException.class)
                    .hasMessage("invalid graph snapshot");
        }
    }
}
