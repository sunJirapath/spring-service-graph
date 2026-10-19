package io.github.sunjirapath.ssg.scan;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class JavaIndexEdgeCasesTest {

    @TempDir
    Path tmp;

    @Test
    void foldsPrimitiveAndEmptyLiteralsExactly() {
        var index = index(repo("literals").java("x/Use.java", """
                package x;
                class Use { Object[] values = {"", "text", 'z', 42, 43L, true, false}; }
                """));

        var values = index.evalAll(arrayInitializer(index));

        assertThat(values).extracting(Value::text)
                .containsExactly("", "text", "z", "42", "43", "true", "false");
        assertThat(values).extracting(Value::confidence).containsOnly(Confidence.EXACT);
    }

    @Test
    void foldsEnumConstantGetterThroughConstructorOrLombokFieldOrder() {
        var ctor = fold(repo("enum-ctor").java("x/Use.java", """
                package x;
                enum Topic { SMS("sms-topic", "Send SMS"); Topic(String t, String n) { this.topic = t; name = n; }
                    String topic; String name; String getTopic() { return topic; } }
                class Use { void m() { use(Topic.SMS.getTopic()); } }
                """));
        var lombok = fold(repo("enum-lombok").java("x/Use.java", """
                package x;
                @Getter enum Queue { CANCEL("ex", "order.cancel", "key"); private String exchange; private String name; private String routeKey; }
                class Use { void m() { use(Queue.CANCEL.getRouteKey()); } }
                """));

        assertThat(ctor.text()).isEqualTo("sms-topic");
        assertThat(lombok.text()).isEqualTo("key");
    }

    @Test
    void decodesEscapesAndUnicodeEscapesInStringLiterals() {
        var value = fold(repo("escapes").java("x/Use.java", """
                package x;
                class Use { void m() { use("line\\ncol\\tquote:\\\" unicode:\\u0041"); } }
                """));

        assertThat(value.text()).isEqualTo("line\ncol\tquote:\" unicode:A");
        assertThat(value.confidence()).isEqualTo(Confidence.EXACT);
    }

    @Test
    void foldsTextBlockLiteralExactly() {
        var value = fold(repo("text-block").java("x/Use.java", """
                package x;
                class Use {
                    void m() {
                        use(\"""
                            alpha
                              beta
                            \""");
                    }
                }
                """));

        assertThat(value.text()).isEqualTo("alpha\n  beta\n");
        assertThat(value.confidence()).isEqualTo(Confidence.EXACT);
    }

    @Test
    void concatenatesStringsIntegersAndCharacters() {
        var value = fold(repo("concat").java("x/Use.java", """
                package x;
                class Use { void m() { use("v" + 2 + ':' + 7L); } }
                """));

        assertThat(value.text()).isEqualTo("v2:7");
        assertThat(value.confidence()).isEqualTo(Confidence.EXACT);
    }

    @Test
    void ignoresParenthesesAndCastsWhileFolding() {
        var value = fold(repo("casts").java("x/Use.java", """
                package x;
                class Use { void m() { use(((String) ("/api")) + ((long) 2L)); } }
                """));

        assertThat(value.text()).isEqualTo("/api2");
        assertThat(value.confidence()).isEqualTo(Confidence.EXACT);
    }

    @Test
    void evalAllWrapsANonArrayExpression() {
        var index = index(repo("eval-one").java("x/Use.java", """
                package x;
                class Use { void m() { use("one"); } }
                """));

        assertThat(index.evalAll(useCall(index).getArgument(0)))
                .singleElement().extracting(Value::text).isEqualTo("one");
    }

    @Test
    void resolvesLocalVariableDeclaredBeforeUse() {
        var value = fold(repo("local-before").java("x/Use.java", """
                package x;
                class Use { void m() { String path = "/local"; use(path); } }
                """));

        assertThat(value.text()).isEqualTo("/local");
        assertThat(value.confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    void doesNotResolveLocalVariableDeclaredAfterUse() {
        var value = fold(repo("local-after").java("x/Use.java", """
                package x;
                class Use { void m() { use(path); String path = "/too-late"; } }
                """));

        assertThat(value.text()).isEqualTo(Value.UNKNOWN);
        assertThat(value.raw()).isEqualTo("path");
        assertThat(value.confidence()).isEqualTo(Confidence.UNRESOLVED);
    }

    @Test
    void localVariableShadowsField() {
        var value = fold(repo("local-shadow").java("x/Use.java", """
                package x;
                class Use {
                    String path = "/field";
                    void m() { String path = "/local"; use(path); }
                }
                """));

        assertThat(value.text()).isEqualTo("/local");
    }

    @Test
    void fieldIsUsedBeforeLaterLocalShadowBegins() {
        var value = fold(repo("shadow-start").java("x/Use.java", """
                package x;
                class Use {
                    String path = "/field";
                    void m() { use(path); String path = "/local"; }
                }
                """));

        assertThat(value.text()).isEqualTo("/field");
    }

    @Test
    void resolvesLocalDeclaredInForInitializer() {
        var value = fold(repo("for-initializer").java("x/Use.java", """
                package x;
                class Use { void m() { for (String path = "/loop"; ready(); next()) { use(path); } } }
                """));

        assertThat(value.text()).isEqualTo("/loop");
        assertThat(value.confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    void fieldShadowsInheritedConstant() {
        var value = fold(repo("field-shadow")
                .java("x/Base.java", """
                        package x;
                        class Base { static final String PATH = "/base"; }
                        """)
                .java("x/Use.java", """
                        package x;
                        class Use extends Base {
                            static final String PATH = "/child";
                            void m() { use(PATH); }
                        }
                        """));

        assertThat(value.text()).isEqualTo("/child");
    }

    @Test
    void methodParameterIsUnresolvedAndKeepsRawName() {
        var value = fold(repo("parameter").java("x/Use.java", """
                package x;
                class Use { void m(String path) { use(path); } }
                """));

        assertUnresolvedName(value, "path");
    }

    @Test
    void lambdaParameterIsUnresolvedAndKeepsRawName() {
        var value = fold(repo("lambda-parameter").java("x/Use.java", """
                package x;
                class Use { void m() { stream.map(path -> use(path)); } }
                """));

        assertUnresolvedName(value, "path");
    }

    @Test
    void recordComponentIsUnresolvedAndKeepsRawName() {
        var value = fold(repo("record-component").java("x/Use.java", """
                package x;
                record Use(String path) { void m() { use(path); } }
                """));

        assertUnresolvedName(value, "path");
    }

    @Test
    void resolvesExplicitThisField() {
        var value = fold(repo("this-field").java("x/Use.java", """
                package x;
                class Use { String path = "/this"; void m() { use(this.path); } }
                """));

        assertThat(value.text()).isEqualTo("/this");
        assertThat(value.confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    void valueAnnotatedFieldPreservesPlaceholderAsResolvedText() {
        var value = fold(repo("value-field").java("x/Use.java", """
                package x;
                class Use {
                    @Value("${x:d}") String path;
                    void m() { use(path); }
                }
                """));

        assertThat(value.text()).isEqualTo("${x:d}");
        assertThat(value.confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    void valueAnnotatedConstructorParameterPreservesPlaceholderAsResolvedText() {
        var value = fold(repo("value-constructor").java("x/Use.java", """
                package x;
                class Use {
                    Use(@Value("${x:d}") String path) { use(path); }
                }
                """));

        assertThat(value.text()).isEqualTo("${x:d}");
        assertThat(value.confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    void knownLimitNonFinalReassignedFieldStillFoldsItsInitializer() {
        var value = fold(repo("reassigned").java("x/Use.java", """
                package x;
                class Use {
                    String path = "/initial";
                    void m() { path = runtime(); use(path); }
                }
                """));

        assertThat(value.text()).isEqualTo("/initial");
        assertThat(value.confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    void finalFieldAssignedOnlyInConstructorIsUnresolved() {
        var value = fold(repo("constructor-assignment").java("x/Use.java", """
                package x;
                class Use {
                    final String path;
                    Use() { path = "/assigned"; }
                    void m() { use(path); }
                }
                """));

        assertUnresolvedName(value, "path");
    }

    @Test
    void fieldAssignedOnlyInStaticBlockIsUnresolved() {
        var value = fold(repo("static-assignment").java("x/Use.java", """
                package x;
                class Use {
                    static final String path;
                    static { path = "/assigned"; }
                    void m() { use(path); }
                }
                """));

        assertUnresolvedName(value, "path");
    }

    @Test
    void resolvesClassConstantInSamePackage() {
        var value = fold(repo("same-package")
                .java("x/Paths.java", """
                        package x;
                        class Paths { static final String API = "/same"; }
                        """)
                .java("x/Use.java", """
                        package x;
                        class Use { void m() { use(Paths.API); } }
                        """));

        assertThat(value.text()).isEqualTo("/same");
    }

    @Test
    void resolvesClassConstantThroughSingleTypeImport() {
        var value = fold(repo("single-import")
                .java("a/Paths.java", """
                        package a;
                        public class Paths { public static final String API = "/single"; }
                        """)
                .java("b/Use.java", """
                        package b;
                        import a.Paths;
                        class Use { void m() { use(Paths.API); } }
                        """));

        assertThat(value.text()).isEqualTo("/single");
    }

    @Test
    void resolvesClassConstantThroughOnDemandImport() {
        var value = fold(repo("on-demand-import")
                .java("a/Paths.java", """
                        package a;
                        public class Paths { public static final String API = "/wildcard"; }
                        """)
                .java("b/Use.java", """
                        package b;
                        import a.*;
                        class Use { void m() { use(Paths.API); } }
                        """));

        assertThat(value.text()).isEqualTo("/wildcard");
    }

    @Test
    void resolvesConstantThroughSingleStaticImport() {
        var value = fold(repo("static-single")
                .java("a/Paths.java", """
                        package a;
                        public class Paths { public static final String API = "/static"; }
                        """)
                .java("b/Use.java", """
                        package b;
                        import static a.Paths.API;
                        class Use { void m() { use(API); } }
                        """));

        assertThat(value.text()).isEqualTo("/static");
    }

    @Test
    void resolvesConstantThroughOnDemandStaticImport() {
        var value = fold(repo("static-wildcard")
                .java("a/Paths.java", """
                        package a;
                        public class Paths { public static final String API = "/static-all"; }
                        """)
                .java("b/Use.java", """
                        package b;
                        import static a.Paths.*;
                        class Use { void m() { use(API); } }
                        """));

        assertThat(value.text()).isEqualTo("/static-all");
    }

    @Test
    void resolvesNestedClassConstant() {
        var value = fold(repo("nested")
                .java("x/Outer.java", """
                        package x;
                        class Outer { static class Inner { static final String API = "/nested"; } }
                        """)
                .java("x/Use.java", """
                        package x;
                        class Use { void m() { use(Outer.Inner.API); } }
                        """));

        assertThat(value.text()).isEqualTo("/nested");
    }

    @Test
    void resolvesDeeplyNestedClassConstant() {
        var value = fold(repo("deeply-nested")
                .java("x/Outer.java", """
                        package x;
                        class Outer {
                            static class A { static class B { static class C { static final String API = "/deep"; } } }
                        }
                        """)
                .java("x/Use.java", """
                        package x;
                        class Use { void m() { use(Outer.A.B.C.API); } }
                        """));

        assertThat(value.text()).isEqualTo("/deep");
    }

    @Test
    void resolvesFullyQualifiedClassConstant() {
        var value = fold(repo("fully-qualified")
                .java("pkg/sub/Paths.java", """
                        package pkg.sub;
                        public class Paths { public static final String API = "/fqn"; }
                        """)
                .java("x/Use.java", """
                        package x;
                        class Use { void m() { use(pkg.sub.Paths.API); } }
                        """));

        assertThat(value.text()).isEqualTo("/fqn");
    }

    @Test
    void singleTypeImportSelectsBetweenDuplicateSimpleNames() {
        var value = fold(repo("duplicate-simple-name")
                .java("a/Paths.java", """
                        package a;
                        public class Paths { public static final String API = "/a"; }
                        """)
                .java("b/Paths.java", """
                        package b;
                        public class Paths { public static final String API = "/b"; }
                        """)
                .java("x/Use.java", """
                        package x;
                        import b.Paths;
                        class Use { void m() { use(Paths.API); } }
                        """));

        assertThat(value.text()).isEqualTo("/b");
    }

    @Test
    void resolvesInterfaceConstantThroughImplements() {
        var value = fold(repo("interface-constant").java("x/Use.java", """
                package x;
                interface Paths { String API = "/interface"; }
                class Use implements Paths { void m() { use(API); } }
                """));

        assertThat(value.text()).isEqualTo("/interface");
    }

    @Test
    void resolvesConstantInheritedThroughTwoSuperclassLevels() {
        var value = fold(repo("two-level-inheritance").java("x/Use.java", """
                package x;
                class Base { static final String API = "/base"; }
                class Middle extends Base {}
                class Use extends Middle { void m() { use(API); } }
                """));

        assertThat(value.text()).isEqualTo("/base");
    }

    @Test
    void resolvesConstantFromGenericSuperType() {
        var value = fold(repo("generic-super").java("x/Use.java", """
                package x;
                class Base<T> { static final String API = "/generic"; }
                class Use extends Base<String> { void m() { use(API); } }
                """));

        assertThat(value.text()).isEqualTo("/generic");
    }

    @Test
    void resolveTypePrefersSamePackageToOnDemandImport() {
        var index = index(repo("type-precedence")
                .java("a/Paths.java", "package a; public class Paths {}")
                .java("x/Paths.java", "package x; class Paths {}")
                .java("x/Use.java", """
                        package x;
                        import a.*;
                        class Use {}
                        """));
        var context = type(index, "x.Use").orElseThrow();

        var resolved = index.resolveType("Paths", context).orElseThrow();
        assertThat(resolved.getFullyQualifiedName()).contains("x.Paths");
    }

    @Test
    void superTypesReturnsResolvedSuperclassThenInterfaces() {
        var index = index(repo("super-types").java("x/Use.java", """
                package x;
                class Base {}
                interface First {}
                interface Second {}
                class Use extends Base implements First, Second {}
                """));
        var use = type(index, "x.Use").orElseThrow();

        assertThat(index.superTypes(use)).extracting(candidate -> candidate.getNameAsString())
                .containsExactly("Base", "First", "Second");
    }

    @Test
    void enumConstantReferenceIsUnresolved() {
        var value = fold(repo("enum-constant").java("x/Use.java", """
                package x;
                enum Color { RED, BLUE }
                class Use { void m() { use(Color.RED); } }
                """));

        // EXPECTATION?: Enum constants are runtime objects, so their string representation should not be guessed statically.
        assertThat(value.text()).isEqualTo(Value.UNKNOWN);
        assertThat(value.raw()).isEqualTo("Color.RED");
        assertThat(value.confidence()).isEqualTo(Confidence.UNRESOLVED);
    }

    @Test
    void typesMissingFromSourcesRemainUnresolvedWithoutThrowing() {
        var index = index(repo("external-types").java("x/Use.java", """
                package x;
                class Use { Object[] values = {java.lang.String.valueOf(1), Integer.MAX_VALUE}; }
                """));

        assertThatCode(() -> index.evalAll(arrayInitializer(index))).doesNotThrowAnyException();
        assertThat(index.evalAll(arrayInitializer(index)))
                .allSatisfy(v -> assertThat(v.confidence()).isEqualTo(Confidence.UNRESOLVED));
    }

    @Test
    void declaredTypeFindsLocalsParametersFieldsAndRecordComponents() {
        var index = index(repo("declared-types").java("x/Use.java", """
                package x;
                record Use(Client component) {
                    Service field;
                    void m(Request parameter) {
                        Local local = null;
                        use(local, parameter, field, component);
                    }
                }
                """));
        var call = useCall(index);

        assertThat(index.declaredType("local", call)).contains("Local");
        assertThat(index.declaredType("parameter", call)).contains("Request");
        assertThat(index.declaredType("field", call)).contains("Service");
        assertThat(index.declaredType("component", call)).contains("Client");
    }

    @Test
    void declaredTypePreservesPrimitiveAndArrayTypeSpellings() {
        var index = index(repo("declared-primitive-types").java("x/Use.java", """
                package x;
                class Use { void m(int count, String[] names) { use(count, names); } }
                """));
        var call = useCall(index);

        assertThat(index.declaredType("count", call)).contains("int");
        assertThat(index.declaredType("names", call)).contains("String[]");
    }

    @Test
    void stringValueOfPassesThroughFoldedArgument() {
        var value = fold(repo("value-of").java("x/Use.java", """
                package x;
                class Use { static final int PORT = 8080; void m() { use(String.valueOf(PORT)); } }
                """));

        assertThat(value.text()).isEqualTo("8080");
        assertThat(value.confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    void supportedZeroArgumentStringConversionsApplyTheirRuntimeSemantics() {
        var index = index(repo("string-conversions").java("x/Use.java", """
                package x;
                class Use {
                    static final String VALUE = " value ";
                    String[] values = {VALUE.trim(), VALUE.strip(), VALUE.toString()};
                }
                """));

        assertThat(index.evalAll(arrayInitializer(index))).extracting(Value::text)
                .containsExactly("value", "value", " value ");
    }

    @Test
    void foldsZeroArgumentHelperInSameClass() {
        var value = fold(repo("helper").java("x/Use.java", """
                package x;
                class Use {
                    static final String API = "/helper";
                    String path() { return API; }
                    void m() { use(path()); }
                }
                """));

        assertThat(value.text()).isEqualTo("/helper");
        assertThat(value.confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    void helperWithParametersIsUnresolved() {
        var value = fold(repo("helper-parameter").java("x/Use.java", """
                package x;
                class Use { String path(String suffix) { return "/" + suffix; } void m() { use(path("x")); } }
                """));

        assertThat(value.text()).isEqualTo(Value.UNKNOWN);
        assertThat(value.raw()).isEqualTo("path(\"x\")");
    }

    @Test
    void helperWithTwoStatementsIsUnresolved() {
        var value = fold(repo("helper-statements").java("x/Use.java", """
                package x;
                class Use {
                    String path() { log(); return "/x"; }
                    void m() { use(path()); }
                }
                """));

        assertThat(value.text()).isEqualTo(Value.UNKNOWN);
        assertThat(value.raw()).isEqualTo("path()");
    }

    @Test
    void foldsExplicitThisHelperInSameClass() {
        var value = fold(repo("this-helper").java("x/Use.java", """
                package x;
                class Use { String path() { return "/this-helper"; } void m() { use(this.path()); } }
                """));

        assertThat(value.text()).isEqualTo("/this-helper");
    }

    @Test
    void helperInheritedFromSuperclassIsUnresolved() {
        var value = fold(repo("super-helper").java("x/Use.java", """
                package x;
                class Base { String path() { return "/base-helper"; } }
                class Use extends Base { void m() { use(path()); } }
                """));

        // EXPECTATION?: The documented helper shortcut is deliberately limited to helpers in the same class.
        assertThat(value.text()).isEqualTo(Value.UNKNOWN);
        assertThat(value.raw()).isEqualTo("path()");
    }

    @Test
    void discoveryClientGetInstancesBecomesLoadBalancedUri() {
        var value = fold(repo("discovery-client").java("x/Use.java", """
                package x;
                class Use { void m() { use(discoveryClient.getInstances("orders").get(0).getUri()); } }
                """));

        assertThat(value.text()).isEqualTo("lb://orders");
        assertThat(value.confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    void loadBalancerChooseBecomesLoadBalancedUri() {
        var value = fold(repo("load-balancer").java("x/Use.java", """
                package x;
                class Use { void m() { use(loadBalancer.choose("billing").getUri()); } }
                """));

        assertThat(value.text()).isEqualTo("lb://billing");
        assertThat(value.confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    void stringFormatIsUnresolved() {
        var value = fold(repo("string-format").java("x/Use.java", """
                package x;
                class Use { void m() { use(String.format("/%s", "x")); } }
                """));

        assertThat(value.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(value.raw()).isEqualTo("String.format(\"/%s\", \"x\")");
    }

    @Test
    void ternaryExpressionIsUnresolved() {
        var value = fold(repo("ternary").java("x/Use.java", """
                package x;
                class Use { void m(boolean flag) { use(flag ? "/a" : "/b"); } }
                """));

        assertThat(value.text()).isEqualTo(Value.UNKNOWN);
        assertThat(value.raw()).isEqualTo("flag ? \"/a\" : \"/b\"");
    }

    @Test
    void stringBuilderChainIsUnresolved() {
        var value = fold(repo("string-builder").java("x/Use.java", """
                package x;
                class Use { void m() { use(new StringBuilder().append("/x").toString()); } }
                """));

        assertThat(value.text()).isEqualTo(Value.UNKNOWN);
        assertThat(value.raw()).isEqualTo("new StringBuilder().append(\"/x\").toString()");
    }

    @Test
    void methodReferenceIsUnresolved() {
        var value = fold(repo("method-reference").java("x/Use.java", """
                package x;
                class Use { String path() { return "/x"; } void m() { use(this::path); } }
                """));

        assertThat(value.text()).isEqualTo(Value.UNKNOWN);
        assertThat(value.raw()).isEqualTo("this::path");
    }

    @Test
    void mutuallyRecursiveConstantsTerminateWithoutStackOverflow() {
        var value = fold(repo("mutual-cycle").java("x/Use.java", """
                package x;
                class Use {
                    static final String A = B + "x";
                    static final String B = A + "y";
                    void m() { use(A); }
                }
                """));

        assertThat(value.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(value.text()).contains(Value.UNKNOWN);
    }

    @Test
    void selfReferentialConstantTerminatesWithoutStackOverflow() {
        var value = fold(repo("self-cycle").java("x/Use.java", """
                package x;
                class Use { static final String X = X + "a"; void m() { use(X); } }
                """));

        assertThat(value.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(value.text()).contains(Value.UNKNOWN);
    }

    @Test
    void foldsVeryLongLiteralConcatenation() {
        String expression = IntStream.range(0, 200).mapToObj(i -> "\"x\"").collect(Collectors.joining(" + "));
        var value = fold(repo("long-concat").java("x/Use.java", """
                package x;
                class Use { void m() { use(%s); } }
                """.formatted(expression)));

        assertThat(value.text()).isEqualTo("x".repeat(200));
        assertThat(value.confidence()).isEqualTo(Confidence.EXACT);
    }

    @Test
    void parsesFiveThousandLineSourceFile() {
        String padding = "// old generated line\n".repeat(4_998);
        var index = index(repo("large-file").java("x/Large.java", """
                package x;
                %s
                class Large {}
                """.formatted(padding)));

        assertThat(index.problems()).isEmpty();
        assertThat(type(index, "x.Large")).isPresent();
    }

    @Test
    void unknownNamesNeverThrow() {
        var index = index(repo("unknown-name").java("x/Use.java", """
                package x;
                class Use { void m() { use(missing.deep.value + unknown); } }
                """));

        assertThatCode(() -> index.eval(useCall(index).getArgument(0))).doesNotThrowAnyException();
        assertThat(index.eval(useCall(index).getArgument(0)).confidence()).isEqualTo(Confidence.UNRESOLVED);
    }

    @Test
    void parsesJava21RecordsSealedTypesGuardedSwitchesAndRecordPatterns() {
        var index = index(repo("java-21").java("x/Modern.java", """
                package x;
                sealed interface Shape permits Circle, Box {}
                record Circle(double radius) implements Shape {}
                record Box(int width, int height) implements Shape {}
                class Modern {
                    int area(Shape shape) {
                        return switch (shape) {
                            case Circle(double radius) when radius > 0 -> (int) radius;
                            case Box(int width, int height) -> width * height;
                            default -> 0;
                        };
                    }
                }
                """));

        assertThat(index.problems()).isEmpty();
        assertThat(index.units()).hasSize(1);
    }

    @Test
    void parsesBleedingEdgeUnnamedLambdaAndCatchVariables() {
        var index = index(repo("unnamed-variables").java("x/Unnamed.java", """
                package x;
                class Unnamed {
                    void m() {
                        values.forEach(_ -> consume());
                        try { work(); } catch (RuntimeException _) { recover(); }
                    }
                }
                """));

        assertThat(index.problems()).isEmpty();
        assertThat(type(index, "x.Unnamed")).isPresent();
    }

    @Test
    void syntaxErrorReportsItsLineAndOtherFilesRemainIndexed() {
        var index = index(repo("partial-parse")
                .java("bad/Broken.java", """
                        package bad;
                        class Broken {
                            void broken( {
                        }
                        """)
                .java("good/Good.java", """
                        package good;
                        class Good {}
                        """));

        assertThat(index.problems()).anySatisfy(problem -> {
            assertThat(problem.file()).hasFileName("Broken.java");
            assertThat(problem.line()).isGreaterThan(1);
            assertThat(problem.message()).isNotBlank();
        });
        assertThat(type(index, "good.Good")).isPresent();
    }

    @Test
    void completelyUnparseableFileDoesNotStopIndexBuild() {
        var index = index(repo("failed-parse")
                .java("bad/Noise.java", """
                        %%% this is not Java %%%
                        """)
                .java("good/Good.java", """
                        package good;
                        class Good {}
                        """));

        assertThat(index.problems()).isNotEmpty();
        assertThat(type(index, "good.Good")).isPresent();
    }

    @Test
    void acceptsUtf8BomAtStartOfFile() {
        byte[] source = "\uFEFFpackage x; class Bom {}".getBytes(StandardCharsets.UTF_8);
        var index = index(repo("bom").bytes("src/main/java/x/Bom.java", source));

        assertThat(index.problems()).isEmpty();
        assertThat(type(index, "x.Bom")).isPresent();
    }

    @Test
    void crlfLineEndingsPreserveEvidenceLineNumbers() {
        var index = index(repo("crlf").bytes("src/main/java/x/Use.java", String.join("\r\n",
                "package x;",
                "class Use {",
                "    void m() {",
                "        use(\"x\");",
                "    }",
                "}").getBytes(StandardCharsets.UTF_8)));

        assertThat(index.evidence(useCall(index)).line()).isEqualTo(4);
    }

    @Test
    void tabsDoNotDisturbLineBasedEvidence() {
        var index = index(repo("tabs").java("x/Use.java", """
                package x;
                class Use {
                \tvoid m() {
                \t\tuse("x");
                \t}
                }
                """));

        assertThat(index.evidence(useCall(index)).line()).isEqualTo(4);
    }

    @Test
    void indexesFileContainingOnlyPackageDeclaration() {
        var index = index(repo("package-only").java("x/Empty.java", """
                package x;
                """));

        assertThat(index.problems()).isEmpty();
        assertThat(index.units()).singleElement().satisfies(unit ->
                assertThat(unit.getPackageDeclaration()).get()
                        .satisfies(p -> assertThat(p.getNameAsString()).isEqualTo("x")));
    }

    @Test
    void indexesModuleInfo() {
        var index = index(repo("module-info").java("module-info.java", """
                module example.module { }
                """));

        assertThat(index.problems()).isEmpty();
        assertThat(index.units()).singleElement().satisfies(unit -> assertThat(unit.getModule()).isPresent());
    }

    @Test
    void indexesAnnotatedPackageInfo() {
        var index = index(repo("package-info").java("x/package-info.java", """
                @Deprecated
                package x;
                """));

        assertThat(index.problems()).isEmpty();
        assertThat(index.units()).singleElement().satisfies(unit ->
                assertThat(unit.findAll(AnnotationExpr.class)).singleElement()
                        .extracting(a -> a.getName().getIdentifier()).isEqualTo("Deprecated"));
    }

    @Test
    void indexesNonAsciiIdentifiers() {
        var value = fold(repo("unicode-identifiers").java("x/Use.java", """
                package x;
                class Use { static final String เส้นทาง = "/ไทย"; void m() { use(เส้นทาง); } }
                """));

        assertThat(value.text()).isEqualTo("/ไทย");
        assertThat(value.confidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    void includesMainAndGeneratedSourcesWhileExcludingBuildOutputsTestsAndIgnoredTrees() {
        var index = index(repo("source-selection")
                .file("src/main/java/x/Main.java", "package x; class Main {}")
                .file("src/test/java/x/TestOnly.java", "package x; class TestOnly {}")
                .file("build/generated-sources/x/BuildGenerated.java", "package x; class BuildGenerated {}")
                .file("target/generated-sources/x/TargetGenerated.java", "package x; class TargetGenerated {}")
                .file("build/classes/x/BuildOutput.java", "package x; class BuildOutput {}")
                .file("target/other/x/TargetOther.java", "package x; class TargetOther {}")
                .file(".git/x/GitJava.java", "package x; class GitJava {}")
                .file("node_modules/x/NodeJava.java", "package x; class NodeJava {}"));

        assertThat(index.units()).flatExtracting(unit -> unit.getTypes().stream()
                        .map(TypeDeclaration::getNameAsString).toList())
                .containsExactlyInAnyOrder("Main", "BuildGenerated", "TargetGenerated");
        assertThat(index.units().stream().filter(index::isGenerated)).hasSize(2);
    }

    @Test
    void repositoryUnderSrcTestPathStillIndexesItsMainSources() {
        var nested = TempRepo.at(tmp.resolve("src/test/repo")).java("x/Main.java", """
                package x;
                class Main {}
                """);

        assertThat(type(index(nested), "x.Main")).isPresent();
    }

    @Test
    void repositoryUnderBuildPathStillIndexesItsMainSources() {
        var nested = TempRepo.at(tmp.resolve("build/repo")).java("x/Main.java", """
                package x;
                class Main {}
                """);

        assertThat(type(index(nested), "x.Main")).isPresent();
    }

    @Test
    void moduleOfRootMainSourceIsRepositoryRoot() {
        var tempRepo = repo("root-module").java("x/Main.java", "package x; class Main {}");
        Path source = tempRepo.root().resolve("src/main/java/x/Main.java");

        assertThat(tempRepo.index().moduleOf(source)).isEqualTo(tempRepo.root().toAbsolutePath().normalize());
    }

    @Test
    void moduleOfDeeplyNestedMainSourceIsNestedModuleDirectory() {
        var tempRepo = repo("nested-module").file("a/b/c/src/main/java/x/Main.java", "package x; class Main {}");
        Path source = tempRepo.root().resolve("a/b/c/src/main/java/x/Main.java");

        assertThat(tempRepo.index().moduleOf(source))
                .isEqualTo(tempRepo.root().resolve("a/b/c").toAbsolutePath().normalize());
    }

    @Test
    void moduleOfTargetGeneratedSourceIsOwningModuleDirectory() {
        var tempRepo = repo("generated-module").file("module/target/generated-sources/x/Api.java", "package x; class Api {}");
        Path source = tempRepo.root().resolve("module/target/generated-sources/x/Api.java");

        assertThat(tempRepo.index().moduleOf(source))
                .isEqualTo(tempRepo.root().resolve("module").toAbsolutePath().normalize());
    }

    @Test
    void moduleOfUnrecognizedSourcePathFallsBackToParentDirectory() {
        var tempRepo = repo("loose-source").file("misc/Loose.java", "class Loose {}");
        Path source = tempRepo.root().resolve("misc/Loose.java");

        assertThat(tempRepo.index().moduleOf(source))
                .isEqualTo(tempRepo.root().resolve("misc").toAbsolutePath().normalize());
    }

    @Test
    void evidenceUsesAnnotationAndCallStartLinesAndForwardSlashPaths() {
        var index = index(repo("evidence").java("deep/path/Use.java", """
                package deep.path;

                @Route(
                    "/x")
                class Use {
                    void m() {
                        use(
                            "/x");
                    }
                }
                """));
        CompilationUnit unit = index.units().getFirst();
        var annotation = unit.findFirst(AnnotationExpr.class).orElseThrow();

        assertThat(index.evidence(annotation).line()).isEqualTo(3);
        assertThat(index.evidence(useCall(index)).line()).isEqualTo(7);
        assertThat(index.evidence(useCall(index)).file()).isEqualTo("src/main/java/deep/path/Use.java");
    }

    @Test
    void foldedConstantRecordsItsDefinitionEvidence() {
        var value = fold(repo("constant-evidence").java("x/Use.java", """
                package x;

                class Use {
                    static final String PATH = "/x";
                    void m() { use(PATH); }
                }
                """));

        assertThat(value.via()).singleElement().satisfies(evidence -> {
            assertThat(evidence.file()).isEqualTo("src/main/java/x/Use.java");
            assertThat(evidence.line()).isEqualTo(4);
        });
    }

    private TempRepo repo(String name) {
        return TempRepo.at(tmp.resolve(name));
    }

    private static JavaIndex index(TempRepo repo) {
        return repo.index();
    }

    private static Value fold(TempRepo repo) {
        var index = index(repo);
        return index.eval(useCall(index).getArgument(0));
    }

    private static MethodCallExpr useCall(JavaIndex index) {
        return index.units().stream().flatMap(unit -> unit.findAll(MethodCallExpr.class).stream())
                .filter(call -> call.getNameAsString().equals("use"))
                .findFirst().orElseThrow();
    }

    private static ArrayInitializerExpr arrayInitializer(JavaIndex index) {
        return index.units().stream().flatMap(unit -> unit.findAll(ArrayInitializerExpr.class).stream())
                .findFirst().orElseThrow();
    }

    private static java.util.Optional<TypeDeclaration<?>> type(JavaIndex index, String fqn) {
        return index.units().stream().flatMap(unit -> unit.findAll(TypeDeclaration.class).stream())
                .<TypeDeclaration<?>>map(candidate -> (TypeDeclaration<?>) candidate)
                .filter(candidate -> candidate.getFullyQualifiedName().orElse("").equals(fqn))
                .findFirst();
    }

    private static void assertUnresolvedName(Value value, String raw) {
        assertThat(value.text()).isEqualTo(Value.UNKNOWN);
        assertThat(value.raw()).isEqualTo(raw);
        assertThat(value.confidence()).isEqualTo(Confidence.UNRESOLVED);
    }
}
