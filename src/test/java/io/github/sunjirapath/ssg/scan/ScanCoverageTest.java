package io.github.sunjirapath.ssg.scan;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScanCoverageTest {

    @Test
    void invalidUtf8PropertiesFallBackToIso88591(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("legacy"));
        repo.bytes("src/main/resources/application.properties",
                new byte[]{'n', 'a', 'm', 'e', '=', 'c', 'a', 'f', (byte) 0xe9, '\n'});

        Config config = load(repo, "application.properties");

        assertThat(config.get("name").value()).isEqualTo("café");
    }

    @Test
    void valueOfSecretKeyStaysUnresolved(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("secret")).resource("application.properties", "api-token=do-not-leak\n");

        Value value = load(repo, "application.properties").valueOf("api-token");

        assertThat(value.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(value.text()).isEqualTo(Value.UNKNOWN);
        assertThat(value.raw()).isEqualTo("${api-token}");
    }

    @Test
    void trailingContinuationAtEndOfPropertiesFileIsHandled(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("continuation")).resource("application.properties", "key=value\\");

        Config config = load(repo, "application.properties");

        assertThat(config.get("key").value()).isEqualTo("value");
    }

    @Test
    void yamlMergeSequenceAppliesEveryAnchorBeforeExplicitValues(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("merge-sequence")).resource("application.yml", """
                first: &first
                  host: one
                second: &second
                  port: 8080
                client:
                  <<: [*first, *second]
                  host: explicit
                """);

        Config config = load(repo, "application.yml");

        assertThat(config.get("client.host").value()).isEqualTo("explicit");
        assertThat(config.get("client.port").value()).isEqualTo("8080");
    }

    @Test
    void yamlComplexMappingKeyIsIgnoredWhileScalarKeysRemain(@TempDir Path tmp) {
        var repo = TempRepo.at(tmp.resolve("complex-key")).resource("application.yml", """
                ? [one, two]
                : ignored
                normal: kept
                """);

        Config config = load(repo, "application.yml");

        assertThat(config.withPrefix("")).containsOnlyKeys("normal");
        assertThat(config.get("normal").value()).isEqualTo("kept");
    }

    @Test
    void resolveTypeWithDetachedContextReturnsEmpty(@TempDir Path tmp) {
        JavaIndex index = TempRepo.at(tmp.resolve("repo"))
                .java("x/Thing.java", "package x; class Thing {}")
                .index();

        assertThat(index.resolveType("Thing", StaticJavaParser.parseExpression("value"))).isEmpty();
    }

    @Test
    void enclosingTypeNameResolvesBeforeImports(@TempDir Path tmp) {
        JavaIndex index = TempRepo.at(tmp.resolve("enclosing")).java("x/Outer.java", """
                package x;
                class Outer {
                    class Inner {
                        static final String PATH = "/self";
                        void m() { use(Inner.PATH); }
                    }
                }
                """).index();

        assertThat(fold(index).text()).isEqualTo("/self");
    }

    @Test
    void memberTypeResolvesFromItsEnclosingType(@TempDir Path tmp) {
        JavaIndex index = TempRepo.at(tmp.resolve("member")).java("x/Outer.java", """
                package x;
                class Outer {
                    static class Inner { static final String PATH = "/member"; }
                    void m() { use(Inner.PATH); }
                }
                """).index();

        assertThat(fold(index).text()).isEqualTo("/member");
    }

    @Test
    void nonVariableForInitializerDoesNotHideField(@TempDir Path tmp) {
        JavaIndex index = TempRepo.at(tmp.resolve("for-assignment")).java("x/Use.java", """
                package x;
                class Use {
                    String path = "/field";
                    int i;
                    void m() { for (i = 0; i < 1; i++) { use(path); } }
                }
                """).index();

        assertThat(fold(index).text()).isEqualTo("/field");
    }

    @Test
    void deeplyInheritedConstantIsResolved(@TempDir Path tmp) {
        JavaIndex index = TempRepo.at(tmp.resolve("deep-inheritance")).java("x/Use.java", """
                package x;
                class C00 { static final String PATH = "/deep"; }
                class C01 extends C00 {} class C02 extends C01 {} class C03 extends C02 {}
                class C04 extends C03 {} class C05 extends C04 {} class C06 extends C05 {}
                class C07 extends C06 {} class C08 extends C07 {} class C09 extends C08 {}
                class C10 extends C09 {} class C11 extends C10 {} class C12 extends C11 {}
                class C13 extends C12 {} class C14 extends C13 {} class C15 extends C14 {}
                class C16 extends C15 {} class C17 extends C16 {} class C18 extends C17 {}
                class C19 extends C18 {} class C20 extends C19 {} class C21 extends C20 {}
                class C22 extends C21 {} class C23 extends C22 {} class C24 extends C23 {}
                class C25 extends C24 {} class Use extends C25 { void m() { use(PATH); } }
                """).index();

        // EXPECTATION?: Legal enterprise inheritance chains should not silently turn a source constant into an unknown value.
        assertThat(fold(index).text()).isEqualTo("/deep");
    }

    @Test
    void missingExplicitThisFieldRemainsUnresolved(@TempDir Path tmp) {
        Value value = fold(TempRepo.at(tmp.resolve("missing-this")).java("x/Use.java", """
                package x;
                class Use { void m() { use(this.missing); } }
                """).index());

        assertThat(value.text()).isEqualTo(Value.UNKNOWN);
        assertThat(value.raw()).isEqualTo("this.missing");
    }

    @Test
    void nonPlusBinaryExpressionIsNotFolded(@TempDir Path tmp) {
        Value value = fold(TempRepo.at(tmp.resolve("minus")).java("x/Use.java", """
                package x;
                class Use { void m() { use(4 - 2); } }
                """).index());

        assertThat(value.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(value.raw()).isEqualTo("4 - 2");
    }

    @Test
    void discoveryLookupWithWrongArityIsNotInvented(@TempDir Path tmp) {
        Value value = fold(TempRepo.at(tmp.resolve("lookup-arity")).java("x/Use.java", """
                package x;
                class Use { void m() { use(discoveryClient.getInstances().get(0).getUri()); } }
                """).index());

        assertThat(value.confidence()).isEqualTo(Confidence.UNRESOLVED);
    }

    @Test
    void supportedUriConversionsPreserveResolvedText(@TempDir Path tmp) {
        JavaIndex index = TempRepo.at(tmp.resolve("uri-conversions")).java("x/Use.java", """
                package x;
                class Use { Object[] values = {"https://host/x".toURI(), "https://host/y".toUriString()}; }
                """).index();

        var initializer = index.units().getFirst().findFirst(com.github.javaparser.ast.expr.ArrayInitializerExpr.class).orElseThrow();
        assertThat(index.evalAll(initializer)).extracting(Value::text)
                .containsExactly("https://host/x", "https://host/y");
    }

    @Test
    void stringValueOfRequiresStringReceiverAndOneArgument(@TempDir Path tmp) {
        JavaIndex index = TempRepo.at(tmp.resolve("value-of-shape")).java("x/Use.java", """
                package x;
                class Use { Object[] values = {String.valueOf(), Other.valueOf(1)}; }
                """).index();

        var initializer = index.units().getFirst().findFirst(com.github.javaparser.ast.expr.ArrayInitializerExpr.class).orElseThrow();
        assertThat(index.evalAll(initializer)).allSatisfy(value ->
                assertThat(value.confidence()).isEqualTo(Confidence.UNRESOLVED));
    }

    private static Config load(TempRepo repo, String resource) {
        Path file = repo.root().resolve("src/main/resources").resolve(resource);
        return Config.load(Repo.of(repo.root()), List.of(file), "default");
    }

    private static Value fold(JavaIndex index) {
        MethodCallExpr use = index.units().stream().flatMap(unit -> unit.findAll(MethodCallExpr.class).stream())
                .filter(call -> call.getNameAsString().equals("use")).findFirst().orElseThrow();
        return index.eval(use.getArgument(0));
    }
}
