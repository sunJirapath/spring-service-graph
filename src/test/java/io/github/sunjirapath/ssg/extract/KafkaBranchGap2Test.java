package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Fact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class KafkaBranchGap2Test {
    @TempDir Path tmp;

    @Test
    void definitionPrecedence() {
        String beans = """
                @Bean Supplier<String> primary() { return null; }
                @Bean Supplier<String> fallback() { return null; }
                """;
        String calls = """
                bridge.send("primary-out-0", "rabbit", payload);
                bridge.send("fallback-out-0", "rabbit", payload);
                """;
        var primary = topics(repo("primary", beans, calls, """
                spring.cloud.stream.function.definition=primary
                spring.cloud.function.definition=fallback
                """));
        var fallback = topics(repo("fallback", beans, calls, """
                spring.cloud.stream.function.definition=
                spring.cloud.function.definition=fallback
                """));

        assertThat(primary).extracting(t -> t.topic().text(), Fact.TopicUse::broker)
                .containsExactly(tuple("primary-out-0", "kafka"), tuple("fallback-out-0", "rabbit"));
        assertThat(fallback).extracting(t -> t.topic().text(), Fact.TopicUse::broker)
                .containsExactly(tuple("primary-out-0", "rabbit"), tuple("fallback-out-0", "kafka"));
    }

    @Test
    void unresolvedAutodetection() {
        String beans = "@Bean Supplier<String> generate() { return null; }";
        String call = "bridge.send(\"generate-out-0\", \"rabbit\", payload);";
        var unresolved = topics(repo("unresolved", beans, call,
                "spring.cloud.stream.function.autodetect=${missing}\n"));
        var resolved = topics(repo("resolved", beans, call,
                "spring.cloud.stream.function.autodetect=${missing:true}\n"));

        assertThat(unresolved).extracting(Fact.TopicUse::broker).containsExactly("unknown");
        assertThat(resolved).extracting(Fact.TopicUse::broker).containsExactly("kafka");
    }

    @Test
    void multipleAutoDetectedFunctions() {
        String beans = """
                @Bean Supplier<String> generate() { return null; }
                @Bean int scalar() { return 1; }
                Supplier<String> notABean() { return null; }
                """;
        String call = "bridge.send(\"generate-out-0\", \"rabbit\", payload);";
        var single = topics(repo("single", beans, call, ""));
        var multiple = topics(repo("multiple", beans + "@Bean Function<String, String> other() { return null; }",
                call, ""));

        assertThat(single).extracting(Fact.TopicUse::broker).containsExactly("kafka");
        assertThat(multiple).extracting(Fact.TopicUse::broker).containsExactly("unknown");
    }

    @Test
    void unresolvedBeanNames() {
        String call = "bridge.send(\"events\", payload);";
        var unresolved = topics(repo("unresolved", "@Bean(\"${missing}\") MessageChannel channel() { return null; }",
                call, ""));
        var resolved = topics(repo("resolved", "@Bean(\"other\") MessageChannel channel() { return null; }",
                call, ""));

        assertThat(unresolved).extracting(Fact.TopicUse::broker).containsExactly("unknown");
        assertThat(resolved).extracting(Fact.TopicUse::broker).containsExactly("kafka");
    }

    @Test
    void emptyChannelBeanNames() {
        String call = "bridge.send(\"events\", payload);";
        var fallback = topics(repo("fallback", "@Bean(name = {}) MessageChannel events() { return null; }", call, ""));
        var named = topics(repo("named", "@Bean(name = {\"other\"}) MessageChannel events() { return null; }", call, ""));

        assertThat(fallback).extracting(Fact.TopicUse::broker).containsExactly("unknown");
        assertThat(named).extracting(Fact.TopicUse::broker).containsExactly("kafka");
    }

    @Test
    void unresolvedAutoDetectedName() {
        String call = "bridge.send(\"generate-out-0\", \"rabbit\", payload);";
        var unresolved = topics(repo("unresolved", "@Bean(\"${missing}\") Supplier<String> generate() { return null; }",
                call, ""));
        var resolved = topics(repo("resolved", "@Bean(\"${missing:generate}\") Supplier<String> generate() { return null; }",
                call, ""));

        assertThat(unresolved).extracting(Fact.TopicUse::broker).containsExactly("unknown");
        assertThat(resolved).extracting(Fact.TopicUse::broker).containsExactly("kafka");
    }

    // FINDING (asserts current behaviour, not endorsed; invert when fixed): an empty @Bean name array falls back to the
    // method name for bean detection but not function output lookup.
    @Test
    void emptyFunctionBeanNames() {
        String call = "bridge.send(\"generate-out-0\", \"rabbit\", payload);";
        var empty = topics(repo("empty", "@Bean(name = {}) Supplier<String> generate() { return null; }", call, ""));
        var implicit = topics(repo("implicit", "@Bean Supplier<String> generate() { return null; }", call, ""));

        assertThat(empty).extracting(Fact.TopicUse::broker).containsExactly("unknown");
        assertThat(implicit).extracting(Fact.TopicUse::broker).containsExactly("kafka");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Function | unknown", "Function<String, ?> | unknown", "Function<String, String[]> | unknown",
            "MessageChannel | unknown", "int | unknown", "Tuple2 | unknown",
            "Function<String, Tuple2> | unknown"
    })
    void incompleteFunctionTypes(String type, String expected) {
        var facts = topics(repo("incomplete", "@Bean " + type + " convert() { return null; }",
                "bridge.send(\"convert-out-0\", \"rabbit\", payload);",
                "spring.cloud.function.definition=convert\n"));
        var valid = topics(repo("valid", "@Bean Function<String, String> convert() { return null; }",
                "bridge.send(\"convert-out-0\", \"rabbit\", payload);", "spring.cloud.function.definition=convert\n"));

        assertThat(facts).extracting(t -> t.topic().text(), Fact.TopicUse::broker)
                .containsExactly(tuple("convert-out-0", expected));
        assertThat(valid).extracting(t -> t.topic().text(), Fact.TopicUse::broker)
                .containsExactly(tuple("convert-out-0", "kafka"));
    }

    // FINDING (asserts current behaviour, not endorsed; invert when fixed): only the Flux<Void> row is the bug (it is
    // treated as a function output though it cannot emit values); the other rows pin intended behaviour.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "BiFunction<String, String, Void> | rabbit",
            "BiFunction<String, String, Mono<Void>> | rabbit",
            "BiFunction<String, String, Flux<Void>> | kafka",
            "BiFunction<String, String, Mono<String>> | kafka",
            "BiFunction<String, String, Mono<?>> | kafka",
            "BiFunction<String, String, Mono> | kafka",
            "BiFunction<String, String, Mono<String, Void>> | kafka",
            "Supplier<Void> | kafka", "Supplier<Mono<Void>> | kafka"
    })
    void functionalResults(String type, String expected) {
        var facts = topics(repo("results", "@Bean " + type + " convert() { return null; }\n"
                + "@Bean Supplier<String> baseline() { return null; }", """
                bridge.send("convert-out-0", "rabbit", payload);
                bridge.send("convert-out-1", "rabbit", payload);
                bridge.send("baseline-out-0", "rabbit", payload);
                """, "spring.cloud.function.definition=convert;baseline\n"));

        assertThat(facts).extracting(t -> t.topic().text(), Fact.TopicUse::broker)
                .containsExactly(tuple("convert-out-0", expected), tuple("convert-out-1", "rabbit"),
                        tuple("baseline-out-0", "kafka"));
    }

    @Test
    void functionAliases() {
        String beans = """
                @Bean({"unrelated", "${missing}", "convert"}) Function<String, String> renamed() { return null; }
                Function<String, String> convert() { return null; }
                @Bean int primitive() { return 1; }
                """;
        String call = "bridge.send(\"convert-out-0\", \"rabbit\", payload);";
        var match = topics(repo("match", beans, call, "spring.cloud.function.definition=convert\n"));
        var missing = topics(repo("missing", beans, call, "spring.cloud.function.definition=absent\n"));

        assertThat(match).extracting(Fact.TopicUse::broker).containsExactly("kafka");
        assertThat(missing).extracting(Fact.TopicUse::broker).containsExactly("unknown");
    }

    @Test
    void unresolvedOutputAlias() {
        String beans = "@Bean Supplier<String> convert() { return null; }";
        String call = "bridge.send(\"events\", \"rabbit\", payload);";
        var unresolved = topics(repo("unresolved", beans, call, """
                spring.cloud.function.definition=convert
                spring.cloud.stream.function.bindings.convert-out-0=${missing}
                """));
        var resolved = topics(repo("resolved", beans, call, """
                spring.cloud.function.definition=convert
                spring.cloud.stream.function.bindings.convert-out-0=${missing:events}
                """));

        assertThat(unresolved).extracting(Fact.TopicUse::broker).containsExactly("unknown");
        assertThat(resolved).extracting(Fact.TopicUse::broker).containsExactly("kafka");
    }

    @Test
    void ambiguousArgumentPrecreation() {
        var facts = topics(repo("precreated", "String binder() { return \"rabbit\"; }", """
                bridge.send("known", binder(), payload);
                bridge.send("dynamic", binder(), payload);
                """, "spring.cloud.stream.output_bindings=known\n"));

        assertThat(facts).extracting(t -> t.topic().text(), Fact.TopicUse::broker)
                .containsExactly(tuple("known", "kafka"), tuple("dynamic", "unknown"));
    }

    // FINDING (asserts current behaviour, not endorsed; invert when fixed): a constructed String selects the explicit
    // binder overload but its binder value remains unresolved.
    @Test
    void stringArgumentShapes() {
        var facts = topics(repo("strings", "", """
                bridge.send("enclosed", ("rabbit"), payload);
                bridge.send("castString", (String) "rabbit", payload);
                bridge.send("castObject", (Object) "rabbit", payload);
                bridge.send("constructedString", new String("rabbit"), payload);
                bridge.send("constructedObject", new Object(), payload);
                bridge.send("number", (1), payload);
                bridge.send("character", 'r', payload);
                bridge.send("boolean", true, payload);
                bridge.send("textBlock", \"""
                    rabbit
                    \""", payload);
                """, ""));

        assertThat(facts).extracting(t -> t.topic().text(), Fact.TopicUse::broker).containsExactly(
                tuple("enclosed", "rabbit"), tuple("castString", "rabbit"), tuple("castObject", "kafka"),
                tuple("constructedString", "unknown"), tuple("constructedObject", "kafka"), tuple("number", "kafka"),
                tuple("character", "kafka"), tuple("boolean", "kafka"), tuple("textBlock", "unknown"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "((MimeType) null) | kafka", "((Object) null) | rabbit", "(int) 0 | rabbit",
            "org.springframework.util.MimeTypeUtils.TEXT_PLAIN | kafka",
            "MimeTypeUtils.SPECIFICITY_COMPARATOR | rabbit", "MediaType.TEXT_PLAIN_VALUE | rabbit",
            "this.media | kafka", "this.other | rabbit",
            "MimeTypeUtils.parseMimeType(\"text/plain\") | kafka",
            "Other.parseMimeType(\"text/plain\") | rabbit",
            "mime() | kafka", "mime(0) | rabbit", "absent() | rabbit",
            "mixed() | kafka", "mixed(null) | rabbit"
    })
    void mimeArgumentShapes(String expression, String expected) {
        var facts = topics(repo("mime", """
                MediaType media;
                Object other;
                MimeType mime() { return null; }
                Object mime(int ignored) { return null; }
                MediaType mixed() { return null; }
                MimeType mixed(String ignored) { return null; }
                Object mixed(Integer ignored) { return null; }
                """, "bridge.send(\"events\", \"rabbit\", " + expression + ");\n"
                        + "bridge.send(\"control\", \"rabbit\", payload);\n"
                        + "bridge.send(\"typedControl\", \"rabbit\", media);", ""));

        assertThat(facts).extracting(t -> t.topic().text(), Fact.TopicUse::broker)
                .containsExactly(tuple("events", expected), tuple("control", "rabbit"), tuple("typedControl", "kafka"));
    }

    @Test
    void whitespaceDestination() {
        var repo = TempRepo.at(tmp.resolve("destinations")).app().resource("application.yml", """
                spring.cloud.stream.default-binder: kafka
                spring.cloud.stream.bindings.blank-in-0.destination: "  "
                spring.cloud.stream.bindings.set-out-0.destination: " events "
                """);

        var facts = topics(repo);

        assertThat(facts).extracting(Fact.TopicUse::produce, t -> t.topic().text(),
                        t -> t.topic().raw(), t -> t.topic().confidence(), Fact.TopicUse::broker)
                .containsExactlyInAnyOrder(
                        tuple(false, "{?}", "spring.cloud.stream.bindings.blank-in-0.destination", Confidence.UNRESOLVED, "kafka"),
                        tuple(true, "events", " events ", Confidence.EXACT, "kafka"));
    }

    private TempRepo repo(String name, String members, String calls, String properties) {
        return TempRepo.at(tmp.resolve(name)).app().java("app/Messaging.java", """
                package app;
                class Messaging {
                    StreamBridge bridge;
                    %s
                    void send(Object payload) { %s }
                }
                """.formatted(members, calls)).resource("application.properties",
                "spring.cloud.stream.default-binder=kafka\n" + properties);
    }

    private static List<Fact.TopicUse> topics(TempRepo repo) {
        var facts = repo.facts(new KafkaExtractor());
        assertThat(facts).allSatisfy(fact -> assertThat(fact).isInstanceOf(Fact.TopicUse.class));
        return facts.stream().map(Fact.TopicUse.class::cast).toList();
    }
}
