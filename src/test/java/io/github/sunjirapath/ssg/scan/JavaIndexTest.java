package io.github.sunjirapath.ssg.scan;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Constant folding across files without a symbol solver. */
class JavaIndexTest {

    @TempDir
    Path repo;

    private void src(String path, String code) throws IOException {
        Path f = repo.resolve("src/main/java").resolve(path);
        Files.createDirectories(f.getParent());
        Files.writeString(f, code);
    }

    /** Folds the first argument of the first {@code use(..)} call in Use.java. */
    private Value fold() {
        var index = JavaIndex.build(Repo.of(repo));
        var call = index.units().stream().flatMap(u -> u.findAll(MethodCallExpr.class).stream())
                .filter(m -> m.getNameAsString().equals("use")).findFirst().orElseThrow();
        return index.eval(call.getArgument(0));
    }

    @Test
    void qualifiedSuperFieldUsesTheSuperclassDeclaration() throws IOException {
        src("a/A.java", """
                package a;
                class Base { String binder = "rabbit"; }
                class Child extends Base {
                    Object binder = new Object();
                    void send() { use(super.binder); use(this.binder); }
                }
                """);
        var index = JavaIndex.build(Repo.of(repo));
        var fields = index.units().stream().flatMap(u -> u.findAll(MethodCallExpr.class).stream())
                .filter(m -> m.getNameAsString().equals("use")).map(m -> m.getArgument(0)).toList();
        assertThat(index.declaredType("binder", fields.get(0))).contains("String");
        assertThat(index.declaredType("binder", fields.get(1))).contains("Object");
    }

    @Test
    void compactedTreesKeepLinesNamesAndStructure() throws IOException {
        src("a/A.java", "package a;\nclass A {\n  void m() { use(1); }\n}\n");
        src("a/B.java", "package a;\nclass B {\n\n  void m() { use(2); }\n}\n");
        var calls = JavaIndex.build(Repo.of(repo)).units().stream()
                .flatMap(u -> u.findAll(MethodCallExpr.class).stream()).toList();
        assertThat(calls).extracting(c -> c.getBegin().orElseThrow().line).containsExactlyInAnyOrder(3, 4);
        assertThat(calls).allSatisfy(c -> {
            assertThat(c.getNameAsString()).isEqualTo("use");
            assertThat(c.getArguments()).hasSize(1);
            assertThat(c.getTokenRange()).isEmpty();
            assertThat(c.getArgument(0).getParentNode()).containsSame(c);
        });
        // names are shared across files, so equal identifiers are one instance
        assertThat(calls.get(0).getName().getIdentifier()).isSameAs(calls.get(1).getName().getIdentifier());
    }

    @Test
    void constantFromAnotherPackageViaImport() throws IOException {
        src("a/Topics.java", "package a; public final class Topics { public static final String P = \"acc\"; "
                + "public static final String CREATED = P + \".created\"; }");
        src("b/Use.java", "package b; import a.Topics; class Use { void m() { use(Topics.CREATED); } }");
        Value v = fold();
        assertThat(v.text()).isEqualTo("acc.created");
        assertThat(v.confidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(v.via()).anyMatch(e -> e.file().equals("src/main/java/a/Topics.java"));
    }

    @Test
    void staticImportNestedClassAndInterfaceConstant() throws IOException {
        src("a/Api.java", "package a; public interface Api { String BASE = \"/v1\"; class Paths { public static final String X = BASE + \"/x\"; } }");
        src("b/Use.java", "package b; import static a.Api.Paths.X; class Use { void m() { use(X + \"/y\"); } }");
        assertThat(fold().text()).isEqualTo("/v1/x/y");
    }

    @Test
    void inheritedConstantLocalVariableAndValueField() throws IOException {
        src("a/Base.java", "package a; public class Base { protected static final String HOST = \"http://svc\"; }");
        src("a/Use.java", """
                package a;
                class Use extends Base {
                    @org.springframework.beans.factory.annotation.Value("${topic.x:dflt}") String topic;
                    void m() { String url = HOST + "/p"; use(url + topic); }
                }""");
        assertThat(fold().text()).isEqualTo("http://svc/p${topic.x:dflt}");
    }

    @Test
    void runtimeValuesBecomeUnknownParts() throws IOException {
        src("a/Use.java", "package a; class Use { void m(String r, long id) { use(base(r) + \"/r/\" + id); } String base(String r) { return \"x\" + r; } }");
        Value v = fold();
        assertThat(v.text()).isEqualTo(Value.UNKNOWN + "/r/" + Value.UNKNOWN);
        assertThat(v.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(v.raw()).isEqualTo("base(r) + \"/r/\" + id");
    }

    @Test
    void discoveryClientLookupBecomesLoadBalancedUrl() throws IOException {
        src("a/Use.java", "package a; class Use { Object dc; void m() { use(dc.getInstances(\"customers\").get(0).getUri() + \"/owners\"); } }");
        assertThat(fold().text()).isEqualTo("lb://customers/owners");
    }

    @Test
    void zeroArgHelperReturningAnExpressionIsFolded() throws IOException {
        src("a/Use.java", """
                package a;
                class Use {
                    Object dc;
                    private java.net.URI base() { return dc.getInstances("customers").get(0).getUri(); }
                    void m() { use(base() + "/owners"); }
                }""");
        Value v = fold();
        assertThat(v.text()).isEqualTo("lb://customers/owners");
        assertThat(v.confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    void java21SyntaxParses() throws IOException {
        src("a/Use.java", """
                package a;
                sealed interface Shape permits Circle {}
                record Circle(double r) implements Shape {}
                class Use {
                    String m(Object o) { return switch (o) { case Circle(double r) when r > 0 -> "c"; default -> "x"; }; }
                    void n() { use("ok"); }
                }""");
        var index = JavaIndex.build(Repo.of(repo));
        assertThat(index.problems()).isEmpty();
        assertThat(index.units().getFirst().findAll(AnnotationExpr.class)).isEmpty();
        assertThat(fold().text()).isEqualTo("ok");
    }
}
