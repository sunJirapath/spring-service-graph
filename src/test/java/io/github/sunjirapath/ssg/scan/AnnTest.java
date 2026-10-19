package io.github.sunjirapath.ssg.scan;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnnTest {

    @Test
    void findMatchesSimpleAnnotationName() {
        var type = annotatedType("@Value class C {}");

        assertThat(Ann.find(type, "Value")).isPresent();
    }

    @Test
    void findMatchesFullyQualifiedAnnotationBySimpleName() {
        var type = annotatedType("@org.springframework.beans.factory.annotation.Value class C {}");

        assertThat(Ann.find(type, "Value")).isPresent();
    }

    @Test
    void attrTreatsSingleMemberAsValue() {
        var annotation = annotation("@Route(\"/orders\")");

        assertThat(Ann.attr(annotation, "value")).get().hasToString("\"/orders\"");
    }

    @Test
    void attrUsesRequestedNamePrecedenceForNormalAnnotation() {
        var annotation = annotation("@Route(path = \"/path\", value = \"/value\")");

        assertThat(Ann.attr(annotation, "value", "path")).get().hasToString("\"/value\"");
        assertThat(Ann.attr(annotation, "missing", "path")).get().hasToString("\"/path\"");
    }

    @Test
    void attrReturnsEmptyForMarkerAnnotation() {
        assertThat(Ann.attr(annotation("@Deprecated"), "value")).isEmpty();
    }

    @Test
    void attrReturnsEmptyWhenNormalAnnotationHasNoneOfTheRequestedNames() {
        assertThat(Ann.attr(annotation("@Route(path = \"/x\")"), "value", "name")).isEmpty();
    }

    @Test
    void hasMatchesAnyOfSeveralNames() {
        var type = annotatedType("@RestController class C {}");

        assertThat(Ann.has(type, "Controller", "RestController", "Service")).isTrue();
        assertThat(Ann.has(type, "Controller", "Service")).isFalse();
    }

    private static ClassOrInterfaceDeclaration annotatedType(String source) {
        return StaticJavaParser.parse(source).findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
    }

    private static AnnotationExpr annotation(String source) {
        return annotatedType(source + " class C {}").getAnnotations().get(0);
    }
}
