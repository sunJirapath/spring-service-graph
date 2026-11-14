package io.github.sunjirapath.ssg.scan;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IndexModulesDiffBranchGapTest {

    @TempDir
    Path tmp;

    @Test
    void duplicateTypesResolveAndPreferTheCopyInTheRequestedModule() {
        var repo = TempRepo.at(tmp.resolve("same-fqn"))
                .file("one/src/main/java/shared/Common.java", "package shared; class Common { String origin = \"one\"; }")
                .file("two/src/main/java/shared/Common.java", "package shared; class Common { String origin = \"two\"; }")
                .file("one/src/main/java/app/Use.java", "package app; import shared.Common; class Use { Common value; }")
                .file("two/src/main/java/app/Use.java", "package app; import shared.Common; class Use { Common value; }");
        var index = repo.index();
        var uses = index.units().stream().filter(cu -> index.fileOf(cu).getFileName().toString().equals("Use.java")).toList();
        var common = index.units().stream().filter(cu -> index.fileOf(cu).getFileName().toString().equals("Common.java"))
                .map(cu -> cu.getType(0)).toList();
        var oneModule = repo.root().resolve("one").toAbsolutePath().normalize();
        var twoModule = repo.root().resolve("two").toAbsolutePath().normalize();
        var oneCommon = common.stream().filter(t -> index.moduleOf(index.fileOf(t.findCompilationUnit().orElseThrow())).equals(oneModule))
                .findFirst().orElseThrow();
        var twoCommon = common.stream().filter(t -> index.moduleOf(index.fileOf(t.findCompilationUnit().orElseThrow())).equals(twoModule))
                .findFirst().orElseThrow();
        var oneUse = uses.stream().filter(cu -> index.moduleOf(index.fileOf(cu)).equals(oneModule)).findFirst().orElseThrow();
        var twoUse = uses.stream().filter(cu -> index.moduleOf(index.fileOf(cu)).equals(twoModule)).findFirst().orElseThrow();

        assertThat(index.resolveType("Common", oneUse.getType(0))).containsSame(oneCommon);
        assertThat(index.resolveType("Common", twoUse.getType(0))).containsSame(twoCommon);
        assertThat(index.preferring(oneCommon, Set.of(twoModule))).isSameAs(twoCommon);
        assertThat(index.preferring(twoCommon, Set.of(twoModule))).isSameAs(twoCommon);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "999999999999999999999L", "(int) 2147483648"})
    void outOfRangeJavaIntegerLiteralsStayUnresolved(String expression) {
        var repo = TempRepo.at(tmp.resolve("overflow-" + expression.hashCode()))
                .java("Use.java", "class Use { void m() { use(" + expression + "); } }");
        var index = repo.index();
        var value = index.eval(useCall(index).getArgument(0));

        assertThat(value.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(value.text()).isEqualTo(Value.UNKNOWN);
    }

    @Test
    void expressionWithoutCompilationUnitStaysUnresolved() {
        var value = TempRepo.at(tmp.resolve("detached-expression")).index()
                .eval(StaticJavaParser.parseExpression("unbound"));

        assertThat(value.text()).isEqualTo(Value.UNKNOWN);
        assertThat(value.raw()).isEqualTo("unbound");
        assertThat(value.confidence()).isEqualTo(Confidence.UNRESOLVED);
    }

    @Test
    void modulesDistinguishNoMetadataFromSettingsOnlyMetadata() {
        var noMetadata = Modules.read(Repo.of(TempRepo.at(tmp.resolve("no-metadata")).root()));
        var settingsOnlyRepo = TempRepo.at(tmp.resolve("settings-only"))
                .file("settings.gradle", "include ':app'");
        var settingsOnly = Modules.read(Repo.of(settingsOnlyRepo.root()));

        assertThat(noMetadata.hasBuildMetadata()).isFalse();
        assertThat(settingsOnly.hasBuildMetadata()).isTrue();
        assertThat(settingsOnly.direct(settingsOnlyRepo.root())).isEmpty();
    }

    // Pins Modules.java:92-94: an unreadable first POM must not prevent discovering the later app-to-lib dependency.
    @Test
    void malformedPomIsSkippedWithoutLosingReadableModuleDependencies() {
        var repo = TempRepo.at(tmp.resolve("malformed-pom"))
                .file("app/pom.xml", pom("app", "lib"))
                .bytes("a-broken/pom.xml", new byte[] {(byte) 0xc3, 0x28})
                .file("lib/pom.xml", pom("lib"));
        Path root = repo.root().toAbsolutePath().normalize();

        assertThat(Modules.read(Repo.of(root)).direct(root.resolve("app"))).containsExactly(root.resolve("lib"));
    }

    @Test
    void mavenDependencyWithAnUnrelatedGroupDoesNotMatchLocalArtifact() {
        var repo = TempRepo.at(tmp.resolve("foreign-group"))
                .file("app/pom.xml", "<project><artifactId>app</artifactId><dependencies>"
                        + "<dependency><groupId>external</groupId><artifactId>shared</artifactId></dependency>"
                        + "</dependencies></project>")
                .file("shared/pom.xml", "<project><groupId>local</groupId><artifactId>shared</artifactId></project>");
        Path root = repo.root().toAbsolutePath().normalize();

        assertThat(Modules.read(Repo.of(root)).direct(root.resolve("app"))).isEmpty();
    }

    @Test
    void unreadableAppliedGradleScriptIsSkippedWhileInlineDependenciesRemain() {
        var repo = TempRepo.at(tmp.resolve("bad-applied-script"))
                .file("settings.gradle", "include ':app', ':lib'")
                .file("app/build.gradle", "apply from: 'broken.gradle'\ndependencies { implementation project(':lib') }")
                .bytes("app/broken.gradle", new byte[] {(byte) 0xc3, 0x28})
                .file("lib/build.gradle", "");
        Path root = repo.root().toAbsolutePath().normalize();

        assertThat(Modules.read(Repo.of(root)).direct(root.resolve("app"))).containsExactly(root.resolve("lib"));
    }

    @Test
    void dependencyTraversalStopsBeforeAnOtherApplicationAndItsDependencies() {
        var repo = TempRepo.at(tmp.resolve("stop-module"))
                .file("app/pom.xml", pom("app", "middle"))
                .file("middle/pom.xml", pom("middle", "leaf"))
                .file("leaf/pom.xml", pom("leaf"));
        Path root = repo.root().toAbsolutePath().normalize();

        assertThat(Modules.read(Repo.of(root)).all(root.resolve("app"), Set.of(root.resolve("middle")))).isEmpty();
    }

    private static MethodCallExpr useCall(JavaIndex index) {
        return index.units().stream().flatMap(unit -> unit.findAll(MethodCallExpr.class).stream())
                .filter(call -> call.getNameAsString().equals("use")).findFirst().orElseThrow();
    }

    private static String pom(String artifact, String... dependencies) {
        return "<project><artifactId>" + artifact + "</artifactId><dependencies>"
                + String.join("", java.util.Arrays.stream(dependencies)
                .map(dep -> "<dependency><artifactId>" + dep + "</artifactId></dependency>").toList())
                + "</dependencies></project>";
    }
}
