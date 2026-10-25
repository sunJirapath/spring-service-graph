package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.mcp.GraphQueries;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StreamBinderRegressionTest {
    @TempDir Path tmp;

    private TempRepo repo(String calls, String properties) {
        return TempRepo.at(tmp.resolve("app")).app().java("app/Messaging.java", """
                package app;
                class Messaging {
                    StreamBridge bridge;
                    static final String EVENTS = "events";
                    void send(Object payload, MimeType contentType, MediaType mediaType, String binder) {
                """ + calls + "\n}}\n").resource("application.properties", properties);
    }

    private static List<Fact.TopicUse> topics(TempRepo repo) {
        return repo.facts(new KafkaExtractor()).stream().filter(Fact.TopicUse.class::isInstance)
                .map(Fact.TopicUse.class::cast).toList();
    }

    @Test
    void bindingOverridesCommonDefaultWhichOverridesGlobalDefault() {
        var repo = repo("", """
                spring.cloud.stream.defaultBinder=kafka
                spring.cloud.stream.default.binder=rabbit
                spring.cloud.stream.bindings.input.destination=orders
                spring.cloud.stream.bindings.output.destination=audit
                spring.cloud.stream.bindings.output.binder=events
                spring.cloud.stream.binders.events.type=rocketmq
                """);
        assertThat(topics(repo)).extracting(Fact.TopicUse::broker).containsExactly("rabbit", "rocketmq");
    }

    @ParameterizedTest
    @CsvSource({"defaultBinder", "default-binder", "default_binder", "defaultbinder"})
    void globalBinderAcceptsRelaxedSpellings(String property) {
        var repo = repo("bridge.send(\"orders\", payload);",
                "spring.cloud.stream." + property + "=${missing:events}\nspring.cloud.stream.binders.events.type=rabbit\n");
        assertThat(topics(repo)).extracting(Fact.TopicUse::broker).containsExactly("rabbit");
    }

    @ParameterizedTest
    @CsvSource({"defaultBinder,default-binder", "default-binder,defaultBinder"})
    void higherPrecedenceAliasWinsAcrossProfileFiles(String base, String profile) {
        var repo = repo("bridge.send(\"orders\", payload);", "spring.cloud.stream." + base + "=kafka\n")
                .resource("application-prod.yml", "spring.cloud.stream." + profile + ": rabbit\n");
        var facts = new KafkaExtractor().extract(repo.services("prod", null).getFirst());
        assertThat(facts).filteredOn(Fact.TopicUse.class::isInstance)
                .map(f -> ((Fact.TopicUse) f).broker()).containsExactly("rabbit");
    }

    @ParameterizedTest
    @CsvSource({"'',kafka", "'${missing}',unknown", "'${missing:}',kafka", "'${missing:rabbit}',rabbit"})
    void emptyBinderUsesGlobalDefaultButUnresolvedBinderDoesNotGuess(String binding, String expected) {
        var repo = repo("bridge.send(\"orders\", payload);", """
                spring.cloud.stream.defaultBinder=kafka
                spring.cloud.stream.default.binder=rocketmq
                spring.cloud.stream.bindings.orders.binder=
                """.stripTrailing() + binding + "\n");
        assertThat(topics(repo)).extracting(Fact.TopicUse::broker).containsExactly(expected);
    }

    @Test
    void absentOrUnsupportedBinderRemainsUnknown() {
        var repo = repo("bridge.send(\"orders\", payload);", "");
        assertThat(topics(repo)).extracting(Fact.TopicUse::broker).containsExactly("unknown");
        repo.resource("application.properties", "spring.cloud.stream.defaultBinder=custom\n");
        assertThat(topics(repo)).extracting(Fact.TopicUse::broker).containsExactly("unknown");
    }

    @Test
    void explicitOverloadsSelectBinderForDynamicDestinationsEvenWithBindingProperties() {
        var repo = repo("""
                bridge.send("three", "rabbit", payload);
                bridge.send("four", EVENTS, payload, contentType);
                bridge.send("variable", binder, payload);
                """, """
                spring.cloud.stream.defaultBinder=kafka
                spring.cloud.stream.default.binder=kafka
                spring.cloud.stream.bindings.three.destination=orders
                spring.cloud.stream.bindings.three.binder=kafka
                spring.cloud.stream.binders.events.type=rocketmq
                """);
        assertThat(topics(repo)).extracting(Fact.TopicUse::broker).containsExactly("rabbit", "rocketmq", "unknown");
        assertThat(topics(repo).getFirst().topic().text()).isEqualTo("orders");
    }

    @Test
    void contentTypeOverloadsAndNullBinderKeepConfiguredSelection() {
        var repo = repo("""
                bridge.send("plain", payload);
                bridge.send("typed", payload, contentType);
                bridge.send("constant", payload, MimeTypeUtils.APPLICATION_JSON);
                bridge.send("constructed", payload, new MimeType("application", "json"));
                bridge.send("parsed", payload, MimeType.valueOf("application/json"));
                bridge.send("nullable", null, payload);
                bridge.send("empty", "", payload, contentType);
                """, "spring.cloud.stream.defaultBinder=kafka\n");
        assertThat(topics(repo)).hasSize(7).extracting(Fact.TopicUse::broker).containsOnly("kafka");
    }

    @ParameterizedTest
    @CsvSource({"output-bindings", "outputBindings"})
    void precreatedOutputIgnoresExplicitBinderButUnlistedDestinationDoesNot(String key) {
        var repo = repo("""
                bridge.send("orders", "rabbit", payload);
                bridge.send("dynamic", "rabbit", payload);
                """, "spring.cloud.stream." + key + "=audit;${output:orders}\n" + """
                spring.cloud.stream.defaultBinder=kafka
                spring.cloud.stream.bindings.orders.destination=events
                """);
        assertThat(topics(repo)).extracting(Fact.TopicUse::broker).containsExactly("kafka", "rabbit");
        assertThat(topics(repo)).extracting(t -> t.topic().text()).containsExactly("events", "dynamic");
    }

    @Test
    void correctedBackendSelectionReachesGraphIdsAndMcpAliases() {
        var repo = repo("bridge.send(\"orders\", \"rabbit\", payload);", """
                spring.cloud.stream.defaultBinder=kafka
                spring.cloud.stream.default.binder=rabbit
                spring.cloud.stream.bindings.input.destination=orders
                """);
        var graph = TempRepo.scan(repo);
        assertThat(graph.nodes()).filteredOn(Node.Topic.class::isInstance).extracting(Node::id)
                .containsExactly("topic:rabbit:orders");
        assertThat(new GraphQueries(graph).resolve("topic:orders")).containsExactly("topic:rabbit:orders");
    }

    @Test
    void unresolvedOutputBindingsDoNotAssumeADynamicChannel() {
        var repo = repo("""
                bridge.send("orders", "rabbit", payload);
                bridge.send("known", "rabbit", payload);
                bridge.send("nullable", null, payload);
                """, """
                spring.cloud.stream.default-binder=kafka
                spring.cloud.stream.output-bindings=known;${outputs}
                """);
        assertThat(topics(repo)).extracting(Fact.TopicUse::broker).containsExactly("unknown", "kafka", "kafka");
    }

    @Test
    void mimeSubtypeIsContentTypeButMimeStringsAndListsArePayloads() {
        var repo = repo("""
                bridge.send("media", "payload", MediaType.APPLICATION_JSON);
                bridge.send("typedMedia", "payload", mediaType);
                bridge.send("string", "rabbit", MimeTypeUtils.APPLICATION_JSON_VALUE);
                bridge.send("list", "rabbit", MimeTypeUtils.parseMimeTypes("application/json"));
                """, "spring.cloud.stream.default-binder=kafka\n");
        assertThat(topics(repo)).extracting(Fact.TopicUse::broker).containsExactly("kafka", "kafka", "rabbit", "rabbit");
    }

    @ParameterizedTest
    @CsvSource({"uppercase-out-0,''", "orders,'spring.cloud.stream.function.bindings.uppercase-out-0=orders'"})
    void configuredFunctionOutputRetainsItsBinderIncludingRenamedBindings(String output, String alias) {
        var repo = repo("bridge.send(\"" + output + "\", \"rabbit\", payload);", """
                spring.cloud.stream.default-binder=kafka
                spring.cloud.stream.function.definition=
                spring.cloud.function.definition=uppercase
                """ + alias + "\n").java("app/Functions.java", """
                package app;
                @Configuration class Functions {
                    @Bean("uppercase") Function<String, String> convert() { return value -> value.toUpperCase(); }
                }
                """);
        assertThat(topics(repo)).extracting(Fact.TopicUse::broker).containsExactly("kafka");
    }

    @Test
    void consumerHasNoPrecreatedOutputAndUnknownFunctionDefinitionDoesNotGuess() {
        var repo = repo("bridge.send(\"consume-out-0\", \"rabbit\", payload);", """
                spring.cloud.stream.default-binder=kafka
                spring.cloud.function.definition=consume
                """).java("app/Functions.java", """
                package app;
                @Configuration class Functions {
                    @Bean Consumer<String> consume() { return value -> {}; }
                }
                """);
        assertThat(topics(repo)).extracting(Fact.TopicUse::broker).containsExactly("rabbit");
        repo.resource("application.properties", """
                spring.cloud.stream.default-binder=kafka
                spring.cloud.function.definition=${function}
                """);
        assertThat(topics(repo)).extracting(Fact.TopicUse::broker).containsExactly("unknown");
    }

    @ParameterizedTest
    @CsvSource({"'',kafka", "spring.cloud.stream.function.autodetect=false,rabbit"})
    void soleFunctionIsPrecreatedUnlessAutodetectionIsDisabled(String setting, String expected) {
        var repo = repo("bridge.send(\"uppercase-out-0\", \"rabbit\", payload);",
                "spring.cloud.stream.default-binder=kafka\n" + setting + "\n")
                .java("app/Functions.java", """
                        package app;
                        @Configuration class Functions {
                            @Bean Function<String, String> uppercase() { return value -> value.toUpperCase(); }
                        }
                        """);
        assertThat(topics(repo)).extracting(Fact.TopicUse::broker).containsExactly(expected);
    }

    @ParameterizedTest
    @CsvSource({"split-out-1,''", "orders,'spring.cloud.stream.function.bindings.split-out-1=orders'"})
    void tupleFunctionPrecreatesEveryOutputIncludingAliases(String output, String alias) {
        var repo = repo("bridge.send(\"" + output + "\", \"rabbit\", payload);\n"
                + "bridge.send(\"split-out-2\", \"rabbit\", payload);", """
                spring.cloud.stream.default-binder=kafka
                spring.cloud.function.definition=split
                """ + alias + "\n").java("app/Functions.java", """
                package app;
                @Configuration class Functions {
                    @Bean Function<Flux<String>, Tuple2<Flux<String>, Flux<String>>> split() { return null; }
                }
                """);
        assertThat(topics(repo)).extracting(Fact.TopicUse::broker).containsExactly("kafka", "rabbit");
    }

    @ParameterizedTest
    @CsvSource({"Void,rabbit", "Mono<Void>,rabbit", "Mono<java.lang.Void>,rabbit", "List<Void>,kafka"})
    void onlyVoidAndMonoVoidFunctionResultsSuppressOutput(String result, String expected) {
        var repo = repo("bridge.send(\"convert-out-0\", \"rabbit\", payload);", """
                spring.cloud.stream.default-binder=kafka
                spring.cloud.function.definition=convert
                """).java("app/Functions.java", """
                package app;
                @Configuration class Functions {
                    @Bean Function<String, %s> convert() { return null; }
                }
                """.formatted(result));
        assertThat(topics(repo)).extracting(Fact.TopicUse::broker).containsExactly(expected);
    }
}
