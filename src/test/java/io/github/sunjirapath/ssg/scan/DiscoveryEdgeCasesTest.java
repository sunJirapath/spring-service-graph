package io.github.sunjirapath.ssg.scan;

import com.github.javaparser.ast.body.VariableDeclarator;
import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Edge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryEdgeCasesTest {

    @TempDir
    Path tmp;

    @Test
    void singleModuleApplicationProducesOneNamedService() {
        var repo = repo("single").app().resource("application.yml", "spring.application.name: orders\n");

        assertThat(repo.services()).singleElement().satisfies(service -> {
            assertThat(service.name()).isEqualTo("orders");
            assertThat(service.moduleRoot()).isEqualTo(repo.root());
        });
    }

    @Test
    void multiModuleRepositoryProducesOneServicePerApplicationModule() {
        var repo = repo("multi")
                .file("orders/src/main/java/orders/App.java", "package orders; @SpringBootApplication class App {}")
                .file("orders/src/main/resources/application.properties", "spring.application.name=orders\n")
                .file("billing/src/main/java/billing/App.java", "package billing; @SpringBootApplication class App {}")
                .file("billing/src/main/resources/application.properties", "spring.application.name=billing\n");

        assertThat(repo.services()).extracting(Service::name).containsExactly("billing", "orders");
    }

    @Test
    void unnamedApplicationsWithTheSameFolderNameAreToldApartByParentFolder() {
        var repo = repo("same-folder")
                .file("integration/http/src/main/java/a/App.java", "package a; @SpringBootApplication class App {}")
                .file("transport/http/src/main/java/b/App.java", "package b; @SpringBootApplication class App {}")
                .file("billing/src/main/java/c/App.java", "package c; @SpringBootApplication class App {}");

        assertThat(repo.services()).extracting(Service::name)
                .containsExactlyInAnyOrder("integration-http", "transport-http", "billing");
    }

    @Test
    void twoApplicationClassesInSameModuleStillProduceOneService() {
        var repo = repo("two-apps-one-module")
                .java("one/First.java", "package one; @SpringBootApplication class First {}")
                .java("two/Second.java", "package two; @SpringBootApplication class Second {}")
                .resource("application.properties", "spring.application.name=one-service\n");

        assertThat(repo.services()).singleElement().satisfies(service -> assertThat(service.name()).isEqualTo("one-service"));
    }

    @Test
    void fullyQualifiedSpringBootApplicationAnnotationIsRecognized() {
        var repo = repo("fully-qualified").java("app/App.java", """
                package app;
                @org.springframework.boot.autoconfigure.SpringBootApplication
                class App {}
                """);

        assertThat(repo.services()).singleElement().satisfies(service -> assertThat(service.name()).isEqualTo("fully-qualified"));
    }

    @Test
    void applicationClassOnlyUnderTestSourcesIsIgnored() {
        var repo = repo("test-only").file("src/test/java/app/App.java",
                "package app; @SpringBootApplication class App {}");

        assertThat(repo.services()).isEmpty();
    }

    @Test
    void applicationClassesUnderGeneratedBuildAndTargetDirectoriesAreIgnored() {
        var repo = repo("generated-only")
                .file("build/generated/sources/app/App.java",
                        "package app; @SpringBootApplication class App {}")
                .file("target/generated-sources/app/OtherApp.java",
                        "package app; @SpringBootApplication class OtherApp {}");

        assertThat(repo.services()).isEmpty();
    }

    @Test
    void missingApplicationNameFallsBackToModuleDirectoryName() {
        var repo = repo("fallback-root").file("inventory/src/main/java/app/App.java",
                "package app; @SpringBootApplication class App {}");

        assertThat(repo.services()).singleElement().satisfies(service -> assertThat(service.name()).isEqualTo("inventory"));
    }

    @Test
    void bootstrapYamlProvidesLegacyApplicationName() {
        var repo = repo("bootstrap-name").app().resource("bootstrap.yml", """
                spring:
                  application:
                    name: legacy-service
                """);

        assertThat(repo.services()).singleElement().satisfies(service -> assertThat(service.name()).isEqualTo("legacy-service"));
    }

    @Test
    void applicationNameMayResolveFromPlaceholderDefault() {
        var repo = repo("placeholder-name").app()
                .resource("application.properties", "spring.application.name=${APP_NAME:orders}\n");

        assertThat(repo.services()).singleElement().satisfies(service -> assertThat(service.name()).isEqualTo("orders"));
    }

    @Test
    void profileSpecificApplicationNameIsUsedOnlyForSelectedProfile() {
        var repo = repo("profile-name").app()
                .resource("application-prod.yml", "spring.application.name: production-orders\n");

        assertThat(repo.services("prod", null)).singleElement()
                .satisfies(service -> assertThat(service.name()).isEqualTo("production-orders"));
        assertThat(repo.services("default", null)).singleElement()
                .satisfies(service -> assertThat(service.name()).isEqualTo("profile-name"));
    }

    @Test
    void localConfigPrecedenceIsBootstrapThenApplicationThenProfile() {
        var repo = repo("local-precedence").app()
                .resource("bootstrap.properties", "layer=bootstrap\n")
                .resource("bootstrap-prod.properties", "layer=bootstrap-profile\n")
                .resource("application.properties", "layer=application\n")
                .resource("application-prod.properties", "layer=application-profile\n");

        var service = repo.services("prod", null).getFirst();

        assertThat(service.config().get("layer").value()).isEqualTo("application-profile");
        assertThat(service.config().get("layer").evidence().file()).endsWith("application-prod.properties");
    }

    @Test
    void configRepoOverridesLocalInApplicationThenProfileThenAppThenAppProfileOrder() {
        var repo = repo("remote-precedence").app().resource("application.properties", """
                spring.application.name=orders
                layer=local
                """);
        var configRepo = TempRepo.at(tmp.resolve("config-repo"))
                .file("application.properties", "layer=remote-application\n")
                .file("application-prod.properties", "layer=remote-application-profile\n")
                .file("orders.properties", "layer=remote-app\n")
                .file("orders-prod.properties", "layer=remote-app-profile\n");

        var service = repo.services("prod", configRepo.root()).getFirst();

        assertThat(service.config().get("layer").value()).isEqualTo("remote-app-profile");
        assertThat(service.config().get("layer").evidence().file()).isEqualTo("orders-prod.properties");
    }

    @Test
    void configRepoFilesForAnotherApplicationAreIgnored() {
        var repo = repo("remote-other-app").app().resource("application.properties", """
                spring.application.name=orders
                owner=local-orders
                """);
        var configRepo = TempRepo.at(tmp.resolve("other-config-repo"))
                .file("billing.properties", "owner=billing\n")
                .file("billing-prod.properties", "owner=billing-prod\n");

        var service = repo.services("prod", configRepo.root()).getFirst();

        assertThat(service.config().get("owner").value()).isEqualTo("local-orders");
    }

    @Test
    void libraryModuleSourcesGoOnlyToServicesThatUseThemAndTheirConstantsResolve() {
        var repo = repo("library-attribution")
                .file("app/src/main/java/app/App.java", """
                        package app;
                        import common.Names;
                        @SpringBootApplication class App {}
                        class Use { static final String PATH = Names.PATH; }
                        """)
                .file("app/src/main/resources/application.properties", "spring.application.name=app\n")
                .file("library/src/main/java/common/Names.java", """
                        package common;
                        public class Names { public static final String PATH = "/from-library"; Helper h; }
                        """)
                .file("library/src/main/java/common/Helper.java", "package common; public class Helper {}")
                .file("library/src/main/java/common/Unused.java", "package common; public class Unused {}")
                .file("other/src/main/java/other/App.java", "package other; @SpringBootApplication class App {}")
                .file("other/src/main/resources/application.properties", "spring.application.name=other\n");

        var service = repo.service("app");
        var path = service.units().stream().flatMap(cu -> cu.findAll(VariableDeclarator.class).stream())
                .filter(v -> v.getNameAsString().equals("PATH")).findFirst().orElseThrow();

        // Names directly, Helper through Names; nobody uses Unused
        assertThat(service.units()).extracting(cu -> service.index().fileOf(cu).getFileName().toString())
                .containsExactlyInAnyOrder("App.java", "Names.java", "Helper.java");
        assertThat(repo.service("other").units()).extracting(cu -> service.index().fileOf(cu).getFileName().toString())
                .containsExactly("App.java");
        assertThat(service.eval(path.getInitializer().orElseThrow())).satisfies(value -> {
            assertThat(value.text()).isEqualTo("/from-library");
            assertThat(value.confidence()).isEqualTo(Confidence.RESOLVED);
            assertThat(value.via()).anyMatch(e -> e.file().endsWith("library/src/main/java/common/Names.java"));
        });
    }

    @Test
    void feignClientInSharedApiModuleCallsFromItsUsersNotFromItsImplementer() {
        var repo = repo("shared-api")
                .file("users-api/src/main/java/api/UserApi.java", """
                        package api;
                        @FeignClient(name = "users")
                        public interface UserApi { @GetMapping("/rpc/users/{id}") String get(@PathVariable long id); }
                        """)
                .file("users/src/main/java/users/App.java", "package users; @SpringBootApplication class App {}")
                .file("users/src/main/java/users/UserApiImpl.java", """
                        package users;
                        import api.UserApi;
                        @RestController class UserApiImpl implements UserApi { public String get(long id) { return ""; } }
                        class Self { UserApi local; } // the local bean, not a remote call
                        """)
                .file("users/src/main/resources/application.properties", "spring.application.name=users\n")
                .file("orders/src/main/java/orders/App.java", """
                        package orders;
                        import api.UserApi;
                        @SpringBootApplication @EnableFeignClients(clients = UserApi.class) class App {}
                        """)
                .file("orders/src/main/resources/application.properties", "spring.application.name=orders\n")
                .file("billing/src/main/java/billing/App.java", "package billing; @SpringBootApplication class App {}")
                .file("billing/src/main/resources/application.properties", "spring.application.name=billing\n");

        var calls = TempRepo.scan(repo).edges().stream().filter(e -> e.kind() == Edge.Kind.CALLS).toList();

        assertThat(calls).singleElement().satisfies(e -> {
            assertThat(e.from()).isEqualTo("service:orders");
            assertThat(e.to()).isEqualTo("endpoint:users:GET /rpc/users/{id}");
            assertThat(e.evidence().getFirst().file()).isEqualTo("users-api/src/main/java/api/UserApi.java");
            assertThat(e.evidence().getFirst().line()).isEqualTo(3);
        });
    }

    @Test
    void singleApplicationRepositoryAttributesSourcesFromAllModules() {
        var repo = repo("single-app-multi-module")
                .file("service/src/main/java/app/App.java", "package app; @SpringBootApplication class App {}")
                .file("library/src/main/java/common/Helper.java", "package common; class Helper {}")
                .file("unrelated/src/main/java/old/Legacy.java", "package old; class Legacy {}");

        var service = repo.services().getFirst();

        assertThat(service.units()).extracting(cu -> service.index().fileOf(cu).getFileName().toString())
                .containsExactlyInAnyOrder("App.java", "Helper.java", "Legacy.java");
    }

    @Test
    void rootModuleServicePathIsDot() {
        var service = repo("root-path").app().service();

        assertThat(service.path()).isEqualTo(".");
    }

    @Test
    void repoRelativeAlwaysUsesForwardSlashes() {
        var root = tmp.resolve("relative");
        var repo = Repo.of(root);

        assertThat(repo.relative(root.resolve("a/b/c.txt"))).isEqualTo("a/b/c.txt");
    }

    @Test
    void nestedGitAndNodeModulesRepositoriesAreIgnored() {
        var repo = repo("ignored-nested")
                .app()
                .file("node_modules/dependency/src/main/java/dep/App.java",
                        "package dep; @SpringBootApplication class App {}")
                .file("vendor/.git/src/main/java/history/App.java",
                        "package history; @SpringBootApplication class App {}");

        assertThat(repo.services()).singleElement().satisfies(service -> assertThat(service.name()).isEqualTo("ignored-nested"));
    }

    private TempRepo repo(String name) {
        return TempRepo.at(tmp.resolve(name));
    }
}
