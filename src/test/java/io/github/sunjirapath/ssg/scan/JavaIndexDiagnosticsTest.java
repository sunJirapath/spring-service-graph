package io.github.sunjirapath.ssg.scan;

import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import io.github.sunjirapath.ssg.TempRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JavaIndexDiagnosticsTest {
    @TempDir Path tmp;

    @Test
    void legalRecordTypeAnnotationsDoNotProduceAccessorErrorsOrChangeTheAst() throws Exception {
        var repo = TempRepo.at(tmp.resolve("records")).java("dto/Payload.java", """
                package dto;
                import java.util.List;
                import java.util.Map;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE_USE) @interface Valid {}
                record Payload(List<@Valid String> values) {
                    public List<String> values() { return List.copyOf(values); }
                }
                record AnnotatedReturn(List<String> values) {
                    public List<@Valid String> values() { return values; }
                }
                record Nested(Map<String, List<@Valid String>> values) {
                    public Map<String, List<String>> values() { return values; }
                }
                """);
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                repo.root().resolve("src/main/java/dto/Payload.java").toString())).isZero();
        var index = repo.index();
        assertThat(index.problems()).isEmpty();
        var records = index.units().getFirst().findAll(RecordDeclaration.class);
        assertThat(records).hasSize(3);
        assertThat(records.getFirst().getParameters().get(0).getType().findAll(AnnotationExpr.class)).hasSize(1);
        assertThat(records.get(1).getMethodsByName("values").getFirst().getType().findAll(AnnotationExpr.class)).hasSize(1);
    }

    @Test
    void realRecordTypeAndDeclarationErrorsRemainVisible() {
        var repo = TempRepo.at(tmp.resolve("bad-records")).java("dto/Bad.java", """
                package dto;
                import java.util.List;
                record Bad(List<@Valid String> good, List<@Valid String> bad) {
                    public List<String> good() { return good; }
                    public List<Integer> bad() { return null; }
                }
                abstract record AbstractRecord(String value) {}
                record FieldRecord(String value) { int illegal; }
                """).java("dto/Broken.java", "package dto; class Broken { void broken( }");
        var problems = repo.index().problems();
        assertThat(problems).anySatisfy(p -> assertThat(p.message())
                .contains("Incorrect component accessor return type").contains("List<Integer>"));
        assertThat(problems).anySatisfy(p -> assertThat(p.message()).contains("must not be declared as abstract"));
        assertThat(problems).anySatisfy(p -> assertThat(p.message()).contains("zero non-static fields"));
        assertThat(problems).anySatisfy(p -> assertThat(p.file().getFileName().toString()).isEqualTo("Broken.java"));
        assertThat(problems).noneMatch(p -> p.message().contains("Expected: 'List<String>', found: 'List<String>'"));
    }

    @Test
    void lexicalErrorsRetainTheirActualSourceLineAlongsideParseErrors() {
        var repo = TempRepo.at(tmp.resolve("diagnostics"))
                .java("dto/Lexical.java", """
                        package dto;
                        class Lexical {
                            String value = "valid";

                            String split(String input) { return input.split("\\:"); }
                        }
                        """)
                .java("dto/Syntax.java", """
                        package dto;
                        class Syntax {
                            void broken( }
                        }
                        """);

        var problems = repo.index().problems();
        assertThat(problems).anySatisfy(problem -> {
            assertThat(problem.file().getFileName().toString()).isEqualTo("Lexical.java");
            assertThat(problem.line()).isEqualTo(5);
            assertThat(problem.message()).contains("Lexical error");
        });
        assertThat(problems).anySatisfy(problem -> {
            assertThat(problem.file().getFileName().toString()).isEqualTo("Syntax.java");
            assertThat(problem.message()).doesNotContain("Lexical error");
        });
    }

    @Test
    void resourceTemplatesAreNotIndexedAsProductionJava() {
        var repo = TempRepo.at(tmp.resolve("templates")).app()
                .file("archetypes/src/main/resources/archetype-resources/src/main/java/__package__/Init.java",
                        "#set( $symbol_pound = '#' )\npackage ${package}; class ${name} {}")
                .file("src/main/resources/archetype-resources/src/main/java/template/App.java",
                        "package template; @SpringBootApplication class App {}")
                .file("src/main/resources/src/main/java/NotSource.java", "class ${name} {}")
                .java("app/src/main/resources/Real.java", "package app.src.main.resources; class Real {}");
        var index = repo.index();
        assertThat(index.problems()).isEmpty();
        assertThat(index.units()).hasSize(2).noneSatisfy(cu ->
                assertThat(index.fileOf(cu).toString()).contains("archetype-resources"));
        assertThat(Discovery.discover(index, "default", null)).hasSize(1);
    }
}
