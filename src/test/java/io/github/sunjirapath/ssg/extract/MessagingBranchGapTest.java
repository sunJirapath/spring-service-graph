package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Fact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingBranchGapTest {

    @TempDir Path tmp;

    // Pins KafkaExtractor.java:96-99: only a nonempty ProducerRecord supplies its first argument as the topic.
    @Test
    void kafkaTemplateDistinguishesProducerRecordFromOtherSingleArguments() {
        var facts = repo(tmp, "producer-record", """
                package x;
                class P { KafkaTemplate template;
                    void send() {
                        template.send(new String("message"));
                        template.send(new ProducerRecord<>());
                        template.send(new ProducerRecord<>("orders", v));
                    }
                }
                """).facts(new KafkaExtractor());

        assertThat(produced(facts)).extracting(t -> t.topic().text())
                .containsExactly("{?}", "{?}", "orders");
        assertThat(produced(facts)).extracting(t -> t.topic().raw())
                .containsExactly("new String(\"message\")", "new ProducerRecord<>()", "orders");
        assertThat(produced(facts)).extracting(t -> t.topic().confidence())
                .containsExactly(Confidence.UNRESOLVED, Confidence.UNRESOLVED, Confidence.EXACT);
    }

    // Pins KafkaExtractor.java:115-116, 214-238: a detected function output ignores the explicit binder.
    @Test
    void streamBridgeKeepsConfiguredBrokerForPrecreatedFunctionOutput() {
        var facts = repo(tmp, "function-output", """
                package x;
                import java.util.function.Function;
                class Config {
                    @Bean(name = "normalize") Function<String, String> fn() { return x -> x; }
                }
                class P { StreamBridge bridge;
                    void send(Object value) { bridge.send("normalize-out-0", "rabbit", value); }
                }
                """).resource("application.properties", """
                spring.cloud.stream.default-binder=kafka
                spring.cloud.stream.bindings.normalize-out-0.destination=normalized
                """).facts(new KafkaExtractor());

        assertThat(produced(facts)).singleElement().satisfies(t -> {
            assertThat(t.topic().text()).isEqualTo("normalized");
            assertThat(t.broker()).isEqualTo("kafka");
            assertThat(t.via()).isEqualTo("StreamBridge");
        });
    }

    // Pins KafkaExtractor.java:192-196: disabling autodetection lets the explicit binder override the default.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void functionOutputUsesDefaultBrokerOnlyWhenAutodetectionIsEnabled(boolean autodetect) {
        var facts = repo(tmp, "autodetect-" + autodetect, """
                package x;
                import java.util.function.Supplier;
                class Config { @Bean Supplier<String> generated() { return () -> "x"; } }
                class P { StreamBridge bridge;
                    void send(Object value) { bridge.send("generated-out-0", "rabbit", value); }
                }
                """).resource("application.properties", """
                spring.cloud.stream.default-binder=kafka
                spring.cloud.stream.function.autodetect=%s
                """.formatted(autodetect)).facts(new KafkaExtractor());

        assertThat(produced(facts)).singleElement().satisfies(t -> {
            assertThat(t.topic().text()).isEqualTo("generated-out-0");
            assertThat(t.broker()).isEqualTo(autodetect ? "kafka" : "rabbit");
        });
    }

    // Pins KafkaExtractor.java:190: a composition stays ambiguous even when a bean has that literal name.
    @Test
    void composedFunctionDefinitionLeavesExplicitBinderUnresolved() {
        var facts = repo(tmp, "composed-definition", """
                package x;
                import java.util.function.Function;
                class Config {
                    @Bean(name = "normalize|enrich") Function<String, String> fn() { return x -> x; }
                }
                class P { StreamBridge bridge;
                    void send(Object value) { bridge.send("normalize|enrich-out-0", "rabbit", value); }
                }
                """).resource("application.properties", """
                spring.cloud.stream.default-binder=kafka
                spring.cloud.stream.function.definition=normalize|enrich
                """).facts(new KafkaExtractor());

        assertThat(produced(facts)).singleElement().satisfies(t -> {
            assertThat(t.topic().text()).isEqualTo("normalize|enrich-out-0");
            assertThat(t.broker()).isEqualTo("unknown");
        });
    }

    // Pins KafkaExtractor.java:251: the depth cap hides a non-String binder that otherwise retains the default.
    @Test
    void deepNonStringAliasChainBecomesUnknownPastTheTypeDepthCap() {
        StringBuilder source = new StringBuilder("package x; class P { StreamBridge bridge; void send() {");
        source.append("var b0 = 1;\n");
        for (int i = 1; i <= 24; i++) source.append("var b").append(i).append(" = b").append(i - 1).append(";\n");
        source.append("bridge.send(\"shallow-out-0\", b23, \"payload\");");
        source.append("bridge.send(\"deep-out-0\", b24, \"payload\"); } }");
        var facts = repo(tmp, "deep-alias", source.toString())
                .resource("application.properties", """
                spring.cloud.stream.default-binder=kafka
                """).facts(new KafkaExtractor());

        assertThat(produced(facts)).extracting(t -> t.topic().text())
                .containsExactly("shallow-out-0", "deep-out-0");
        assertThat(produced(facts)).extracting(Fact.TopicUse::broker).containsExactly("kafka", "unknown");
    }

    // Pins KafkaExtractor.java:246-247, 272-294: MIME third arguments use the default; other rows use the String binder.
    @Test
    void mimeAndStringOverloadsAreDistinguishedForStreamBridge() {
        var facts = repo(tmp, "mime-overloads", """
                package x;
                class P {
                    StreamBridge bridge;
                    MediaType media;
                    MimeType mime() { return null; }
                    Object other() { return null; }
                    void send() {
                        bridge.send("one-out-0", "kafka", (MimeType) null);
                        bridge.send("two-out-0", "kafka", new MediaType());
                        bridge.send("three-out-0", "kafka", media);
                        bridge.send("four-out-0", "kafka", MediaType.APPLICATION_JSON);
                        bridge.send("five-out-0", "kafka", MediaType.ALL_VALUE);
                        bridge.send("six-out-0", "kafka", MediaType.parseMediaType("text/plain"));
                        bridge.send("seven-out-0", "kafka", mime());
                        bridge.send("eight-out-0", "kafka", other());
                        bridge.send("nine-out-0", "kafka", null);
                    }
                }
                """).resource("application.properties", """
                spring.cloud.stream.default-binder=rabbit
                """).facts(new KafkaExtractor());

        assertThat(produced(facts)).extracting(t -> t.topic().text()).containsExactlyInAnyOrder(
                "one-out-0", "two-out-0", "three-out-0", "four-out-0", "five-out-0", "six-out-0", "seven-out-0", "eight-out-0", "nine-out-0");
        assertThat(produced(facts)).filteredOn(t -> List.of("one-out-0", "two-out-0", "three-out-0",
                        "four-out-0", "six-out-0", "seven-out-0").contains(t.topic().text()))
                .hasSize(6).allSatisfy(t -> assertThat(t.broker()).isEqualTo("rabbit"));
        assertThat(produced(facts)).filteredOn(t -> List.of("five-out-0", "eight-out-0", "nine-out-0")
                        .contains(t.topic().text()))
                .hasSize(3).allSatisfy(t -> assertThat(t.broker()).isEqualTo("kafka"));
    }

    // Pins KafkaExtractor.java:307: a blank configured destination is unresolved at the binding's config key.
    @Test
    void cloudStreamEmptyDestinationIsUnresolvedAtItsConfigKey() {
        var facts = repo(tmp, "blank-destination", "package x; class Empty {}")
                .resource("application.properties", "spring.cloud.stream.bindings.events-in-0.destination=\n")
                .facts(new KafkaExtractor());

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.TopicUse.class, t -> {
            assertThat(t.produce()).isFalse();
            assertThat(t.topic().text()).isEqualTo("{?}");
            assertThat(t.topic().raw()).isEqualTo("spring.cloud.stream.bindings.events-in-0.destination");
            assertThat(t.topic().confidence()).isEqualTo(Confidence.UNRESOLVED);
        });
    }

    // Pins KafkaExtractor.java:113, 139-142: null retains the configured binder; named types must resolve.
    @Test
    void streamBridgeNullExplicitBinderAndUnresolvedBinderTypeRemainObservable() {
        var facts = repo(tmp, "binder-branches", """
                package x;
                class P { StreamBridge bridge;
                    void send(Object value) {
                        bridge.send("explicit-out-0", null, "payload", value);
                        bridge.send("typed-out-0", value);
                        bridge.send("named-out-0", "working", value);
                    }
                }
                """).resource("application.properties", """
                spring.cloud.stream.default-binder=rabbit
                spring.cloud.stream.binders.working.type=kafka
                spring.cloud.stream.binders.unresolved.type=${missing.type}
                spring.cloud.stream.bindings.explicit-out-0.binder=working
                spring.cloud.stream.bindings.typed-out-0.binder=unresolved
                """).facts(new KafkaExtractor());

        assertThat(produced(facts)).extracting(t -> t.topic().text())
                .containsExactly("explicit-out-0", "typed-out-0", "named-out-0");
        assertThat(produced(facts)).extracting(Fact.TopicUse::broker).containsExactly("kafka", "unknown", "kafka");
    }

    // Pins MessagingExtractor.java:95-110: metadata overloads keep the first routing key, unknown types stay unresolved.
    @Test
    void rabbitMetadataAndUnknownArgumentTypeKeepObservableRoutingKeyResults() {
        var facts = repo(tmp, "rabbit-overloads", """
                package x;
                class P {
                    RabbitTemplate rabbit;
                    MessagePostProcessor post;
                    Object unknown() { return null; }
                    void process(Object value) {}
                    void send() {
                        rabbit.send("post-processed", "body", new MessagePostProcessor());
                        rabbit.send("cast", "body", (MessagePostProcessor) null);
                        rabbit.send("named", "body", post);
                        rabbit.send("field", "body", this.post);
                        rabbit.send("lambda", "body", value -> {});
                        rabbit.send("reference", "body", this::process);
                        rabbit.convertAndSend("exchange", "literal-key", "body");
                        rabbit.convertAndSend("exchange", "five-args", "body", new MessagePostProcessor(), new CorrelationData());
                        rabbit.convertAndSend("exchange", "body", unknown());
                    }
                }
                """).facts(new MessagingExtractor());

        assertThat(produced(facts)).extracting(t -> t.topic().text()).containsExactly(
                "post-processed", "cast", "named", "field", "lambda", "reference", "literal-key", "five-args",
                "{?}");
        assertThat(produced(facts)).last().satisfies(t -> {
            assertThat(t.topic().raw()).isEqualTo("rabbit.convertAndSend(\"exchange\", \"body\", unknown())");
            assertThat(t.topic().confidence()).isEqualTo(Confidence.UNRESOLVED);
        });
    }

    // Pins MessagingExtractor.java:68-70, 86-87: missing listener topics are unresolved and RocketMQ sends drop tags.
    @Test
    void rocketListenerWithoutTopicAndUnrelatedSendersStayExplicit() {
        var facts = repo(tmp, "messaging-negatives", """
                package x;
                @RocketMQMessageListener(consumerGroup = "workers") class Listener {}
                class P { EmailSender email; RocketMQTemplate rocket;
                    void send() { email.send("ignored", "body"); rocket.send("events:tag", "body"); }
                }
                class EmailSender { void send(Object a, Object b) {} }
                """).facts(new MessagingExtractor());

        assertThat(facts).hasSize(2);
        assertThat(consumed(facts)).singleElement().satisfies(t -> {
            assertThat(t.topic().text()).isEqualTo("{?}");
            assertThat(t.topic().raw()).isEqualTo("@RocketMQMessageListener(consumerGroup = \"workers\")");
            assertThat(t.topic().confidence()).isEqualTo(Confidence.UNRESOLVED);
            assertThat(t.via()).isEqualTo("RocketMQMessageListener");
            assertThat(t.broker()).isEqualTo("rocketmq");
        });
        assertThat(produced(facts)).singleElement().satisfies(t -> {
            assertThat(t.topic().text()).isEqualTo("events");
            assertThat(t.broker()).isEqualTo("rocketmq");
        });
    }

    private static TempRepo repo(Path tmp, String name, String source) {
        return TempRepo.at(tmp.resolve(name)).app().java("x/Messaging.java", source);
    }

    private static List<Fact.TopicUse> produced(List<Fact> facts) {
        return facts.stream().filter(Fact.TopicUse.class::isInstance).map(Fact.TopicUse.class::cast)
                .filter(Fact.TopicUse::produce).toList();
    }

    private static List<Fact.TopicUse> consumed(List<Fact> facts) {
        return facts.stream().filter(Fact.TopicUse.class::isInstance).map(Fact.TopicUse.class::cast)
                .filter(t -> !t.produce()).toList();
    }
}
