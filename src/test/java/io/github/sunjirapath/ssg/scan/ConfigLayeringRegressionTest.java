package io.github.sunjirapath.ssg.scan;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.extract.GatewayExtractor;
import io.github.sunjirapath.ssg.model.Fact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigLayeringRegressionTest {
    @TempDir Path dir;

    @Test
    void malformedLocalAndEmbeddedYamlNeverLogSourceValues() throws Exception {
        String text = "spring.datasource.password: FAKE_PRIVATE_VALUE: invalid\n";
        Path file = Files.writeString(dir.resolve("bad.yml"), text);
        var bytes = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (var stderr = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(stderr);
            Config.load(Repo.of(dir), List.of(file), "default");
            new Config().addEmbedded(Repo.of(dir), dir.resolve("seed.sql"), 42, false, text, "default");
        } finally {
            System.setErr(previous);
        }
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("bad.yml", "seed.sql:42", "line 1")
                .doesNotContain("FAKE_PRIVATE_VALUE", "spring.datasource.password");
    }

    @Test
    void profileRouteListReplacesItemsAndTheirFieldsButScalarLookupStillFallsBack() {
        var repo = TempRepo.at(dir.resolve("gateway")).app()
                .resource("application.yml", """
                        spring.cloud.gateway.routes:
                          - uri: lb://old
                            predicates: [Path=/old/**]
                            filters: [StripPrefix=1]
                          - uri: lb://stale
                            predicates: [Path=/stale/**]
                        """)
                .resource("application-prod.yml", """
                        spring.cloud.gateway.routes:
                          - uri: lb://new
                            predicates: [Path=/new/**]
                        """);
        var service = repo.services("prod", null).getFirst();
        var facts = new GatewayExtractor().extract(service);
        assertThat(facts).filteredOn(Fact.Call.class::isInstance).map(f -> ((Fact.Call) f).url().text())
                .containsExactly("lb://new/new/**");
        assertThat(service.config().resolve("${spring.cloud.gateway.routes[1].uri}").text()).isEqualTo("lb://stale");
    }

    @Test
    void indexedPropertiesReplaceListsAcrossDocumentsAndPutAll() throws Exception {
        Path file = Files.writeString(dir.resolve("base.properties"), """
                routes[0].uri=old
                routes[0].filter=old-filter
                routes[1].uri=stale
                map.first=kept
                #---
                spring.config.activate.on-profile=prod
                routes[0].uri=new
                map.second=added
                """);
        Config config = Config.load(Repo.of(dir), List.of(file), "prod");
        assertThat(config.withPrefix("routes[")).containsOnlyKeys("routes[0].uri");
        assertThat(config.withPrefix("map.")).containsOnlyKeys("map.first", "map.second");
        Path empty = Files.writeString(dir.resolve("empty.yml"), "routes: []\n");
        config.putAll(Config.load(Repo.of(dir), List.of(empty), "default"));
        assertThat(config.withPrefix("routes[")).isEmpty();
        assertThat(config.valueOf("routes[0].uri").text()).isEqualTo("new");
    }

    @Test
    void embeddedListsKeepReplacementAndSeedEvidence() {
        Config config = new Config();
        Repo repo = Repo.of(dir);
        Path seed = dir.resolve("seed.sql");
        config.addEmbedded(repo, seed, 4, false, "routes: [{uri: old}, {uri: stale}]\n", "default");
        config.addEmbedded(repo, seed, 8, false, "routes: [{uri: new}]\n", "default");
        assertThat(config.withPrefix("routes[")).containsOnlyKeys("routes[0].uri");
        assertThat(config.get("routes[0].uri").evidence().line()).isEqualTo(8);
        config.addEmbedded(repo, seed, 12, false, "routes: []\n", "default");
        assertThat(config.withPrefix("routes[")).isEmpty();
    }

    @Test
    void emptyPropertiesValueClearsAnIndexedList() throws Exception {
        Repo repo = Repo.of(dir);
        Path base = Files.writeString(dir.resolve("base.properties"), "routes[0].uri=old\n");
        Path empty = Files.writeString(dir.resolve("empty.properties"), "routes=\n");
        Config config = Config.load(repo, List.of(base, empty), "default");
        assertThat(config.withPrefix("routes[")).isEmpty();
        config = Config.load(repo, List.of(base), "default");
        config.putAll(Config.load(repo, List.of(empty), "default"));
        assertThat(config.withPrefix("routes[")).isEmpty();
    }

    @Test
    void emptyYamlListReplacesTheRootPropertyAsWellAsBoundItems() throws Exception {
        Repo repo = Repo.of(dir);
        Path base = Files.writeString(dir.resolve("base.properties"), "values=old\n");
        Path empty = Files.writeString(dir.resolve("empty.yml"), "values: []\n");
        Config loaded = Config.load(repo, List.of(base, empty), "default");
        assertThat(loaded.valueOf("values").text()).isEmpty();
        assertThat(loaded.resolve("${values:fallback}").text()).isEmpty();
        Config merged = Config.load(repo, List.of(base), "default");
        merged.putAll(Config.load(repo, List.of(empty), "default"));
        assertThat(merged.valueOf("values").text()).isEmpty();
        merged.addEmbedded(repo, dir.resolve("seed.sql"), 12, false, "values: []\n", "default");
        assertThat(merged.get("values").evidence().line()).isEqualTo(12);
    }

    @Test
    void aliasLookupPreservesPrecedenceAcrossPutAllEmbeddedAndInactiveDocuments() throws Exception {
        Repo repo = Repo.of(dir);
        String canonical = "spring.cloud.stream.default-binder";
        String camel = "spring.cloud.stream.defaultBinder";
        Path base = Files.writeString(dir.resolve("base.properties"), canonical + "=kafka\n");
        Path remote = Files.writeString(dir.resolve("remote.yml"), camel + ": rabbit\n---\n"
                + "spring.config.activate.on-profile: dev\n" + canonical + ": ignored\n");
        Config config = Config.load(repo, List.of(base), "prod");
        config.putAll(Config.load(repo, List.of(remote), "prod"));
        assertThat(config.valueOfAny(canonical, camel).text()).isEqualTo("rabbit");
        config.addEmbedded(repo, dir.resolve("seed.sql"), 42, true, canonical + "=rocketmq\n", "prod");
        assertThat(config.valueOfAny(canonical, camel).text()).isEqualTo("rocketmq");
        assertThat(config.get(canonical).evidence().line()).isEqualTo(42);
        config.putAll(config);
        assertThat(config.valueOfAny(canonical, camel).text()).isEqualTo("rocketmq");
        assertThat(config.valueOf(camel).text()).isEqualTo("rabbit");
    }

    @Test
    void aliasLookupKeepsHigherPrecedenceEmptyAndUnresolvedValues() throws Exception {
        Repo repo = Repo.of(dir);
        Path file = Files.writeString(dir.resolve("aliases.yml"), """
                canonical: base
                camel: base
                ---
                camel: ''
                ---
                canonical: ${missing}
                """);
        Config config = Config.load(repo, List.of(file), "default");
        assertThat(config.valueOfAny("camel", "canonical").resolved()).isFalse();
        assertThat(config.valueOfAny("camel", "absent").text()).isEmpty();
        assertThat(config.valueOfAny("absent").resolved()).isFalse();
    }
}
