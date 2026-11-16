package io.github.sunjirapath.ssg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sunjirapath.ssg.model.*;
import io.github.sunjirapath.ssg.output.JsonOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.*;

class SnapshotRobustnessTest {
    @TempDir(factory = WorkspaceTempDirFactory.class) Path tmp;

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r", "\r\n\n"})
    void bomAndLineEndingsPreserveUnicodeModelAndCanonicalBytes(String newline) throws Exception {
        Graph graph = unicodeGraph();
        JsonOutput.write(graph, tmp.resolve("original"));
        byte[] canonical = Files.readAllBytes(tmp.resolve("original/graph.json"));
        String text = "\ufeff" + new String(canonical, StandardCharsets.UTF_8).replace("\n", newline);
        Graph parsed = JsonOutput.parseSnapshot(text.getBytes(StandardCharsets.UTF_8));
        assertThat(parsed).isEqualTo(graph);
        JsonOutput.write(parsed, tmp.resolve("parsed"));
        assertThat(Files.readAllBytes(tmp.resolve("parsed/graph.json"))).isEqualTo(canonical);
    }

    @Test
    void veryLongUnicodeScalarRoundTripsWithoutTruncation() throws Exception {
        String name = "สินค้า😀مرحبا".repeat(20_000);
        Graph graph = new Graph(List.of(new Node.External("external:long", name)), List.of(), List.of());
        byte[] bytes = new ObjectMapper().writeValueAsBytes(graph); // One long JSON line, below the snapshot byte limit.
        assertThat(JsonOutput.parseSnapshot(bytes)).isEqualTo(graph);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \r\n\t", "{", "{\"nodes\":[", "{\"nodes\":[],\"edges\":[],\"findings\":[]"})
    void emptyOrTruncatedSnapshotHasClearReadFailure(String text) {
        assertThatThrownBy(() -> JsonOutput.parseSnapshot(text.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class).hasMessage("invalid graph snapshot");
    }

    // FINDING: snapshot deserialization rejects additive fields on otherwise valid graph, node, and edge objects.
    @ParameterizedTest
    @ValueSource(strings = {"graph", "node", "edge"})
    void unknownExtraFieldsPreserveKnownSnapshotModel(String location) throws Exception {
        var json = new ObjectMapper();
        ObjectNode document = json.valueToTree(unicodeGraph());
        ObjectNode object = switch (location) {
            case "node" -> (ObjectNode) document.path("nodes").get(0);
            case "edge" -> (ObjectNode) document.path("edges").get(0);
            default -> document;
        };
        object.putObject("futureMetadata").put("producer", "next-version");
        assertThat(JsonOutput.parseSnapshot(json.writeValueAsBytes(document))).isEqualTo(unicodeGraph());
    }

    @Test
    void duplicateAttributeKeysUseLastValueWithoutLosingOtherAttributes() throws Exception {
        var json = new ObjectMapper();
        String text = json.writeValueAsString(unicodeGraph()).replace("\"client\":\"rest-client\"",
                "\"client\":\"obsolete\",\"client\":\"rest-client\"");
        assertThat(text).contains("\"client\":\"obsolete\",\"client\":\"rest-client\"");
        // Jackson's default duplicate-key policy is last-value-wins; no stricter policy is configured.
        assertThat(JsonOutput.parseSnapshot(text.getBytes(StandardCharsets.UTF_8))).isEqualTo(unicodeGraph());
    }

    private static Graph unicodeGraph() {
        var service = new Node.Service("service:สินค้า", "สินค้า", "รีโป", "module with spaces");
        var endpoint = new Node.Endpoint("endpoint:สินค้า:GET /مرحبا/😀", "สินค้า", "GET", "/مرحبا/😀");
        var topic = new Node.Topic("topic:kafka:สินค้า😀", "สินค้า😀", "kafka");
        var table = new Node.Table("table:sql:สินค้า/ตาราง", "สินค้า", "ตาราง", "sql");
        var external = new Node.External("external:مرحبا", "مرحبا");
        var evidence = List.of(new Evidence("รีโป", "src/main/java/app/สินค้า.java", 17));
        var edges = List.of(
                new Edge(Edge.Kind.EXPOSES, service.id(), endpoint.id(), Confidence.EXACT, new TreeMap<>(), evidence),
                new Edge(Edge.Kind.CALLS, service.id(), external.id(), Confidence.RESOLVED,
                        new TreeMap<>(Map.of("client", "rest-client", "path", "/مرحبا/😀", "method", "GET")), evidence),
                new Edge(Edge.Kind.PRODUCES, service.id(), topic.id(), Confidence.EXACT, new TreeMap<>(Map.of("via", "kafka-template")), evidence),
                new Edge(Edge.Kind.USES, service.id(), table.id(), Confidence.EXACT, new TreeMap<>(), evidence));
        return new Graph(List.of(service, endpoint, topic, table, external), edges,
                List.of(new Finding(Finding.Kind.UNRESOLVED, service.name(), "ข้อมูล 😀 مرحبا", evidence)));
    }
}
