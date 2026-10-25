package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.diff.GraphDiff;
import io.github.sunjirapath.ssg.extract.KafkaExtractor;
import io.github.sunjirapath.ssg.link.Linker;
import io.github.sunjirapath.ssg.mcp.GraphQueries;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import io.github.sunjirapath.ssg.model.Value;
import io.github.sunjirapath.ssg.output.JsonOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceIdentityTest {
    private static final Evidence EV = new Evidence("repo", "App.java", 1);

    @Test
    void differentBrokerProducersCannotHideAnOrphanedKafkaTopic() {
        Graph base = link(service("producer", topic(true, "kafka"), topic(true, "rabbit"), topic(true, "rocketmq")),
                service("consumer", topic(false, "kafka")));
        Graph head = link(service("producer", topic(true, "rabbit"), topic(true, "rocketmq")),
                service("consumer", topic(false, "kafka")));

        assertThat(base.nodes()).filteredOn(Node.Topic.class::isInstance).hasSize(3);
        assertThat(GraphDiff.compare(base, head).breaking()).containsExactly(
                "Topic `orders` (kafka) has no producer any more but is still consumed by consumer");
        assertThat(GraphDiff.compare(head, head).breaking()).isEmpty();
    }

    @Test
    void extractorsKeepIdenticallyNamedKafkaRabbitAndRocketDestinationsSeparate(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("repo")).app().java("app/Messaging.java", """
                package app;
                class Messaging {
                    KafkaTemplate kafka; RabbitTemplate rabbit; RocketMQTemplate rocket;
                    @KafkaListener(topics = "orders") void kafka() {}
                    @RabbitListener(queues = "orders") void rabbit() {}
                    @RocketMQMessageListener(topic = "orders") void rocket() {}
                    void send() {
                        kafka.send("orders", "payload");
                        rabbit.convertAndSend("orders", "payload");
                        rocket.syncSend("orders:tag", "payload");
                    }
                }
                """);
        Graph graph = TempRepo.scan(repo);
        assertThat(graph.nodes()).filteredOn(Node.Topic.class::isInstance).extracting(Node::id)
                .containsExactlyInAnyOrder("topic:kafka:orders", "topic:rabbit:orders", "topic:rocketmq:orders");
        for (String broker : List.of("kafka", "rabbit", "rocketmq")) {
            assertThat(graph.edges()).filteredOn(e -> e.to().equals("topic:" + broker + ":orders"))
                    .extracting(Edge::kind).containsExactlyInAnyOrder(Edge.Kind.PRODUCES, Edge.Kind.CONSUMES);
        }
    }

    @Test
    void storeIsPartOfOwnershipDemotionAndResourceIdentity() {
        Graph graph = link(new Linker.ServiceFacts("sql", "repo", "sql", List.of(
                        new Fact.TableUse(true, Value.exact("orders"), "sql", new Evidence("repo", "sql/V1.sql", 1), "DDL"))),
                new Linker.ServiceFacts("mongo", "repo", "mongo", List.of(
                        new Fact.TableUse(true, Value.exact("orders"), "mongo", new Evidence("repo", "shared/Doc.java", 1), "document"))),
                service("mixed", new Fact.TableUse(false, Value.exact("orders"), "sql", EV, "repository"),
                        new Fact.TableUse(false, Value.exact("orders"), "mongo", EV, "repository")));

        assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.OWNS).extracting(Edge::to)
                .containsExactlyInAnyOrder("table:sql:sql/orders", "table:mongo:mongo/orders");
        assertThat(graph.edges()).filteredOn(e -> e.from().equals("service:mixed")).extracting(Edge::to)
                .containsExactlyInAnyOrder("table:sql:mixed/orders", "table:mongo:mixed/orders");
        assertThat(graph.findings()).isEmpty();
    }

    @Test
    void sendToUsesItsListenerBackendRatherThanAssumingKafka(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("repo")).app().java("app/Replies.java", """
                package app;
                class Replies {
                    @KafkaListener(topics = "input") @SendTo("orders") void kafka() {}
                    @RabbitListener(queues = "input") @SendTo("orders") void rabbit() {}
                    @SendTo("orders") void unspecified() {}
                }
                @RabbitListener(queues = "input") class ClassListener {
                    @SendTo("orders") void reply() {}
                }
                """);
        assertThat(repo.facts(new KafkaExtractor())).filteredOn(f -> f instanceof Fact.TopicUse t && t.produce())
                .map(f -> ((Fact.TopicUse) f).broker()).containsExactlyInAnyOrder("kafka", "rabbit", "unknown", "rabbit");
    }

    @Test
    void cloudStreamUsesOnlyExplicitBinderInformation(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("repo")).app().java("app/Messaging.java", """
                package app;
                class Messaging {
                    StreamBridge bridge;
                    void send() { bridge.send("unconfigured", "payload"); }
                }
                """).resource("application.properties", """
                spring.cloud.stream.bindings.kafka-in-0.destination=orders
                spring.cloud.stream.bindings.kafka-in-0.binder=kafka
                spring.cloud.stream.bindings.rabbit-in-0.destination=orders
                spring.cloud.stream.bindings.rabbit-in-0.binder=events
                spring.cloud.stream.binders.events.type=rabbit
                spring.cloud.stream.bindings.unspecified-in-0.destination=orders
                """);
        assertThat(repo.facts(new KafkaExtractor())).filteredOn(Fact.TopicUse.class::isInstance)
                .map(f -> ((Fact.TopicUse) f).broker()).containsExactlyInAnyOrder("unknown", "kafka", "rabbit", "unknown");
        repo.resource("application.properties", """
                spring.cloud.stream.defaultBinder=events
                spring.cloud.stream.binders.events.type=rocketmq
                spring.cloud.stream.bindings.output.destination=orders
                """);
        assertThat(repo.facts(new KafkaExtractor())).filteredOn(Fact.TopicUse.class::isInstance)
                .map(f -> ((Fact.TopicUse) f).broker()).containsOnly("rocketmq");
    }

    @Test
    void legacyJsonRetainsIdsAndUnknownProvenanceAndCannotMixWithNewDiff(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("graph.json"), """
                {"nodes":[
                  {"kind":"topic","id":"topic:orders","name":"orders"},
                  {"kind":"table","id":"table:svc/orders","service":"svc","name":"orders","store":"mongo"}
                ],"edges":[],"findings":[]}
                """);
        Graph legacy = JsonOutput.read(tmp);
        assertThat(legacy.nodes()).containsExactly(new Node.Topic("topic:orders", "orders", "unknown"),
                new Node.Table("table:svc/orders", "svc", "orders", "mongo"));
        assertThat(GraphDiff.report(legacy, legacy)).contains("No changes.");
        JsonOutput.write(legacy, tmp.resolve("legacy-copy"));
        assertThat(JsonOutput.read(tmp.resolve("legacy-copy"))).isEqualTo(legacy);

        Graph current = link(service("svc", topic(false, "kafka"),
                new Fact.TableUse(false, Value.exact("orders"), "mongo", EV, "repository")));
        JsonOutput.write(current, tmp.resolve("current"));
        assertThat(JsonOutput.read(tmp.resolve("current"))).isEqualTo(current);
        assertThat(GraphDiff.report(current, current)).contains("No changes.");
        assertThatThrownBy(() -> GraphDiff.compare(legacy, current)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rescan both base and head");
        assertThatThrownBy(() -> GraphDiff.compare(current, legacy)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rescan both base and head");
        assertThat(new GraphQueries(legacy).resolve("topic:orders")).containsExactly("topic:orders");
        assertThat(new GraphQueries(legacy).resolve("orders")).containsExactly("topic:orders", "table:svc/orders");
    }

    @Test
    void mcpNamesAndLegacyAliasesReturnAllBackendsWhileExactIdsStayPrecise() {
        Graph graph = link(service("svc", topic(false, "kafka"), topic(false, "rabbit"), topic(false, "rocketmq"),
                new Fact.TableUse(false, Value.exact("orders"), "mongo", EV, "repository"),
                new Fact.TableUse(false, Value.exact("orders"), "sql", EV, "repository")));
        var queries = new GraphQueries(graph);
        assertThat(queries.resolve("orders")).containsExactlyInAnyOrder("topic:kafka:orders", "topic:rabbit:orders",
                "topic:rocketmq:orders", "table:mongo:svc/orders", "table:sql:svc/orders");
        assertThat(queries.resolve("topic:orders")).containsExactlyInAnyOrder("topic:kafka:orders", "topic:rabbit:orders", "topic:rocketmq:orders");
        assertThat(queries.resolve("table:svc/orders")).containsExactlyInAnyOrder("table:mongo:svc/orders", "table:sql:svc/orders");
        assertThat(queries.resolve("svc/orders")).containsExactlyInAnyOrder("table:mongo:svc/orders", "table:sql:svc/orders");
        assertThat(queries.resolve("topic:kafka:orders")).containsExactly("topic:kafka:orders");
        assertThat(queries.resolve("table:mongo:svc/orders")).containsExactly("table:mongo:svc/orders");
    }

    private static Fact.TopicUse topic(boolean produce, String broker) {
        return new Fact.TopicUse(produce, Value.exact("orders"), EV, "test", broker);
    }

    private static Linker.ServiceFacts service(String name, Fact... facts) {
        return new Linker.ServiceFacts(name, "repo", ".", List.of(facts));
    }

    private static Graph link(Linker.ServiceFacts... facts) {
        return Linker.link(List.of(facts), Map.of(), List.of());
    }
}
