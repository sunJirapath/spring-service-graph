package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Fact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaEdgeCasesTest {

    @Test
    void listenerAcceptsSingleTopicAndArray(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class Listeners {
                    @KafkaListener(topics = "one") void one() {}
                    @KafkaListener(topics = {"two", "three"}) void many() {}
                }
                """).facts(new KafkaExtractor());

        assertThat(topics(facts, false)).containsExactlyInAnyOrder("one", "two", "three");
    }

    @Test
    void listenerResolvesConstantAndConstantArrayElements(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class Topics { static final String ONE = "one"; static final String TWO = "two"; }
                class L { @KafkaListener(topics = {Topics.ONE, Topics.TWO}) void listen() {} }
                """).facts(new KafkaExtractor());

        assertThat(facts).filteredOn(Fact.TopicUse.class::isInstance).allSatisfy(f ->
                assertThat(((Fact.TopicUse) f).topic().confidence()).isEqualTo(Confidence.RESOLVED));
        assertThat(topics(facts, false)).containsExactlyInAnyOrder("one", "two");
    }

    @Test
    void listenerUsesPlaceholderDefault(@TempDir Path tmp) {
        var fact = onlyTopic(repo(tmp, "package x; class L { @KafkaListener(topics = \"${topic:fallback}\") void listen() {} }")
                .facts(new KafkaExtractor()));

        assertThat(fact.topic().text()).isEqualTo("fallback");
        assertThat(fact.topic().confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    void listenerKeepsMissingPlaceholderUnresolved(@TempDir Path tmp) {
        var fact = onlyTopic(repo(tmp, "package x; class L { @KafkaListener(topics = \"${missing}\") void listen() {} }")
                .facts(new KafkaExtractor()));

        assertThat(fact.topic().confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(fact.topic().text()).isEqualTo("{?}");
    }

    @Test
    void listenerKeepsSpelUnresolved(@TempDir Path tmp) {
        var fact = onlyTopic(repo(tmp, "package x; class L { @KafkaListener(topics = \"#{'${t}'.split(',')}\") void listen() {} }")
                .facts(new KafkaExtractor()));

        assertThat(fact.topic().confidence()).isEqualTo(Confidence.UNRESOLVED);
    }

    @Test
    void listenerTopicPatternIsPreservedAsConsumer(@TempDir Path tmp) {
        var fact = onlyTopic(repo(tmp, "package x; class L { @KafkaListener(topicPattern = \"orders\\\\..*\") void listen() {} }")
                .facts(new KafkaExtractor()));

        assertThat(fact.produce()).isFalse();
        assertThat(fact.via()).isEqualTo("KafkaListener(topicPattern)");
        assertThat(fact.topic().text()).isEqualTo("orders\\..*");
    }

    @Test
    void listenerWithOnlyTopicPartitionsIsNotDropped(@TempDir Path tmp) {
        var fact = onlyTopic(repo(tmp, "package x; class L { @KafkaListener(topicPartitions = @TopicPartition(topic = \"orders\", partitions = \"0\")) void listen() {} }")
                .facts(new KafkaExtractor()));

        assertThat(fact.topic().confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(fact.via()).isEqualTo("KafkaListener");
    }

    @Test
    void classLevelListenerProducesOneFactForKafkaHandlers(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                @KafkaListener(topics = "commands")
                class Handlers {
                    @KafkaHandler void create(String value) {}
                    @KafkaHandler void cancel(Integer value) {}
                }
                """).facts(new KafkaExtractor());

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.TopicUse.class,
                t -> assertThat(t.topic().text()).isEqualTo("commands"));
    }

    @Test
    void listenerWithOnlyIdAndGroupIsUnresolved(@TempDir Path tmp) {
        var fact = onlyTopic(repo(tmp, "package x; class L { @KafkaListener(id = \"worker\", groupId = \"g\") void listen() {} }")
                .facts(new KafkaExtractor()));

        assertThat(fact.topic().confidence()).isEqualTo(Confidence.UNRESOLVED);
    }

    @Test
    void sendToProducesTopic(@TempDir Path tmp) {
        var fact = onlyTopic(repo(tmp, "package x; class L { @KafkaListener(\"in\") @SendTo(\"reply\") String listen() { return \"x\"; } }")
                .facts(new KafkaExtractor()).stream().filter(f -> f instanceof Fact.TopicUse t && t.produce()).toList());

        assertThat(fact.topic().text()).isEqualTo("reply");
        assertThat(fact.via()).isEqualTo("SendTo");
    }

    @Test
    void kafkaTemplateSendOverloadsUseFirstArgumentAsTopic(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class P { KafkaTemplate template;
                    void send(Object v) { template.send("plain", v); template.send("keyed", "key", v); }
                }
                """).facts(new KafkaExtractor());

        assertThat(topics(facts, true)).containsExactlyInAnyOrder("plain", "keyed");
    }

    @Test
    void producerRecordConstructorsExposeTheirTopic(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class P { static final String TOPIC = "constant"; KafkaTemplate template;
                    void send(Object v) { template.send(new ProducerRecord<>("literal", v)); template.send(new ProducerRecord<>(TOPIC, v)); }
                }
                """).facts(new KafkaExtractor());

        assertThat(topics(facts, true)).containsExactlyInAnyOrder("literal", "constant");
    }

    @Test
    void singleArgumentSendIsKeptAsUnresolvedMessage(@TempDir Path tmp) {
        var fact = onlyTopic(repo(tmp, "package x; class P { KafkaTemplate template; void send(Object message) { template.send(message); } }")
                .facts(new KafkaExtractor()));

        assertThat(fact.topic().confidence()).isEqualTo(Confidence.UNRESOLVED);
    }

    @Test
    void sendDefaultUsesConfiguredDefaultTopic(@TempDir Path tmp) {
        var fact = onlyTopic(repo(tmp, "package x; class P { KafkaTemplate template; void send(Object v) { template.sendDefault(v); } }")
                .resource("application.properties", "spring.kafka.template.default-topic=defaults\n")
                .facts(new KafkaExtractor()));

        assertThat(fact.topic().text()).isEqualTo("defaults");
    }

    @Test
    void sendDefaultWithoutConfigurationIsUnresolved(@TempDir Path tmp) {
        var fact = onlyTopic(repo(tmp, "package x; class P { KafkaTemplate template; void send(Object v) { template.sendDefault(v); } }")
                .facts(new KafkaExtractor()));

        assertThat(fact.topic().confidence()).isEqualTo(Confidence.UNRESOLVED);
    }

    @Test
    void supportedTemplateInterfacesAndThisQualifiedFieldAreRecognized(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class P {
                    KafkaOperations operations; ReactiveKafkaProducerTemplate reactive;
                    void send(Object v) { operations.send("ops", v); reactive.send("reactive", v); this.operations.send("this", v); }
                }
                """).facts(new KafkaExtractor());

        assertThat(topics(facts, true)).containsExactlyInAnyOrder("ops", "reactive", "this");
    }

    @Test
    void unrelatedSendMethodsAreNotKafkaTopics(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class P { EmailSender emailSender; EmailSender kafkaTemplate; Channel channel;
                    void send(Object to, Object body, Object msg) { emailSender.send(to, body); kafkaTemplate.send(to, body); channel.send(msg); }
                }
                class EmailSender { void send(Object a, Object b) {} }
                class Channel { void send(Object a) {} }
                """).facts(new KafkaExtractor());

        assertThat(facts).isEmpty();
    }

    @Test
    void streamBridgeUsesConfiguredDestinationWithoutDoubleCountingBinding(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class P { StreamBridge streamBridge; void send(Object v) { streamBridge.send(\"audit-out-0\", v); } }")
                .resource("application.properties", "spring.cloud.stream.bindings.audit-out-0.destination=audit.events\n")
                .facts(new KafkaExtractor());

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.TopicUse.class, t -> {
            assertThat(t.topic().text()).isEqualTo("audit.events");
            assertThat(t.via()).isEqualTo("StreamBridge");
        });
    }

    @Test
    void streamBridgeFallsBackToBindingName(@TempDir Path tmp) {
        var fact = onlyTopic(repo(tmp, "package x; class P { StreamBridge bridge; void send(Object v) { bridge.send(\"dynamic-out-0\", v); } }")
                .facts(new KafkaExtractor()));

        assertThat(fact.topic().text()).isEqualTo("dynamic-out-0");
    }

    @Test
    void cloudStreamInfersDirectionsAndSplitsDestinations(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("application.properties", """
                        spring.cloud.stream.bindings.orders-in-0.destination=orders, orders.retry
                        spring.cloud.stream.bindings.audit-out-0.destination=audit
                        spring.cloud.stream.bindings.input.destination=legacy.in
                        spring.cloud.stream.bindings.output.destination=legacy.out
                        """).facts(new KafkaExtractor());

        assertThat(topics(facts, false)).containsExactlyInAnyOrder("orders", "orders.retry", "legacy.in");
        assertThat(topics(facts, true)).containsExactlyInAnyOrder("audit", "legacy.out");
    }

    @Test
    void cloudStreamUnknownDirectionProducesProblem(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("application.properties", "spring.cloud.stream.bindings.events.destination=events\n")
                .facts(new KafkaExtractor());

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.Problem.class, p -> {
            assertThat(p.kind()).isEqualTo("UNRESOLVED");
            assertThat(p.message()).contains("events").contains("direction");
        });
    }

    @Test
    void cloudStreamDestinationResolvesPlaceholder(@TempDir Path tmp) {
        var fact = onlyTopic(repo(tmp, "package x; class Empty {}")
                .resource("application.properties", "topic=orders\nspring.cloud.stream.bindings.orders-in-0.destination=${topic}\n")
                .facts(new KafkaExtractor()));

        assertThat(fact.topic().text()).isEqualTo("orders");
        assertThat(fact.topic().confidence()).isEqualTo(Confidence.RESOLVED);
    }

    private static TempRepo repo(Path tmp, String source) {
        return TempRepo.at(tmp.resolve("repo")).app().java("x/Kafka.java", source);
    }

    private static List<String> topics(List<Fact> facts, boolean produce) {
        return facts.stream().filter(Fact.TopicUse.class::isInstance).map(Fact.TopicUse.class::cast)
                .filter(t -> t.produce() == produce).map(t -> t.topic().text()).toList();
    }

    private static Fact.TopicUse onlyTopic(List<Fact> facts) {
        assertThat(facts).singleElement().isInstanceOf(Fact.TopicUse.class);
        return (Fact.TopicUse) facts.getFirst();
    }
}
