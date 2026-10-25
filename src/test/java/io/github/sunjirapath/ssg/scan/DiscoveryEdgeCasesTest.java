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
    void nestedSourceComposedSpringBootApplicationAnnotationsAreRecognized() {
        var repo = repo("composed-app").file("src/main/java/app/BootAnnotations.java", """
                package app;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) @SpringBootApplication @interface RootApplication {}
                @Retention(RetentionPolicy.RUNTIME) @RootApplication @interface NestedApplication {}
                @NestedApplication
                class App {}
                """);

        assertThat(repo.services()).singleElement().satisfies(service -> {
            assertThat(service.name()).isEqualTo("composed-app");
            assertThat(service.nameEvidence().file()).isEqualTo("src/main/java/app/BootAnnotations.java");
            assertThat(service.nameEvidence().line()).isEqualTo(6);
        });
    }

    @Test
    void springBootApplicationOnAnAnnotationDeclarationDoesNotCreateAService() {
        var repo = repo("annotation-is-not-app").java("app/BootMarker.java",
                "package app; @SpringBootApplication @interface BootMarker {}");

        assertThat(repo.services()).isEmpty();
    }

    @Test
    void unresolvedAndCyclicSourceAnnotationsDoNotCreateServices() {
        var repo = repo("annotation-cycle").file("src/main/java/app/Annotations.java", """
                package app;
                import java.lang.annotation.*;
                @MissingBootMeta @CycleA class NotAnApplication {}
                @Retention(RetentionPolicy.RUNTIME) @CycleB @interface CycleA {}
                @Retention(RetentionPolicy.RUNTIME) @CycleA @interface CycleB {}
                """);

        assertThat(repo.services()).isEmpty();
    }

    @Test
    void composedApplicationAnnotationsMustBeRetainedAtRuntime() {
        for (String retention : java.util.List.of("", "@Retention(RetentionPolicy.CLASS)", "@Retention(RetentionPolicy.SOURCE)")) {
            var repo = repo("not-runtime-" + Math.abs(retention.hashCode())).java("app/Annotations.java", """
                    package app;
                    import java.lang.annotation.*;
                    @SpringBootApplication
                    """ + retention + " @interface Marker {}\n@Marker class NotAnApplication {}");
            assertThat(repo.services()).isEmpty();
        }
    }

    @Test
    void composedApplicationDrivesItsOwnModuleScanWhenOtherAppsExist() {
        var repo = repo("composed-module-scan")
                .file("orders/pom.xml", "<project><artifactId>orders</artifactId><dependencies><dependency><artifactId>shared</artifactId></dependency></dependencies></project>")
                .file("shared/pom.xml", "<project><artifactId>shared</artifactId></project>")
                .file("orders/src/main/java/orders/BootAnnotations.java", """
                        package orders;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.RUNTIME) @SpringBootApplication(scanBasePackages = "shared.api") @interface RootApplication {}
                        @Retention(RetentionPolicy.RUNTIME) @RootApplication @interface NestedApplication {}
                        @NestedApplication class App {}
                        """)
                .file("users/src/main/java/users/App.java", "package users; @SpringBootApplication class App {}")
                .file("shared/src/main/java/shared/api/InScan.java", "package shared.api; @Service class InScan {}")
                .file("shared/src/main/java/shared/other/OutOfScan.java", "package shared.other; @Service class OutOfScan {}");

        Service orders = repo.service("orders");
        assertThat(orders.units()).extracting(cu -> orders.index().fileOf(cu).getFileName().toString())
                .contains("InScan.java").doesNotContain("OutOfScan.java");
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
    void configRepoPerAppFoldersAreReadWithAppSpecificOverApplicationAndProfileOverDefault() {
        var repo = ordersAndServer("remote-folders", "");
        var configRepo = TempRepo.at(tmp.resolve("folders-config"))
                .file("orders/application.yml", "layer: folder-application\nonly-folder: yes\n")
                .file("orders/orders.yml", "layer: folder-app\n")
                .file("orders/orders-prod.yml", "layer: folder-app-profile\n");

        var service = service(repo.services("prod", configRepo.root()), "orders");

        assertThat(service.config().get("layer").value()).isEqualTo("folder-app-profile");
        assertThat(service.config().get("only-folder").value()).isEqualTo("yes");
    }

    @Test
    void configServerGitSearchPathsExpandApplicationPlaceholderAndGlobs() {
        var repo = ordersAndServer("remote-git-paths", """
                spring.cloud.config.server.git.search-paths: "{application}, config/*, ,{unresolved}"
                """);
        var configRepo = TempRepo.at(tmp.resolve("git-config"))
                .file("orders/orders.yml", "from: placeholder\n")
                .file("config/team-a/orders.yml", "glob: team-a\n")
                .file("config/team-b/application.yml", "glob-b: team-b\n")
                .file("config/readme.txt", "not a directory\n")
                .file("other/orders.yml", "from: other\n");

        var config = service(repo.services("default", configRepo.root()), "orders").config();

        assertThat(config.get("from").value()).isEqualTo("placeholder");
        assertThat(config.get("glob").value()).isEqualTo("team-a");
        assertThat(config.get("glob-b").value()).isEqualTo("team-b");
    }

    @Test
    void searchPathsWithLeadingSlashAndMissingGlobParentAreTolerated() {
        var repo = ordersAndServer("remote-odd-paths", """
                spring.cloud.config.server.git.searchPaths:
                  - /orders
                  - missing/*
                """);
        var configRepo = TempRepo.at(tmp.resolve("odd-config")).file("orders/orders.yml", "from: slash\n");

        assertThat(service(repo.services("default", configRepo.root()), "orders").config().get("from").value())
                .isEqualTo("slash");
    }

    @Test
    void nativeClasspathSearchLocationsPickTheFoldersInsideTheConfigRepo() {
        var repo = ordersAndServer("remote-native", """
                spring.cloud.config.server.native.search-locations: classpath:/shared, file:///elsewhere
                """);
        var configRepo = TempRepo.at(tmp.resolve("native-config"))
                .file("shared/orders.yml", "from: shared\n")
                .file("orders.yml", "from: root\n");

        assertThat(service(repo.services("default", configRepo.root()), "orders").config().get("from").value())
                .isEqualTo("shared");
    }

    @Test
    void configServerWithClasspathLocationsIsItsOwnConfigRepoWhenNoneIsGiven() {
        var repo = ordersAndServer("remote-auto", """
                spring.cloud.config.server.native.search-locations: classpath:/shared
                """)
                .file("server/src/main/resources/shared/application.yml", "layer: shared-application\n")
                .file("server/src/main/resources/shared/orders.yml", "layer: shared-orders\nport: 8000\n");

        var orders = service(repo.services(), "orders");

        assertThat(orders.config().get("layer").value()).isEqualTo("shared-orders");
        assertThat(orders.config().get("layer").evidence().file()).isEqualTo("server/src/main/resources/shared/orders.yml");
        assertThat(orders.config().get("port").value()).isEqualTo("8000");
    }

    @Test
    void configServerWithOnlyNonClasspathLocationsOrNoLocationsAddsNoRemoteConfig() {
        var fileOnly = ordersAndServer("remote-file-only", """
                spring.cloud.config.server.native.search-locations: file:///${GIT_REPO}
                """)
                .file("server/src/main/resources/shared/orders.yml", "from: shared\n");
        var none = ordersAndServer("remote-none", "")
                .file("server/src/main/resources/shared/orders.yml", "from: shared\n");

        assertThat(service(fileOnly.services(), "orders").config().get("from")).isNull();
        assertThat(service(none.services(), "orders").config().get("from")).isNull();
    }

    @Test
    void nacosSeedRowsBecomeRemoteConfigWithEscapesSingleProfileAndPrecedence() {
        var repo = repo("nacos").app()
                .resource("application.yml", "spring.application.name: orders\nlayer: local\nlocal-only: yes\n")
                .file("db/nacos_config.sql", """
                        INSERT INTO `config_info` (`id`, `data_id`, `group_id`, `content`, `gmt_create`) VALUES
                        (1, 'application-dev.yml', 'DEFAULT_GROUP', 'layer: nacos-application\\nsecret.password: \\'pw\\'\\n', NOW()),
                        (2, 'orders-dev.yml', 'DEFAULT_GROUP', 'layer: nacos-app\\ntext: it''s\\ttab\\\\\\n', NOW());
                        INSERT INTO other (a) VALUES (1);
                        INSERT INTO config_info (id, group_id) VALUES (3, 'x');
                        INSERT INTO config_info (data_id, content) VALUES ('orders-dev.properties', 'prop=value'), ('notes.txt', 'ignored'), ('short');
                        """);

        var service = repo.services().getFirst();

        assertThat(service.config().get("layer").value()).isEqualTo("nacos-app");
        assertThat(service.config().get("layer").evidence().file()).isEqualTo("db/nacos_config.sql");
        assertThat(service.config().get("layer").evidence().line()).isEqualTo(3);
        assertThat(service.config().get("text").value()).isEqualTo("it's\ttab\\");
        assertThat(service.config().get("prop").value()).isEqualTo("value");
        assertThat(service.config().get("local-only").value()).isEqualTo("yes");
        assertThat(service.config().get("secret.password").value()).isNull();
    }

    @Test
    void nacosSeedKeepsTheDefaultProfileWhenSeveralProfilesExistAndAppliesAnExplicitOne() {
        var repo = repo("nacos-profiles").app()
                .resource("application.yml", "spring.application.name: orders\n")
                .file("nacos-config.sql", """
                        INSERT INTO config_info (data_id, content) VALUES
                        ('orders.yml', 'layer: base'), ('orders-dev.yml', 'layer: dev'), ('application-prod.yml', 'extra: prod'),
                        ('orders-dev.yml.bak', 'layer: ignored'), ('application.yml', 'a: [');
                        """);

        assertThat(repo.services().getFirst().config().get("layer").value()).isEqualTo("base");
        assertThat(repo.services().getFirst().config().get("extra")).isNull();
        assertThat(repo.services("dev", null).getFirst().config().get("layer").value()).isEqualTo("dev");
        assertThat(repo.services("prod", null).getFirst().config().get("extra").value()).isEqualTo("prod");
    }

    @Test
    void nacosSeedParserHandlesUnterminatedInputAndNonSeedSqlFiles() {
        var repo = repo("nacos-odd").app()
                .resource("application.yml", "spring.application.name: orders\n")
                .file("schema.sql", "INSERT INTO config_info (data_id, content) VALUES ('orders.yml', 'ignored: yes');\n")
                .file("config-broken.sql", "INSERT INTO config_info (data_id, content) VALUES ('orders.yml', 'cut: off")
                .file("config-raw.sql", "INSERT INTO config_info (data_id, content) VALUES (concat('a', ('b')), 'x: 1') ,")
                .file("config-empty.sql", "INSERT INTO config_info (data_id, content) VALUES ");

        assertThat(repo.services().getFirst().config().get("ignored")).isNull();
        assertThat(repo.services().getFirst().config().get("cut").value()).isEqualTo("off");
    }

    private static Service service(java.util.List<Service> services, String name) {
        return services.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    /** An {@code orders} app next to a config-server module whose own config is {@code serverYaml}. */
    private TempRepo ordersAndServer(String name, String serverYaml) {
        return repo(name)
                .file("orders/src/main/java/orders/App.java", "package orders; @SpringBootApplication class App {}")
                .file("orders/src/main/resources/application.properties", "spring.application.name=orders\n")
                .file("server/src/main/java/server/App.java", "package server; @SpringBootApplication @EnableConfigServer class App {}")
                .file("server/src/main/resources/application.yml", "spring.application.name: server\n" + serverYaml);
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
        var other = repo.service("other");
        assertThat(other.units()).extracting(cu -> other.index().fileOf(cu).getFileName().toString())
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
    void singleApplicationWithoutBuildMetadataFallsBackToAllSources() {
        var repo = repo("single-app-multi-module")
                .file("service/src/main/java/app/App.java", "package app; @SpringBootApplication class App {}")
                .file("library/src/main/java/common/Helper.java", "package common; class Helper {}")
                .file("unrelated/src/main/java/old/Legacy.java", "package old; class Legacy {}");

        var service = repo.services().getFirst();

        assertThat(service.units()).extracting(cu -> service.index().fileOf(cu).getFileName().toString())
                .containsExactlyInAnyOrder("App.java", "Helper.java", "Legacy.java");
    }

    @Test
    void singleModuleApplicationWithBuildMetadataKeepsItsOwnSources() {
        for (String descriptor : java.util.List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
            var repo = repo("single-module-" + descriptor).app()
                    .file(descriptor, descriptor.equals("pom.xml") ? "<project><artifactId>app</artifactId></project>" : "")
                    .java("other/Helper.java", "package other; class Helper {}");
            Service service = repo.service();

            assertThat(service.units()).extracting(cu -> service.index().fileOf(cu).getFileName().toString())
                    .containsExactlyInAnyOrder("App.java", "Helper.java");
        }
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
