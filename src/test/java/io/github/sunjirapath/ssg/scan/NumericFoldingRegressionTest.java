package io.github.sunjirapath.ssg.scan;

import com.github.javaparser.ast.expr.MethodCallExpr;
import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NumericFoldingRegressionTest {

    @TempDir Path tmp;

    @Test
    void onlyStringPlusIsFoldedAndIntegralLiteralsUseRuntimeSpelling() {
        var repo = TempRepo.at(tmp.resolve("numeric-plus")).java("x/Use.java", """
                package x;
                class Use {
                    static final char LETTER = 'A';
                    static final int COUNT = 1;
                    void use(String value) {}
                    void m() {
                        use("/v" + (1 + 1));
                        use("/v" + 1 + 1);
                        use("/v" + (LETTER + 1));
                        use("/v" + ((int) 'A' + 1));
                        use("/v" + LETTER);
                        use("/v" + (COUNT + 1));
                        use("/v" + 0x10);
                        use("/v" + 010);
                        use("/v" + 0b1_010);
                        use("/v" + 1_000L);
                    }
                }
                """);
        var index = repo.index();
        List<Value> values = index.units().stream().flatMap(unit -> unit.findAll(MethodCallExpr.class).stream())
                .filter(call -> call.getNameAsString().equals("use"))
                .map(call -> index.eval(call.getArgument(0))).toList();

        assertThat(values).extracting(Value::confidence)
                .containsExactly(Confidence.UNRESOLVED, Confidence.EXACT, Confidence.UNRESOLVED,
                        Confidence.UNRESOLVED, Confidence.RESOLVED, Confidence.UNRESOLVED, Confidence.EXACT,
                        Confidence.EXACT, Confidence.EXACT, Confidence.EXACT);
        assertThat(values.get(1).text()).isEqualTo("/v11");
        assertThat(values.get(4).text()).isEqualTo("/vA");
        assertThat(values.subList(6, values.size())).extracting(Value::text)
                .containsExactly("/v16", "/v8", "/v10", "/v1000");
    }

    @Test
    void preservesImportedAndHelperStringConcatenationButRejectsNumericHelpers() {
        var repo = TempRepo.at(tmp.resolve("constants"))
                .java("x/Constants.java", """
                        package x;
                        class Constants { static final String PREFIX = "/v", SUFFIX = "1"; static final int COUNT = 1; }
                        """)
                .java("x/Use.java", """
                        package x;
                        import static x.Constants.*;
                        class Use {
                            String prefix() { return "/v"; }
                            String suffix() { return "1"; }
                            int count() { return 1; }
                            void m() {
                                use(PREFIX + SUFFIX);
                                use(prefix() + suffix());
                                use("/v" + (COUNT + 1));
                                use("/v" + (count() + 1));
                                use("/v" + (int) 'A');
                            }
                        }
                        """);
        var index = repo.index();
        List<Value> values = index.units().stream().flatMap(u -> u.findAll(MethodCallExpr.class).stream())
                .filter(c -> c.getNameAsString().equals("use")).map(c -> index.eval(c.getArgument(0))).toList();
        assertThat(values.subList(0, 2)).extracting(Value::text).containsExactly("/v1", "/v1");
        assertThat(values.subList(0, 2)).allMatch(Value::resolved);
        assertThat(values.subList(2, 5)).allMatch(v -> v.confidence() == Confidence.UNRESOLVED);
    }

    @Test
    void rejectsNumericEnumGetterArithmeticButKeepsStringEnumGetterConcatenation() {
        var repo = TempRepo.at(tmp.resolve("enum-getters")).java("x/Use.java", """
                package x;
                import lombok.Getter;
                enum Version {
                    ONE(1, "one");
                    private final int code;
                    @Getter private final String label;
                    Version(int code, String label) { this.code = code; this.label = label; }
                    int getCode() { return code; }
                }
                class Use {
                    void use(String value) {}
                    void m() {
                        use("http://orders/v" + (Version.ONE.getCode() + 1));
                        use("/v" + (Version.ONE.getLabel() + 1));
                        use("/v" + (Version.ONE.getCode() + "x"));
                        use("/v" + ((Version.ONE.getCode() + 1) + 2));
                        use("/v" + (Version.ONE.getCode() + 1 + "x"));
                        use("/v" + Version.ONE.getCode() + 1);
                        use("/v" + ((Version.ONE.getLabel() + "x") + 1));
                    }
                }
                """);
        var index = repo.index();
        List<Value> values = index.units().stream().flatMap(unit -> unit.findAll(MethodCallExpr.class).stream())
                .filter(call -> call.getNameAsString().equals("use"))
                .map(call -> index.eval(call.getArgument(0))).toList();

        assertThat(values).hasSize(7);
        assertThat(values.get(0).confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(values.get(1)).satisfies(value -> {
            assertThat(value.text()).isEqualTo("/vone1");
            assertThat(value.resolved()).isTrue();
        });
        assertThat(values.get(2)).satisfies(value -> {
            assertThat(value.text()).isEqualTo("/v1x");
            assertThat(value.resolved()).isTrue();
        });
        assertThat(values.subList(3, 5)).allMatch(v -> v.confidence() == Confidence.UNRESOLVED);
        assertThat(values.subList(5, 7)).extracting(Value::text).containsExactly("/v11", "/vonex1");
        assertThat(values.subList(5, 7)).allMatch(Value::resolved);
    }
}
