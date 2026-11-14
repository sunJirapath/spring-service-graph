package io.github.sunjirapath.ssg.scan;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaIndexBranchGap2Test {
    @TempDir Path tmp;

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "2L | 2x", "2.5 | {?}x", "'A' | Ax", "-2 | {?}x"
    })
    void numericLiterals(String expression, String concatenated) {
        var index = source("""
                class Use { void m() { use(%s + 1); use(%s + "x"); } }
                """.formatted(expression, expression));

        var values = values(index);

        assertThat(values).extracting(Value::text).containsExactly(Value.UNKNOWN, concatenated);
        assertThat(values.getFirst().confidence()).isEqualTo(Confidence.UNRESOLVED);
    }

    @Test
    void inferredNumericLocals() {
        var index = source("""
                class Use { void m() {
                    var number = 2L;
                    var text = "2";
                    var object = new Object();
                    use(number + 1); use(text + 1); use(object + 1);
                } }
                """);

        assertThat(values(index)).extracting(Value::text).containsExactly("{?}", "21", "{?}1");
        assertThat(values(index)).extracting(Value::confidence)
                .containsExactly(Confidence.UNRESOLVED, Confidence.RESOLVED, Confidence.UNRESOLVED);
    }

    @Test
    void numericParameters() {
        var index = source("""
                class Use { void m(long number, String text) { use(number + 1); use(text + 1); } }
                """);

        assertThat(values(index)).extracting(Value::text).containsExactly("{?}", "{?}1");
    }

    @Test
    void thisNumericHelpers() {
        var index = source("""
                class Use {
                    Use other;
                    long number() { return 7L; }
                    String text() { return "7"; }
                    long number(int ignored) { return 7L; }
                    void m() {
                        use(this.number() + 1); use(this.text() + 1);
                        use(other.number() + 1); use(this.number(0) + 1);
                    }
                }
                """);

        // FINDING (pins current behaviour, not endorsed; invert when fixed): long + int through a non-`this` receiver
        // or an overloaded call is not recognised as numeric, so it renders as string concatenation "{?}1".
        assertThat(values(index)).extracting(Value::text).containsExactly("{?}", "71", "{?}1", "{?}1");
    }

    @Test
    void nestedNumericPlus() {
        var index = source("""
                class Use { void m() { use((1L + 2) + 3); use((1L + "2") + 3); } }
                """);

        assertThat(values(index)).extracting(Value::text).containsExactly("{?}", "123");
        assertThat(values(index)).extracting(Value::confidence)
                .containsExactly(Confidence.UNRESOLVED, Confidence.EXACT);
    }

    @Test
    void numericDepthLimit() {
        var code = new StringBuilder("class Use { void m() { var n0 = 1;\n");
        for (int i = 1; i <= 25; i++) code.append("var n").append(i).append(" = n").append(i - 1).append(";\n");
        code.append("use(n23 + 1); use(n25 + 1); } }");
        var index = source(code.toString());

        // n23 is inside the numeric-lookup depth limit; n25 is beyond it and falls back to "{?}1" (documented limit, not endorsed).
        assertThat(values(index)).extracting(Value::text).containsExactly("{?}", "{?}1");
    }

    @Test
    void integralCasts() {
        var index = source("""
                class Use { void m() {
                    use((int) 0x10); use((long) 0b1_010); use((long) 010L);
                    use((long) 'A'); use((int) 1L);
                } }
                """);

        assertThat(values(index)).extracting(Value::text).containsExactly("16", "10", "8", "{?}", "{?}");
        assertThat(values(index)).extracting(Value::confidence).containsExactly(
                Confidence.EXACT, Confidence.EXACT, Confidence.EXACT, Confidence.UNRESOLVED, Confidence.UNRESOLVED);
    }

    @Test
    void moduleFallback() {
        var index = TempRepo.at(tmp.resolve("modules"))
                .file("a/src/main/java/shared/Constants.java", "package shared; class Constants { String origin = \"a\"; }")
                .file("b/src/main/java/shared/Constants.java", "package shared; class Constants { String origin = \"b\"; }")
                .file("b/src/main/java/Use.java", "class Use {}")
                .index();
        var context = index.units().stream().filter(cu -> index.fileOf(cu).getFileName().toString().equals("Use.java"))
                .findFirst().orElseThrow();
        var detached = index.resolveType("shared.Constants", new NameExpr("outside")).orElseThrow();
        var local = index.resolveType("shared.Constants", context).orElseThrow();

        assertThat(index.repo().relative(index.fileOf(detached.findCompilationUnit().orElseThrow())))
                .isEqualTo("a/src/main/java/shared/Constants.java");
        assertThat(index.repo().relative(index.fileOf(local.findCompilationUnit().orElseThrow())))
                .isEqualTo("b/src/main/java/shared/Constants.java");
    }

    @Test
    void forInitializerNames() {
        var index = source("""
                class Use { String missing = "/field"; void m() {
                    for (String other = "/other", path = "/loop"; ready(); ) { use(path); use(missing); }
                } }
                """);

        assertThat(values(index)).extracting(Value::text).containsExactly("/loop", "/field");
    }

    @Test
    void recordComponentTypes() {
        var index = source("""
                record Use(long number, String text) {
                    void m() { use(number + 1); use(text + 1); use(missing); }
                }
                """);
        var uses = calls(index);
        var number = uses.get(0).getArgument(0).asBinaryExpr().getLeft();
        var text = uses.get(1).getArgument(0).asBinaryExpr().getLeft();

        assertThat(index.declarationOf("number", number).orElseThrow()).isInstanceOf(Parameter.class);
        assertThat(index.declaredType("number", number).orElseThrow()).isEqualTo("long");
        assertThat(index.declaredType("text", text).orElseThrow()).isEqualTo("String");
        assertThat(index.declarationOf("missing", uses.get(2).getArgument(0))).isEmpty();
        assertThat(values(index)).extracting(Value::text).containsExactly("{?}", "{?}1", "{?}");
    }

    @Test
    void cyclicInheritance() {
        var index = source("""
                class A extends B {}
                class B extends A { String known = "/known"; }
                class Use extends A { void m() { use(known); use(missing); } }
                """);

        assertThat(values(index)).extracting(Value::text).containsExactly("/known", "{?}");
    }

    @Test
    void assignmentChains() {
        var index = source("""
                class Use { void m() {
                    String a = "/a";
                    String b = "/b";
                    use(a);
                    a = b;
                    use(a);
                    b = "/new";
                    a = b;
                    use(a);
                } }
                """);

        assertThat(values(index)).extracting(Value::text).containsExactly("/a", "/b", "/new");
    }

    @Test
    void assignmentPlacement() {
        var index = source("""
                class Use { void m() {
                    String straight = "/initial";
                    straight = "/next";
                    use(straight);
                    String nested = "/initial";
                    consume(nested = "/next");
                    use(nested);
                    for (String loop = "/initial"; ready(); ) { loop = "/next"; use(loop); }
                } }
                """);

        assertThat(values(index)).extracting(Value::text).containsExactly("/next", "{?}", "{?}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"&id=", " WHERE id="})
    void suffixPrefixes(String suffix) {
        var index = source("""
                class Use { void m(String runtime) {
                    String appended = "/items";
                    appended += "%s" + runtime;
                    String assigned = "/items";
                    assigned = assigned + "%s" + runtime;
                    String path = "/items";
                    path += "/" + runtime;
                    use(appended); use(assigned); use(path);
                } }
                """.formatted(suffix, suffix));

        assertThat(values(index)).extracting(Value::text).containsExactly("/items", "/items", "{?}");
    }

    @Test
    void nonPlusAssignments() {
        var index = source("""
                class Use { void m(int delta) {
                    int numeric = 5;
                    numeric -= delta;
                    int plain = 5;
                    plain = 6;
                    String difference = "/path";
                    difference = difference - delta;
                    use(numeric); use(plain); use(difference);
                } }
                """);

        assertThat(values(index)).extracting(Value::text).containsExactly("{?}", "6", "{?}");
    }

    @Test
    void injectedParameterVersusLocal() {
        var index = source("""
                class Use {
                    String injected = "/default", reassigned = "/default", appended = "/default";
                    void set(String value) { this.injected = value; }
                    void reset() { String value = "/new"; this.reassigned = value; }
                    void append(String value) { this.appended += value; }
                    void m() { use(injected); use(reassigned); use(appended); }
                }
                """);

        assertThat(values(index)).extracting(Value::text).containsExactly("/default", "{?}", "{?}");
    }

    @Test
    void truncatedEnumArguments() {
        var index = source("""
                @Getter @AllArgsConstructor enum Topic {
                    TRUNCATED("first"), COMPLETE("first", "topic");
                    static String ignored;
                    String first, topic;
                }
                class Use { void m() { use(Topic.TRUNCATED.getTopic()); use(Topic.COMPLETE.getTopic()); } }
                """);

        assertThat(values(index)).extracting(Value::text).containsExactly("{?}", "topic");
        assertThat(values(index)).extracting(Value::confidence)
                .containsExactly(Confidence.UNRESOLVED, Confidence.RESOLVED);
    }

    @Test
    void laterRecordRecovery() {
        var index = source("""
                import java.util.List;
                record First(String value) {}
                record Annotated(List<@Valid String> values) {
                    public List<String> values() { return values; }
                }
                record Bad(List<String> values) {
                    public List<Integer> values() { return null; }
                }
                """);

        assertThat(index.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.line()).isEqualTo(6);
            assertThat(problem.message()).startsWith("Incorrect component accessor return type.");
        });
        assertThat(index.units()).singleElement().satisfies(unit ->
                assertThat(unit.getTypes()).extracting(t -> t.getNameAsString())
                        .containsExactly("First", "Annotated", "Bad"));
    }

    private JavaIndex source(String code) {
        return TempRepo.at(tmp.resolve("repo")).java("Use.java", code).index();
    }

    private static List<MethodCallExpr> calls(JavaIndex index) {
        return index.units().stream().flatMap(unit -> unit.findAll(MethodCallExpr.class).stream())
                .filter(call -> call.getNameAsString().equals("use")).toList();
    }

    private static List<Value> values(JavaIndex index) {
        return calls(index).stream().map(call -> index.eval(call.getArgument(0))).toList();
    }
}
