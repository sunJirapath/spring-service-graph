package io.github.sunjirapath.ssg.scan;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigEdgeCasesTest {

    @TempDir
    Path tmp;

    @Test
    void nestedAndDottedYamlKeysShareOneFlattenedNamespace() {
        var config = yaml("mixed", """
                spring:
                  application:
                    name: nested
                spring.application.name: dotted
                management:
                  endpoint.health.show-details: always
                """);

        assertThat(config.get("spring.application.name").value()).isEqualTo("dotted");
        assertThat(config.get("management.endpoint.health.show-details").value()).isEqualTo("always");
    }

    @Test
    void yamlListsFlattenScalarsAndMapsByIndex() {
        var config = yaml("lists", """
                colors: [red, blue]
                clients:
                  - name: billing
                    urls: [one, two]
                  - name: orders
                """);

        assertThat(config.withPrefix("colors")).containsOnlyKeys("colors[0]", "colors[1]");
        assertThat(config.get("colors[1]").value()).isEqualTo("blue");
        assertThat(config.get("clients[0].name").value()).isEqualTo("billing");
        assertThat(config.get("clients[0].urls[1]").value()).isEqualTo("two");
        assertThat(config.get("clients[1].name").value()).isEqualTo("orders");
    }

    @Test
    void yamlAnchorsAliasesAndMergeKeysAreExpanded() {
        var config = yaml("anchors", """
                defaults: &defaults
                  timeout: 30
                  enabled: true
                primary: *defaults
                secondary:
                  <<: *defaults
                  timeout: 60
                """);

        assertThat(config.get("primary.timeout").value()).isEqualTo("30");
        assertThat(config.get("primary.enabled").value()).isEqualTo("true");
        assertThat(config.get("secondary.enabled").value()).isEqualTo("true");
        assertThat(config.get("secondary.timeout").value()).isEqualTo("60");
    }

    @Test
    void multiDocumentYamlAppliesOnlyMatchingModernProfileDocument() {
        var repo = repo("modern-profile").resource("application.yml", """
                mode: base
                ---
                spring.config.activate.on-profile: prod
                mode: production
                ---
                spring.config.activate.on-profile: dev
                mode: development
                """);

        assertThat(loadResource(repo, "application.yml", "prod").get("mode").value()).isEqualTo("production");
        assertThat(loadResource(repo, "application.yml", "qa").get("mode").value()).isEqualTo("base");
    }

    @Test
    void legacySpringProfilesActivatesMatchingDocument() {
        var config = yaml("legacy-profile", """
                mode: base
                ---
                spring.profiles: prod
                mode: legacy-prod
                """, "prod");

        assertThat(config.get("mode").value()).isEqualTo("legacy-prod");
    }

    @Test
    void commaSeparatedQuotedProfilesMatchEitherPlainProfile() {
        var repo = repo("profile-list").resource("application.yml", """
                spring.config.activate.on-profile: "dev, prod"
                selected: yes
                """);

        assertThat(loadResource(repo, "application.yml", "dev").get("selected").value()).isEqualTo("yes");
        assertThat(loadResource(repo, "application.yml", "prod").get("selected").value()).isEqualTo("yes");
        assertThat(loadResource(repo, "application.yml", "test").get("selected")).isNull();
    }

    @Test
    void knownLimitProfileExpressionsAreNotEvaluated() {
        var config = yaml("profile-expression", """
                value: base
                ---
                spring.config.activate.on-profile: "!dev"
                value: not-dev
                """, "prod");

        assertThat(config.get("value").value()).isEqualTo("base");
    }

    @Test
    void emptyDocumentsAreIgnoredWithoutDiscardingOtherDocuments() {
        var config = yaml("empty-documents", """
                ---
                ---
                useful: retained
                ---
                """);

        assertThat(config.get("useful").value()).isEqualTo("retained");
    }

    @Test
    void scalarYamlDocumentDoesNotCreateAnEmptyKey() {
        var config = yaml("scalar-document", """
                just-a-scalar
                ---
                actual: value
                """);

        assertThat(config.withPrefix("")).containsOnlyKeys("actual");
    }

    @Test
    void yamlPrimitiveValuesAreKeptAsStrings() {
        var config = yaml("primitives", """
                enabled: true
                retries: 12
                ratio: 1.5
                nothing: null
                """);

        assertThat(config.get("enabled").value()).isEqualTo("true");
        assertThat(config.get("retries").value()).isEqualTo("12");
        assertThat(config.get("ratio").value()).isEqualTo("1.5");
        assertThat(config.get("nothing").value()).isEqualTo("null");
    }

    @Test
    void quotedYamlKeysMayContainSpacesAndDots() {
        var config = yaml("quoted-keys", """
                "key with spaces": value
                'already.dotted': dotted-value
                parent:
                  "child key": nested-value
                """);

        assertThat(config.get("key with spaces").value()).isEqualTo("value");
        assertThat(config.get("already.dotted").value()).isEqualTo("dotted-value");
        assertThat(config.get("parent.child key").value()).isEqualTo("nested-value");
    }

    @Test
    void veryLongYamlValueIsNotTruncated() {
        String value = "x".repeat(50_000);
        var config = yaml("long-value", "payload: " + value + "\n");

        assertThat(config.get("payload").value()).isEqualTo(value);
    }

    @Test
    void unicodeYamlValuesArePreserved() {
        var config = yaml("unicode", """
                greeting: สวัสดี世界🚀
                """);

        assertThat(config.get("greeting").value()).isEqualTo("สวัสดี世界🚀");
    }

    @Test
    void malformedYamlDoesNotThrowOrPreventLaterFilesLoading() {
        var repo = repo("malformed")
                .resource("tab.yml", "bad:\n\tchild: value\n")
                .resource("quote.yml", "bad: \"unterminated\n")
                .resource("good.yml", "good: retained\n");
        var base = repo.root().resolve("src/main/resources");

        var config = Config.load(Repo.of(repo.root()),
                List.of(base.resolve("tab.yml"), base.resolve("quote.yml"), base.resolve("good.yml")), "default");

        assertThat(config.get("good").value()).isEqualTo("retained");
        assertThat(config.get("bad")).isNull();
    }

    @Test
    void missingConfigFilesAreSkipped() {
        var repo = repo("missing").resource("present.yml", "present: yes\n");
        var resources = repo.root().resolve("src/main/resources");

        var config = Config.load(Repo.of(repo.root()),
                List.of(resources.resolve("absent.yml"), resources.resolve("present.yml")), "default");

        assertThat(config.get("present").value()).isEqualTo("yes");
    }

    @Test
    void propertiesAcceptEqualsColonAndWhitespaceSeparators() {
        var config = properties("separators", """
                equals=value-one
                colon:value-two
                whitespace value-three
                """);

        assertThat(config.get("equals").value()).isEqualTo("value-one");
        assertThat(config.get("colon").value()).isEqualTo("value-two");
        assertThat(config.get("whitespace").value()).isEqualTo("value-three");
    }

    @Test
    void propertiesIgnoreHashAndBangComments() {
        var config = properties("comments", """
                # ignored=one
                ! ignored=two
                kept=value
                """);

        assertThat(config.withPrefix("")).containsOnlyKeys("kept");
    }

    @Test
    void propertiesDecodeEscapedSeparatorsAndUnicode() {
        var config = properties("escapes", """
                escaped\\=key=value-one
                escaped\\:colon=value-two
                letter=\\u0041
                """);

        assertThat(config.get("escaped=key").value()).isEqualTo("value-one");
        assertThat(config.get("escaped:colon").value()).isEqualTo("value-two");
        assertThat(config.get("letter").value()).isEqualTo("A");
    }

    @Test
    void oddTrailingBackslashContinuesButEvenTrailingBackslashesDoNot() {
        var repo = repo("continuations").resource("application.properties",
                "odd=left\\\n  right\neven=two\\\\\nnext=value\n");
        var config = loadResource(repo, "application.properties", "default");

        assertThat(config.get("odd").value()).isEqualTo("leftright");
        assertThat(config.get("even").value()).isEqualTo("two\\");
        assertThat(config.get("next").value()).isEqualTo("value");
    }

    @Test
    void duplicatePropertyKeyUsesLastDefinitionAndEvidence() {
        var config = properties("duplicates", """
                duplicate=first
                other=value
                duplicate=last
                """);

        assertThat(config.get("duplicate").value()).isEqualTo("last");
        assertThat(config.get("duplicate").evidence().line()).isEqualTo(3);
    }

    @Test
    void propertiesKeepEmptyValuesAndKeysWithoutSeparators() {
        var config = properties("empty-values", """
                explicit=
                implicit
                """);

        assertThat(config.get("explicit").value()).isEmpty();
        assertThat(config.get("implicit").value()).isEmpty();
    }

    @Test
    void crlfPropertiesRetainOneBasedPhysicalLineNumbers() {
        var repo = repo("crlf").resource("application.properties", "first=1\r\n# comment\r\nthird=3\r\n");
        var config = loadResource(repo, "application.properties", "default");

        assertThat(config.get("first").evidence().line()).isEqualTo(1);
        assertThat(config.get("third").evidence().line()).isEqualTo(3);
    }

    @Test
    void utf8BomDoesNotBecomePartOfFirstPropertyKey() {
        var repo = repo("bom");
        byte[] content = "\uFEFFfirst=value\nsecond=two\n".getBytes(StandardCharsets.UTF_8);
        repo.bytes("src/main/resources/application.properties", content);

        var config = loadResource(repo, "application.properties", "default");

        assertThat(config.get("first").value()).isEqualTo("value");
        assertThat(config.get("\uFEFFfirst")).isNull();
    }

    @Test
    void laterFilesOverrideEarlierFiles() {
        var repo = repo("file-precedence")
                .resource("first.properties", "winner=first\n")
                .resource("second.properties", "winner=second\n");
        var resources = repo.root().resolve("src/main/resources");

        var config = Config.load(Repo.of(repo.root()),
                List.of(resources.resolve("first.properties"), resources.resolve("second.properties")), "default");

        assertThat(config.get("winner").value()).isEqualTo("second");
        assertThat(config.get("winner").evidence().file()).endsWith("second.properties");
    }

    @Test
    void putAllOverridesExistingEntries() {
        var local = properties("put-all-local", "shared=local\nlocal-only=yes\n");
        var remote = properties("put-all-remote", "shared=remote\nremote-only=yes\n");

        local.putAll(remote);

        assertThat(local.get("shared").value()).isEqualTo("remote");
        assertThat(local.get("local-only").value()).isEqualTo("yes");
        assertThat(local.get("remote-only").value()).isEqualTo("yes");
    }

    @Test
    void plainTextResolvesExactly() {
        var value = new Config().resolve("plain text");

        assertThat(value.text()).isEqualTo("plain text");
        assertThat(value.confidence()).isEqualTo(Confidence.EXACT);
        assertThat(value.via()).isEmpty();
    }

    @Test
    void nestedPlaceholderFallsBackToAnotherKey() {
        var config = properties("nested-placeholder", "b=chosen\n");

        var value = config.resolve("${a:${b}}");

        assertThat(value.text()).isEqualTo("chosen");
        assertThat(value.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(value.via()).singleElement().satisfies(e -> assertThat(e.line()).isEqualTo(1));
    }

    @Test
    void placeholderDefaultMayContainColons() {
        var value = new Config().resolve("${url:http://host:8080/x}");

        assertThat(value.text()).isEqualTo("http://host:8080/x");
        assertThat(value.confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    void emptyPlaceholderDefaultResolvesToEmptyString() {
        var value = new Config().resolve("${missing:}");

        assertThat(value.text()).isEmpty();
        assertThat(value.confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    void missingPlaceholderWithoutDefaultIsUnresolved() {
        var value = new Config().resolve("${missing}");

        assertThat(value.text()).isEqualTo(Value.UNKNOWN);
        assertThat(value.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(value.raw()).isEqualTo("${missing}");
    }

    @Test
    void selfReferenceAndTwoKeyCycleTerminateAsUnresolved() {
        var config = properties("cycles", """
                self=${self}
                a=${b}
                b=${a}
                """);

        assertThat(config.valueOf("self").confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(config.valueOf("a").confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(config.valueOf("b").confidence()).isEqualTo(Confidence.UNRESOLVED);
    }

    @Test
    void unbalancedPlaceholderIsUnresolved() {
        var value = new Config().resolve("before-${missing");

        assertThat(value.text()).isEqualTo(Value.UNKNOWN);
        assertThat(value.confidence()).isEqualTo(Confidence.UNRESOLVED);
    }

    @Test
    void textAroundPlaceholderIsPreserved() {
        var config = properties("surrounding", "name=orders\n");

        assertThat(config.resolve("before-${name}-after").text()).isEqualTo("before-orders-after");
    }

    @Test
    void multiplePlaceholdersResolveIndependently() {
        var config = properties("multiple", "first=one\nsecond=two\n");

        var value = config.resolve("${first}/${second}");

        assertThat(value.text()).isEqualTo("one/two");
        assertThat(value.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(value.via()).extracting(e -> e.line()).containsExactly(1, 2);
    }

    @Test
    void dollarWithoutOpeningBraceIsLiteral() {
        var value = new Config().resolve("cost-$5");

        assertThat(value.text()).isEqualTo("cost-$5");
        assertThat(value.confidence()).isEqualTo(Confidence.EXACT);
    }

    @Test
    void spelExpressionIsUnresolved() {
        var value = new Config().resolve("prefix-#{systemProperties['user.name']}-suffix");

        assertThat(value.text()).isEqualTo(Value.UNKNOWN);
        assertThat(value.confidence()).isEqualTo(Confidence.UNRESOLVED);
    }

    @Test
    void valueOfIncludesEvidenceForTheDefiningKey() {
        var config = properties("value-evidence", "# header\nendpoint=http://orders\n");

        var value = config.valueOf("endpoint");

        assertThat(value.text()).isEqualTo("http://orders");
        assertThat(value.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(value.via()).singleElement().satisfies(e -> {
            assertThat(e.file()).endsWith("application.properties");
            assertThat(e.line()).isEqualTo(2);
        });
    }

    @Test
    void valueOfMissingKeyIsUnresolved() {
        var value = new Config().valueOf("absent");

        assertThat(value.text()).isEqualTo(Value.UNKNOWN);
        assertThat(value.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(value.raw()).isEqualTo("${absent}");
    }

    @Test
    void everyDocumentedSecretKeyVariantIsRedactedCaseInsensitively() {
        var config = properties("secret-variants", """
                password=raw-password
                db.passwd=raw-passwd
                SECRET=raw-secret
                auth.Token=raw-token
                service.credential=raw-credential
                client.api-key=raw-api-dash
                client.apiKey=raw-api-camel
                client.api_key=raw-api-underscore
                tls.private-key=raw-private
                oauth.client-secret=raw-client
                spring.datasource.PASSWORD=raw-upper
                """);

        assertThat(config.withPrefix("")).hasSize(11).allSatisfy((key, entry) -> assertThat(entry.value()).as(key).isNull());
    }

    @Test
    void nonSecretKeyMayContainSecretWordInItsValue() {
        var config = properties("non-secret-value", "message=refresh token expired\n");

        assertThat(config.get("message").value()).isEqualTo("refresh token expired");
    }

    @Test
    void secretPlaceholderNeverResolvesOrLeaksRawValue() {
        var config = properties("secret-placeholder", "password=hunter2\n");

        var value = config.resolve("https://user:${password}@host");

        assertThat(value.text()).isEqualTo("https://user:" + Value.UNKNOWN + "@host");
        assertThat(value.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(value.text()).doesNotContain("hunter2");
        assertThat(value.raw()).doesNotContain("hunter2");
    }

    @Test
    void withPrefixKeepsSecretKeysWithNullValues() {
        var config = properties("secret-prefix", """
                clients.orders.url=http://orders
                clients.orders.token=raw-token
                unrelated=value
                """);

        assertThat(config.withPrefix("clients.orders.")).containsOnlyKeys(
                "clients.orders.token", "clients.orders.url");
        assertThat(config.withPrefix("clients.orders.").get("clients.orders.token").value()).isNull();
    }

    private TempRepo repo(String name) {
        return TempRepo.at(tmp.resolve(name));
    }

    private Config yaml(String name, String text) {
        return yaml(name, text, "default");
    }

    private Config yaml(String name, String text, String profile) {
        var repo = repo(name).resource("application.yml", text);
        return loadResource(repo, "application.yml", profile);
    }

    private Config properties(String name, String text) {
        var repo = repo(name).resource("application.properties", text);
        return loadResource(repo, "application.properties", "default");
    }

    private static Config loadResource(TempRepo repo, String file, String profile) {
        Path path = repo.root().resolve("src/main/resources").resolve(file);
        return Config.load(Repo.of(repo.root()), List.of(path), profile);
    }
}
