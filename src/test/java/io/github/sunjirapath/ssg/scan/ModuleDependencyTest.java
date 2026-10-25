package io.github.sunjirapath.ssg.scan;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.extract.DataExtractor;
import io.github.sunjirapath.ssg.extract.JdbcExtractor;
import io.github.sunjirapath.ssg.extract.OutboundHttpExtractor;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Fact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Library modules (no app of their own) count for the services whose pom.xml or build.gradle depends on them. */
class ModuleDependencyTest {

    @TempDir
    Path tmp;

    @Test
    void identicalSiblingSourcesKeepTheirOwnFilesModulesAndEndpointEvidence() {
        String controller = """
                package app;
                @RestController class Controller {
                    @GetMapping("/runtime") void get() {}
                }
                """;
        var repo = TempRepo.at(tmp.resolve("identical-copies"))
                .file("platform/backend/build.gradle", "plugins { id 'java' }")
                .file("platform/backend/src/main/java/app/App.java", "package app; @SpringBootApplication class App {}")
                .file("platform/backend/src/main/resources/application.properties", "spring.application.name=system\n")
                .file("platform/backend/src/main/java/app/Controller.java", controller)
                .file("tools/backend/src/main/java/app/Controller.java", controller);
        JavaIndex index = repo.index();
        var copies = index.units().stream().filter(cu -> cu.getType(0).getNameAsString().equals("Controller")).toList();

        assertThat(copies).hasSize(2);
        assertThat(copies.get(0)).isEqualTo(copies.get(1)).isNotSameAs(copies.get(1));
        assertThat(copies).extracting(cu -> index.repo().relative(index.fileOf(cu))).containsExactlyInAnyOrder(
                "platform/backend/src/main/java/app/Controller.java", "tools/backend/src/main/java/app/Controller.java");
        assertThat(copies).extracting(cu -> index.repo().relative(index.moduleOf(index.fileOf(cu))))
                .containsExactlyInAnyOrder("platform/backend", "tools/backend");
        Service service = repo.service();
        assertThat(service.units()).extracting(cu -> service.repo().relative(service.index().fileOf(cu)))
                .containsExactlyInAnyOrder("platform/backend/src/main/java/app/App.java",
                        "platform/backend/src/main/java/app/Controller.java");

        var exposes = TempRepo.scan(repo).edges().stream().filter(e -> e.kind() == Edge.Kind.EXPOSES).toList();
        assertThat(exposes).singleElement().satisfies(edge -> {
            assertThat(edge.to()).isEqualTo("endpoint:system:GET /runtime");
            assertThat(edge.evidence()).singleElement().satisfies(evidence -> {
                assertThat(evidence.file()).isEqualTo("platform/backend/src/main/java/app/Controller.java");
                assertThat(evidence.line()).isEqualTo(3);
            });
        });
    }

    @Test
    void singleApplicationUsesGradleDependenciesAndComponentScanWithRemappedProjects() {
        var repo = TempRepo.at(tmp.resolve("single-gradle"))
                .file("settings.gradle", """
                        include ':app', ':library', ':unused'
                        project(':app').projectDir = file('services/orders')
                        project(':library').projectDir = file('libs/shared')
                        """)
                .file("services/orders/build.gradle", "dependencies { implementation project(':library') }")
                .file("services/orders/src/main/java/start/App.java", "package start; @SpringBootApplication class App {}")
                .file("services/orders/src/main/resources/application.properties", "spring.application.name=system\n")
                .file("libs/shared/build.gradle", "")
                .file("libs/shared/src/main/java/start/web/SharedController.java", """
                        package start.web;
                        @RestController class SharedController { @GetMapping("/shared") String get() { return ""; } }
                        """)
                .file("libs/shared/src/main/java/other/OutOfScanController.java", """
                        package other;
                        @RestController class OutOfScanController { @GetMapping("/out-of-scan") String get() { return ""; } }
                        """)
                .file("unused/build.gradle", "")
                .file("unused/src/main/java/start/unused/LegacyController.java", """
                        package start.unused;
                        @RestController class LegacyController { @GetMapping("/unused-admin") String get() { return ""; } }
                        """);
        Service service = repo.service();

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "SharedController.java");
        assertThat(service.libraries()).containsExactly(repo.root().resolve("libs/shared").toAbsolutePath());
        assertThat(edges(repo, Edge.Kind.EXPOSES)).containsExactly("service:system -> endpoint:system:GET /shared");
    }

    @Test
    void singleApplicationKeepsTransitiveAutoConfigurationsClientsAndLibraryResources() {
        var repo = TempRepo.at(tmp.resolve("single-maven"))
                .file("system-start/pom.xml", pom("system-start", "starter"))
                .file("system-start/src/main/java/start/App.java", "package start; @SpringBootApplication class App {}")
                .file("system-start/src/main/resources/application.properties", "spring.application.name=system\n")
                .file("starter/pom.xml", pom("starter", "users-api", "dao"))
                .file("starter/src/main/java/starter/LookupConfig.java", """
                        package starter;
                        import api.UserApi;
                        import dao.AuditMapper;
                        @AutoConfiguration class LookupConfig { UserApi users; AuditMapper audit; }
                        """)
                .file("starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                        "starter.LookupConfig\n")
                .file("starter/src/main/java/other/InactiveBean.java", "package other; @Service class InactiveBean {}")
                .file("users-api/pom.xml", pom("users-api"))
                .file("users-api/src/main/java/api/UserApi.java", """
                        package api;
                        @FeignClient(name = "users")
                        public interface UserApi { @GetMapping("/rpc/users/{id}") String get(@PathVariable long id); }
                        """)
                .file("dao/pom.xml", pom("dao"))
                .file("dao/src/main/java/dao/AuditMapper.java", "package dao; @Mapper public interface AuditMapper {}")
                .file("dao/src/main/java/dao/AuditMapper.xml",
                        "<mapper namespace=\"dao.AuditMapper\"><select id=\"all\">select * from audit</select></mapper>")
                .file("dao/src/main/resources/db/schema.sql", "create table accounts (id bigint primary key);\n")
                .file("unused/pom.xml", pom("unused"))
                .file("unused/src/main/resources/db/schema.sql", "create table unused_table (id bigint primary key);\n");
        Service service = repo.service();

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "LookupConfig.java", "UserApi.java", "AuditMapper.java");
        assertThat(new OutboundHttpExtractor().extract(service)).filteredOn(Fact.Call.class::isInstance)
                .map(Fact.Call.class::cast).extracting(call -> call.url().text())
                .containsExactly("lb://users/rpc/users/{id}");
        assertThat(new DataExtractor().extract(service)).filteredOn(Fact.TableUse.class::isInstance)
                .map(Fact.TableUse.class::cast).extracting(table -> table.table().text())
                .containsExactlyInAnyOrder("accounts", "audit");
    }

    @Test
    void componentScannedControllersCountFromDirectAndTransitiveDependencies() {
        var repo = twoApps("controllers")
                .file("system-start/pom.xml", pom("system-start", "system-biz"))
                .file("system-biz/pom.xml", pom("system-biz", "system-core"))
                .file("system-biz/src/main/java/start/biz/UserController.java", """
                        package start.biz;
                        @RestController class UserController { @GetMapping("/sys/user") String list() { return ""; } }
                        """)
                .file("system-core/pom.xml", pom("system-core"))
                .file("system-core/src/main/java/start/core/HealthController.java", """
                        package start.core;
                        @RestController class HealthController { @GetMapping("/core/health") String ok() { return ""; } }
                        """);

        assertThat(edges(repo, Edge.Kind.EXPOSES)).contains("service:system -> endpoint:system:GET /sys/user",
                "service:system -> endpoint:system:GET /core/health");
    }

    @Test
    void appliedGradleScriptsUseTheProjectDirectoryAndIgnoreCyclesAndRepoEscapes() {
        TempRepo.at(tmp).file("outside.gradle", "dependencies { implementation project(':outside') }");
        var repo = TempRepo.at(tmp.resolve("applied-gradle"))
                .file("settings.gradle", "include ':app', ':first', ':second', ':third', ':tests', ':unused'")
                .file("app/build.gradle", """
                        apply from: 'gradle/dependencies.gradle'
                        apply(from = "${rootDir}/shared.gradle")
                        // apply from: 'unapplied.gradle'
                        """)
                .file("app/gradle/dependencies.gradle", """
                        dependencies {
                            implementation project(':first')
                            testImplementation project(':tests')
                        }
                        apply from: 'extra.gradle'
                        apply from: 'build.gradle'
                        apply from: '../../outside.gradle'
                        """)
                .file("app/extra.gradle", """
                        dependencies { api project(':second') }
                        apply from: 'gradle/dependencies.gradle'
                        """)
                .file("shared.gradle", """
                        dependencies { runtimeOnly project(':third') }
                        apply from: '${projectDir}/extra.gradle'
                        """)
                .file("app/gradle/extra.gradle", "dependencies { implementation project(':unused') }")
                .file("app/unapplied.gradle", "dependencies { implementation project(':unused') }");
        Path root = repo.root().toAbsolutePath().normalize();

        assertThat(Modules.read(Repo.of(root)).direct(root.resolve("app")))
                .containsExactlyInAnyOrder(root.resolve("first"), root.resolve("second"), root.resolve("third"));
    }

    @Test
    void inheritedConfigurationLoadsItsScannedTransitiveControllersAndImports() {
        var repo = TempRepo.at(tmp.resolve("inherited-config"))
                .file("app/pom.xml", pom("app", "starter"))
                .file("app/src/main/resources/application.properties", "spring.application.name=system\n")
                .file("app/src/main/java/extension/App.java", """
                        package extension;
                        import starter.ExtensionBase;
                        @SpringBootApplication class App extends ExtensionBase {}
                        """)
                .file("starter/pom.xml", pom("starter", "rest", "base"))
                .file("starter/src/main/java/starter/ExtensionBase.java", """
                        package starter;
                        @Configuration @ComponentScan("rest.extensions.*")
                        public class ExtensionBase extends BaseConfiguration {}
                        """)
                .file("starter/src/main/java/starter/BaseConfiguration.java", """
                        package starter;
                        import base.HealthController;
                        @Configuration @Import(HealthController.class) class BaseConfiguration {}
                        """)
                .file("rest/pom.xml", pom("rest"))
                .file("rest/src/main/java/rest/extensions/connect/ExtensionController.java", """
                        package rest.extensions.connect;
                        @RestController class ExtensionController { @GetMapping("/extension") void get() {} }
                        """)
                .file("rest/src/main/java/other/OutOfScanController.java", """
                        package other;
                        @RestController class OutOfScanController { @GetMapping("/unscanned") void get() {} }
                        """)
                .file("base/pom.xml", pom("base"))
                .file("base/src/main/java/base/HealthController.java", """
                        package base;
                        @RestController public class HealthController { @GetMapping("/health") void get() {} }
                        """)
                .file("unused/pom.xml", pom("unused"))
                .file("unused/src/main/java/rest/extensions/core/CoreController.java", """
                        package rest.extensions.core;
                        @RestController class CoreController { @GetMapping("/unused") void get() {} }
                        """);

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "ExtensionBase.java", "BaseConfiguration.java",
                "ExtensionController.java", "HealthController.java");
        assertThat(edges(repo, Edge.Kind.EXPOSES)).containsExactlyInAnyOrder(
                "service:system -> endpoint:system:GET /extension", "service:system -> endpoint:system:GET /health");
    }

    @Test
    void registeredOuterConfigurationsLoadOnlyTheirConfigurationMembers() {
        for (String outer : List.of("Configuration", "Component", "SelectorConfiguration")) {
            var repo = bootSources("member-configuration-" + outer, """
                    package launch;
                    import boot.Selector;
                    class App { public static void main(String[] args) { SpringApplication.run(Selector.class, args); } }
                    """)
                    .file("lib/src/main/java/boot/SelectorConfiguration.java", """
                            package boot;
                            @Retention(RetentionPolicy.RUNTIME) @Configuration @interface SelectorConfiguration {}
                            """)
                    .file("lib/src/main/java/boot/Selector.java", """
                            package boot;
                            @%s public class Selector {
                                @SelectorConfiguration static class Nested {
                                    @Import(RuntimeConfiguration.class) static class Imported {}
                                }
                                static class NotAConfiguration {
                                    @Configuration @Import(UnusedConfiguration.class) static class Unregistered {}
                                }
                            }
                            """.formatted(outer));

            assertThat(loaded(repo)).contains("ActiveController.java").doesNotContain("UnusedController.java");
            assertThat(edges(repo, Edge.Kind.EXPOSES)).containsExactly("service:system -> endpoint:system:GET /active");
        }
    }

    @Test
    void builderStartupHelperLoadsItsNestedImportedScanButNotUncalledConfiguration() {
        var repo = bootSources("builder-helper", """
                package launch;
                import boot.RuntimeConfiguration;
                import boot.UnusedConfiguration;
                public class ServerApplication extends SpringBootServletInitializer {
                    @Import(RuntimeConfiguration.class)
                    private static final class Configuration {}
                    @Import(UnusedConfiguration.class)
                    private static final class UnusedConfigurationSource {}

                    private static SpringApplicationBuilder configureApplication(SpringApplicationBuilder builder) {
                        return builder.sources(Configuration.class);
                    }

                    private static SpringApplicationBuilder unused(SpringApplicationBuilder builder) {
                        return builder.sources(UnusedConfigurationSource.class);
                    }

                    public static void main(String[] args) {
                        configureApplication(new SpringApplicationBuilder(ServerApplication.class)).run(args);
                    }
                }
                """);

        assertThat(loaded(repo)).contains("ActiveController.java", "BootAutoConfiguration.java")
                .doesNotContain("UnusedController.java");
        assertThat(edges(repo, Edge.Kind.EXPOSES)).containsExactly("service:system -> endpoint:system:GET /active");
    }

    @Test
    void literalBootSourcesSupportStaticRunAndApplicationAndBuilderConstructors() {
        for (String startup : List.of(
                "SpringApplication.run(RuntimeConfiguration.class, args);",
                "org.springframework.boot.SpringApplication.run(new Class<?>[]{RuntimeConfiguration.class}, args);",
                "new SpringApplication(RuntimeConfiguration.class).run(args);",
                "new SpringApplicationBuilder(RuntimeConfiguration.class).run(args);",
                "new SpringApplicationBuilder().profiles(\"default\").sources(RuntimeConfiguration.class).run(args);",
                "var builder = new SpringApplicationBuilder(); builder.sources(RuntimeConfiguration.class).run(args);")) {
            var repo = bootSources("boot-source-" + Math.abs(startup.hashCode()), """
                    package launch;
                    import boot.RuntimeConfiguration;
                    import boot.UnusedConfiguration;
                    class App {
                        public static void main(String[] args) { %s }
                        private static void unused() { SpringApplication.run(UnusedConfiguration.class, new String[0]); }
                    }
                    """.formatted(startup));

            assertThat(loaded(repo)).as(startup).contains("ActiveController.java", "BootAutoConfiguration.java")
                    .doesNotContain("UnusedController.java");
            assertThat(edges(repo, Edge.Kind.EXPOSES)).as(startup)
                    .containsExactly("service:system -> endpoint:system:GET /active");
        }
    }

    @Test
    void arbitraryClassLiteralsAndOtherSourcesMethodsDoNotRegisterConfigurationScans() {
        var repo = bootSources("not-boot-sources", """
                package launch;
                import boot.RuntimeConfiguration;
                import boot.UnusedConfiguration;
                class App {
                    public static void main(String[] args) {
                        inspect(UnusedConfiguration.class);
                        OtherBuilder other = new OtherBuilder();
                        other.sources(UnusedConfiguration.class);
                        SpringApplication.run(RuntimeConfiguration.class, args);
                    }
                    static void inspect(Class<?> type) {}
                    static class OtherBuilder { void sources(Class<?> type) {} }
                }
                """);

        assertThat(loaded(repo)).contains("ActiveController.java").doesNotContain("UnusedController.java");
        assertThat(edges(repo, Edge.Kind.EXPOSES)).containsExactly("service:system -> endpoint:system:GET /active");
    }

    @Test
    void jpaScansLoadUnreferencedRepositoriesAndEntitiesWithinRegisteredPackages() {
        var repo = persistence("registered-jpa", """
                package configuration;
                import runtime.entities.ActiveEntity;
                @Configuration
                @EnableJpaRepositories(basePackages = "runtime.domain", excludeFilters =
                    @ComponentScan.Filter(type = FilterType.REGEX, pattern = "runtime\\\\.domain\\\\.ExcludedRepository"))
                @EntityScan(basePackageClasses = ActiveEntity.class)
                class PersistenceConfiguration {}
                """)
                .file("lib/src/main/java/runtime/domain/BaseRepository.java", """
                        package runtime.domain;
                        @NoRepositoryBean interface BaseRepository<T, ID> extends JpaRepository<T, ID> {}
                        """)
                .file("lib/src/main/java/runtime/domain/ActiveRepository.java", """
                        package runtime.domain;
                        import runtime.entities.ActiveEntity;
                        interface ActiveRepository extends BaseRepository<ActiveEntity, Long> {}
                        """)
                .file("lib/src/main/java/runtime/domain/ExcludedRepository.java", """
                        package runtime.domain;
                        import unused.entities.UnusedEntity;
                        interface ExcludedRepository extends JpaRepository<UnusedEntity, Long> {}
                        """)
                .file("lib/src/main/java/outside/domain/OutOfScanRepository.java", """
                        package outside.domain;
                        import unused.entities.UnusedEntity;
                        interface OutOfScanRepository extends JpaRepository<UnusedEntity, Long> {}
                        """)
                .file("lib/src/main/java/launch/domain/DefaultRepository.java", """
                        package launch.domain;
                        import unused.entities.UnusedEntity;
                        interface DefaultRepository extends JpaRepository<UnusedEntity, Long> {}
                        """)
                .file("lib/src/main/java/launch/entities/DefaultEntity.java",
                        "package launch.entities; @Entity @Table(name=\"default_table\") class DefaultEntity {}");

        assertThat(loaded(repo)).contains("ActiveRepository.java", "ActiveEntity.java", "DetachedEntity.java")
                .doesNotContain("ExcludedRepository.java", "OutOfScanRepository.java", "UnusedEntity.java",
                        "DefaultRepository.java", "DefaultEntity.java");
        assertThat(repo.facts(new DataExtractor())).filteredOn(Fact.TableUse.class::isInstance)
                .map(Fact.TableUse.class::cast).extracting(t -> t.table().text())
                .containsOnly("active_table", "detached_table");
    }

    @Test
    void entityManagerBuilderPackagesFollowOnlyTheReferencedLocalCollectionHelper() {
        var repo = persistence("builder-entity-packages", """
                package configuration;
                @Configuration class PersistenceConfiguration {
                    @Bean Object entityManagerFactory(EntityManagerFactoryBuilder factoryBuilder) {
                        String[] packagesToScan = getPackagesToScan();
                        OtherBuilder unrelated = new OtherBuilder();
                        unrelated.packages("unused.entities");
                        return factoryBuilder.dataSource(null).packages(packagesToScan).build();
                    }
                    private String[] getPackagesToScan() {
                        Set<String> packagesToScan = new HashSet<>();
                        packagesToScan.add("runtime.entities");
                        return packagesToScan.toArray(String[]::new);
                    }
                    private String[] neverCalled() { return new String[]{"unused.entities"}; }
                    static class OtherBuilder { void packages(String value) {} }
                }
                """)
                .file("unused/pom.xml", pom("unused"))
                .file("unused/src/main/java/runtime/entities/SiblingEntity.java",
                        "package runtime.entities; @Entity @Table(name=\"sibling_table\") class SiblingEntity {}");

        assertThat(loaded(repo)).contains("ActiveEntity.java", "DetachedEntity.java")
                .doesNotContain("UnusedEntity.java", "SiblingEntity.java");
        assertThat(repo.facts(new DataExtractor())).filteredOn(Fact.TableUse.class::isInstance)
                .map(Fact.TableUse.class::cast).extracting(t -> t.table().text())
                .containsExactlyInAnyOrder("active_table", "detached_table");
    }

    @Test
    void entityManagerBuilderPackagesAcceptLiteralArraysAndPackageClasses() {
        for (String argument : List.of("\"runtime.entities\"", "new String[]{\"runtime.entities\"}",
                "runtime.entities.ActiveEntity.class", "List.of(\"runtime.entities\").toArray(String[]::new)")) {
            var repo = persistence("entity-package-" + Math.abs(argument.hashCode()), """
                    package configuration;
                    @Configuration class PersistenceConfiguration {
                        @Bean Object entityManagerFactory(EntityManagerFactoryBuilder builder) {
                            return builder.packages(%s).build();
                        }
                    }
                    """.formatted(argument));

            assertThat(loaded(repo)).as(argument).contains("ActiveEntity.java", "DetachedEntity.java")
                    .doesNotContain("UnusedEntity.java");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"LocalSessionFactoryBean", "LocalContainerEntityManagerFactoryBean"})
    void nativePersistenceFactoryPackageSettersIgnoreUnrelatedReceivers(String factoryType) {
        var repo = persistence("native-entity-packages-" + factoryType, """
                package configuration;
                @Configuration class PersistenceConfiguration {
                    @Bean Object entityManagerFactory() {
                        OtherFactory unrelated = new OtherFactory();
                        unrelated.setPackagesToScan("unused.entities");
                        %s factory = new %s();
                        factory.setPackagesToScan(new String[]{"runtime.entities"});
                        return factory;
                    }
                    private Object neverCalled() {
                        LocalSessionFactoryBean factory = new LocalSessionFactoryBean();
                        factory.setPackagesToScan("unused.entities");
                        return factory;
                    }
                    static class OtherFactory { void setPackagesToScan(String value) {} }
                }
                """.formatted(factoryType, factoryType));

        assertThat(loaded(repo)).contains("ActiveEntity.java", "DetachedEntity.java").doesNotContain("UnusedEntity.java");
        assertThat(repo.facts(new DataExtractor())).filteredOn(Fact.TableUse.class::isInstance)
                .map(Fact.TableUse.class::cast).extracting(t -> t.table().text())
                .containsExactlyInAnyOrder("active_table", "detached_table");
    }

    @Test
    void bootDefaultPersistenceUsesTheApplicationPackageIndependentlyOfComponentScanPackages() {
        var repo = TempRepo.at(tmp.resolve("boot-default-persistence"))
                .file("system-start/pom.xml", pom("system-start", "lib"))
                .file("system-start/src/main/resources/application.properties", "spring.application.name=system\n")
                .file("system-start/src/main/java/launch/App.java",
                        "package launch; @SpringBootApplication(scanBasePackages=\"other\") class App {}")
                .file("lib/pom.xml", pom("lib"))
                .file("lib/src/main/java/launch/entities/DefaultEntity.java",
                        "package launch.entities; @Entity @Table(name=\"default_table\") class DefaultEntity {}")
                .file("lib/src/main/java/launch/domain/DefaultRepository.java", """
                        package launch.domain;
                        import launch.entities.DefaultEntity;
                        interface DefaultRepository extends JpaRepository<DefaultEntity, Long> {}
                        """)
                .file("lib/src/main/java/other/OtherConfiguration.java", "package other; @Configuration class OtherConfiguration {}")
                .file("lib/src/main/java/other/OutsideEntity.java",
                        "package other; @Entity @Table(name=\"outside_table\") class OutsideEntity {}")
                .file("lib/src/main/java/other/OutsideRepository.java",
                        "package other; interface OutsideRepository extends JpaRepository<OutsideEntity, Long> {}");

        assertThat(loaded(repo)).contains("DefaultEntity.java", "DefaultRepository.java", "OtherConfiguration.java")
                .doesNotContain("OutsideEntity.java", "OutsideRepository.java");
        assertThat(repo.facts(new DataExtractor())).filteredOn(Fact.TableUse.class::isInstance)
                .map(Fact.TableUse.class::cast).extracting(t -> t.table().text()).containsOnly("default_table");
    }

    @Test
    void beanCreatedImplementationKeepsItsLibrarySuperclassWithoutLoadingItsImplementedFeignClient() {
        var repo = TempRepo.at(tmp.resolve("bean-superclass"))
                .file("system-start/pom.xml", pom("system-start", "lib"))
                .file("system-start/src/main/resources/application.properties", "spring.application.name=system\n")
                .file("system-start/src/main/java/start/App.java", """
                        package start;
                        @SpringBootApplication @EnableFeignClients(basePackages="api") class App {}
                        @Configuration class Beans { @Bean Object database() { return new DatabaseImpl(); } }
                        """)
                .file("system-start/src/main/java/start/DatabaseImpl.java", """
                        package start;
                        import shared.JdbcBase;
                        class DatabaseImpl extends JdbcBase {}
                        """)
                .file("system-start/src/main/java/start/LocalApiImpl.java", """
                        package start;
                        import api.LocalApi;
                        @RestController class LocalApiImpl implements LocalApi { public String get() { return "ok"; } }
                        """)
                .file("lib/pom.xml", pom("lib"))
                .file("lib/src/main/java/shared/JdbcBase.java", """
                        package shared;
                        public abstract class JdbcBase {
                            JdbcTemplate jdbcTemplate;
                            String query() { return jdbcTemplate.queryForObject("select * from inherited_table", String.class); }
                        }
                        """)
                .file("lib/src/main/java/api/LocalApi.java", """
                        package api;
                        @FeignClient(name="remote") public interface LocalApi { @GetMapping("/local") String get(); }
                        """);

        assertThat(loaded(repo)).contains("JdbcBase.java").doesNotContain("LocalApi.java");
        assertThat(repo.facts(new JdbcExtractor(java.util.Set.of()))).filteredOn(Fact.TableUse.class::isInstance)
                .map(Fact.TableUse.class::cast).extracting(t -> t.table().text()).containsExactly("inherited_table");
        assertThat(repo.facts(new OutboundHttpExtractor())).filteredOn(Fact.Call.class::isInstance).isEmpty();
    }

    @Test
    void beanInTransitiveStarterMakesItsFeignClientCallsCount() {
        var repo = twoApps("feign")
                .file("system-start/pom.xml", pom("system-start", "starter"))
                .file("starter/pom.xml", pom("starter", "users-api"))
                .file("starter/src/main/java/starter/LookupConfig.java", """
                        package starter;
                        import api.UserApi;
                        @AutoConfiguration class LookupConfig { UserApi users; }
                        """)
                .file("starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                        "# loaded by Spring Boot\n\nstarter.LookupConfig\n")
                .file("users-api/pom.xml", pom("users-api"))
                .file("users-api/src/main/java/api/UserApi.java", """
                        package api;
                        @FeignClient(name = "users")
                        public interface UserApi { @GetMapping("/rpc/users/{id}") String get(@PathVariable long id); }
                        """);

        assertThat(edges(repo, Edge.Kind.CALLS)).containsExactly("service:system -> endpoint:users:GET /rpc/users/{id}");
    }

    @Test
    void sameNamedInterfaceResolvesToTheCopyInTheServicesOwnDependencies() {
        var repo = twoApps("twins")
                .file("system-start/pom.xml", pom("system-start", "biz"))
                .file("biz/pom.xml", pom("biz", "local-api"))
                .file("biz/src/main/java/start/biz/Lookup.java", """
                        package start.biz;
                        import api.UserApi;
                        @Service class Lookup { UserApi users; }
                        """)
                .file("local-api/pom.xml", pom("local-api"))
                .file("local-api/src/main/java/api/UserApi.java", "package api; public interface UserApi { String get(long id); }")
                .file("cloud-api/pom.xml", pom("cloud-api"))
                .file("cloud-api/src/main/java/api/UserApi.java", """
                        package api;
                        @FeignClient(name = "users")
                        public interface UserApi { @GetMapping("/rpc/users/{id}") String get(@PathVariable long id); }
                        """);

        assertThat(edges(repo, Edge.Kind.CALLS)).isEmpty();
    }

    @Test
    void sharedDdlAndUsedMapperXmlCountForEveryDependent() {
        var repo = twoApps("data")
                .file("system-start/pom.xml", pom("system-start", "dao"))
                .file("users/pom.xml", pom("users", "dao"))
                .file("dao/pom.xml", pom("dao"))
                .file("dao/src/main/java/dao/DaoConfig.java", "package dao; @AutoConfiguration @MapperScan(value=\"dao\", annotationClass=Mapper.class) class DaoConfig {}")
                .file("dao/src/main/resources/META-INF/spring.factories",
                        "org.springframework.boot.autoconfigure.EnableAutoConfiguration=\\\n  dao.DaoConfig\n")
                .file("dao/src/main/resources/db/schema.sql", "create table accounts (id bigint primary key);\n")
                .file("dao/src/main/java/dao/AuditMapper.java", "package dao; @Mapper public interface AuditMapper { }")
                .file("dao/src/main/java/dao/AuditMapper.xml", """
                        <mapper namespace="dao.AuditMapper"><select id="all">select * from audit</select></mapper>
                        """)
                // This mapper is outside the explicit annotation filter, so its XML must not count.
                .file("dao/src/main/java/dao/LegacyMapper.java", "package dao; public interface LegacyMapper { }")
                .file("dao/src/main/resources/dao/LegacyMapper.xml", """
                        <mapper namespace="dao.LegacyMapper"><select id="all">select * from legacy</select></mapper>
                        """);

        assertThat(edges(repo, Edge.Kind.OWNS))
                .contains("service:system -> table:system/accounts", "service:users -> table:users/accounts");
        assertThat(edges(repo, Edge.Kind.USES))
                .contains("service:system -> table:system/audit", "service:users -> table:users/audit")
                .noneMatch(e -> e.contains("legacy"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"@MapperScan(\"mappers.active\")",
            "@MapperScans({@MapperScan(\"mappers.active\")})",
            "@MapperScan(basePackageClasses=mappers.active.PackageAnchor.class)"})
    void mapperScanRegistersUnreferencedPlainInterfacesWithoutComponentScanningTheirPackage(String scan) {
        var repo = lib("plain-mapper-scan")
                .file("system-start/src/main/java/start/App.java", """
                        package start;
                        import configuration.MapperConfiguration;
                        @SpringBootApplication @Import(MapperConfiguration.class) class App {}
                        """)
                .file("lib/src/main/java/configuration/MapperConfiguration.java", """
                        package configuration;
                        @Configuration %s class MapperConfiguration {}
                        """.formatted(scan))
                .file("lib/src/main/java/mappers/active/PlainMapper.java", """
                        package mappers.active;
                        public interface PlainMapper { Object find(); }
                        """)
                .file("lib/src/main/java/mappers/active/PackageAnchor.java", "package mappers.active; public class PackageAnchor {}")
                .file("lib/src/main/resources/mappers/PlainMapper.xml", """
                        <mapper namespace="mappers.active.PlainMapper">
                          <select id="find">select * from registered_table</select>
                        </mapper>
                        """)
                .file("lib/src/main/java/mappers/active/UnrelatedComponent.java", """
                        package mappers.active;
                        @Service @Entity @Table(name="unrelated_component") class UnrelatedComponent {}
                        """)
                .file("lib/src/main/java/mappers/other/OtherMapper.java", "package mappers.other; interface OtherMapper {}")
                .file("lib/src/main/resources/mappers/OtherMapper.xml", """
                        <mapper namespace="mappers.other.OtherMapper"><select id="find">select * from other_table</select></mapper>
                        """)
                .file("unused/pom.xml", pom("unused"))
                .file("unused/src/main/java/mappers/active/SiblingMapper.java", "package mappers.active; interface SiblingMapper {}")
                .file("unused/src/main/resources/mappers/SiblingMapper.xml", """
                        <mapper namespace="mappers.active.SiblingMapper"><select id="find">select * from sibling_table</select></mapper>
                        """);

        assertThat(loaded(repo)).contains("PlainMapper.java")
                .doesNotContain("UnrelatedComponent.java", "OtherMapper.java", "SiblingMapper.java");
        assertThat(edges(repo, Edge.Kind.USES)).containsExactly("service:system -> table:system/registered_table");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "@SpringBootApplication @ComponentScan(basePackageClasses={configuration.PackageAnchor.class}, basePackages=\"extra\")",
            "@SpringBootApplication(scanBasePackageClasses={configuration.PackageAnchor.class}, scanBasePackages=\"extra\")"})
    void componentClassAnchorsCombineWithStringPackagesAndActivatePlainMappers(String scan) {
        var repo = lib("component-class-anchors")
                .file("system-start/src/main/java/start/App.java", "package start; " + scan + " class App {}")
                .file("lib/src/main/java/configuration/PackageAnchor.java", "package configuration; public interface PackageAnchor {}")
                .file("lib/src/main/java/configuration/MapperConfiguration.java", """
                        package configuration;
                        @Configuration @MapperScan("mappers.active") class MapperConfiguration {}
                        """)
                .file("lib/src/main/java/mappers/active/PlainMapper.java", "package mappers.active; public interface PlainMapper {}")
                .file("lib/src/main/resources/mappers/PlainMapper.xml", """
                        <mapper namespace="mappers.active.PlainMapper"><select id="find">select * from registered_table</select></mapper>
                        """)
                .file("lib/src/main/java/extra/ExtraComponent.java", "package extra; @Service class ExtraComponent {}");

        assertThat(loaded(repo)).contains("MapperConfiguration.java", "PlainMapper.java", "ExtraComponent.java")
                .doesNotContain("InScan.java", "OutOfScan.java");
        assertThat(edges(repo, Edge.Kind.USES)).containsExactly("service:system -> table:system/registered_table");
    }

    @Test
    void autoConfigurationComponentClassAnchorPreservesItsPlainMapperRegistration() {
        var repo = lib("auto-configuration-class-anchor")
                .file("lib/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                        "configuration.MapperConfiguration\n")
                .file("lib/src/main/java/configuration/MapperConfiguration.java", """
                        package configuration;
                        @AutoConfiguration @ComponentScan(basePackageClasses=MapperConfiguration.class)
                        @MapperScan("mappers.active") class MapperConfiguration {}
                        """)
                .file("lib/src/main/java/mappers/active/PlainMapper.java", "package mappers.active; public interface PlainMapper {}")
                .file("lib/src/main/resources/mappers/PlainMapper.xml", """
                        <mapper namespace="mappers.active.PlainMapper"><select id="find">select * from registered_table</select></mapper>
                        """);

        assertThat(loaded(repo)).contains("PlainMapper.java").doesNotContain("OutOfScan.java");
        assertThat(edges(repo, Edge.Kind.USES)).containsExactly("service:system -> table:system/registered_table");
    }

    @Test
    void activeMapperXmlResultMapRetainsRuntimeEntityEvidenceWithoutLoadingUnrelatedTypes() {
        var repo = lib("mapper-result-map-entity")
                .file("lib/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                        "configuration.MapperConfiguration\n")
                .file("lib/src/main/java/configuration/MapperConfiguration.java", """
                        package configuration;
                        @AutoConfiguration @ComponentScan(basePackageClasses=MapperConfiguration.class)
                        @MapperScan("mappers.active") class MapperConfiguration {}
                        """)
                .file("lib/src/main/java/mappers/active/PlainMapper.java", "package mappers.active; public interface PlainMapper {}")
                .file("lib/src/main/resources/mappers/PlainMapper.xml", """
                        <mapper namespace="mappers.active.PlainMapper">
                          <!-- <resultMap id="comment" type="entities.CommentedEntity"/> -->
                          <resultMap id="active" type="entities.ActiveEntity"/>
                          <resultMap id="alias" type="AliasEntity"/>
                          <resultMap id="missing" type="missing.Entity"/>
                          <resultMap id="sibling" type="unrelated.UnusedEntity"/>
                          <select id="find">select * from registered_table</select>
                        </mapper>
                        """)
                .file("lib/src/main/java/entities/ActiveEntity.java", """
                        package entities;
                        @TableName("registered_table") public class ActiveEntity {}
                        """)
                .file("lib/src/main/java/entities/CommentedEntity.java", "package entities; @TableName(\"commented_table\") class CommentedEntity {}")
                .file("lib/src/main/java/entities/AliasEntity.java", "package entities; @TableName(\"alias_table\") class AliasEntity {}")
                .file("lib/src/main/java/entities/UnregisteredEntity.java", "package entities; @TableName(\"unregistered_table\") class UnregisteredEntity {}")
                .file("lib/src/main/java/mappers/other/OtherMapper.java", "package mappers.other; public interface OtherMapper {}")
                .file("lib/src/main/resources/mappers/OtherMapper.xml", """
                        <mapper namespace="mappers.other.OtherMapper"><resultMap id="unused" type="entities.UnregisteredEntity"/></mapper>
                        """)
                .file("system-start/src/main/resources/mappers/InactiveMapper.xml", """
                        <mapper namespace="mappers.other.OtherMapper"><resultMap id="unused" type="entities.UnregisteredEntity"/></mapper>
                        """)
                .file("unused/pom.xml", pom("unused"))
                .file("unused/src/main/java/unrelated/UnusedEntity.java", "package unrelated; @TableName(\"sibling_table\") public class UnusedEntity {}")
                .file("unused/src/main/resources/mappers/PlainMapper.xml", """
                        <mapper namespace="mappers.active.PlainMapper"><resultMap id="unused" type="unrelated.UnusedEntity"/></mapper>
                        """);

        assertThat(loaded(repo)).contains("PlainMapper.java").doesNotContain("ActiveEntity.java", "CommentedEntity.java",
                "AliasEntity.java", "UnregisteredEntity.java", "OtherMapper.java", "UnusedEntity.java");
        var uses = TempRepo.scan(repo).edges().stream().filter(e -> e.kind() == Edge.Kind.USES).toList();
        assertThat(uses).singleElement().satisfies(edge -> {
            assertThat(edge.to()).isEqualTo("table:system/registered_table");
            assertThat(edge.evidence()).extracting(e -> e.file() + ":" + e.line()).containsExactlyInAnyOrder(
                    "lib/src/main/java/entities/ActiveEntity.java:2", "lib/src/main/resources/mappers/PlainMapper.xml:7");
        });
    }

    @Test
    void mapperScanAnnotationAndMarkerFiltersSelectTheirUnion() {
        for (String filters : List.of("annotationClass=Tagged.class", "markerInterface=Marker.class",
                "annotationClass=Tagged.class, markerInterface=Marker.class")) {
            var repo = lib("mapper-filter-" + Math.abs(filters.hashCode()))
                    .file("system-start/src/main/java/start/App.java", """
                            package start;
                            import configuration.MapperConfiguration;
                            @SpringBootApplication @Import(MapperConfiguration.class) class App {}
                            """)
                    .file("lib/src/main/java/configuration/MapperConfiguration.java", """
                            package configuration;
                            import contracts.Marker;
                            import contracts.Tagged;
                            @Configuration @MapperScan(basePackages="filtered", %s) class MapperConfiguration {}
                            """.formatted(filters))
                    .file("lib/src/main/java/contracts/Marker.java", "package contracts; public interface Marker {}")
                    .file("lib/src/main/java/contracts/Intermediate.java", "package contracts; public interface Intermediate extends Marker {}")
                    .file("lib/src/main/java/contracts/Tagged.java", """
                            package contracts;
                            import java.lang.annotation.*;
                            @Retention(RetentionPolicy.RUNTIME) public @interface Tagged {}
                            """)
                    .file("lib/src/main/java/filtered/TaggedMapper.java", "package filtered; @contracts.Tagged interface TaggedMapper {}")
                    .file("lib/src/main/java/filtered/MarkedMapper.java", "package filtered; interface MarkedMapper extends contracts.Intermediate {}")
                    .file("lib/src/main/java/filtered/BothMapper.java", "package filtered; @contracts.Tagged interface BothMapper extends contracts.Marker {}")
                    .file("lib/src/main/java/filtered/NeitherMapper.java", "package filtered; interface NeitherMapper {}");
            for (String mapper : List.of("TaggedMapper", "MarkedMapper", "BothMapper", "NeitherMapper"))
                repo.file("lib/src/main/resources/filtered/" + mapper + ".xml", "<mapper namespace=\"filtered." + mapper
                        + "\"><select id=\"find\">select * from " + mapper.toLowerCase(java.util.Locale.ROOT) + "</select></mapper>");

            var uses = edges(repo, Edge.Kind.USES);
            assertThat(uses).as(filters).contains("service:system -> table:system/bothmapper")
                    .noneMatch(e -> e.contains("neithermapper"));
            assertThat(uses.contains("service:system -> table:system/taggedmapper")).as(filters)
                    .isEqualTo(filters.contains("annotationClass"));
            assertThat(uses.contains("service:system -> table:system/markedmapper")).as(filters)
                    .isEqualTo(filters.contains("markerInterface"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MongoRepository", "ReactiveMongoRepository"})
    void bootDefaultsRegisterUnreferencedMongoRepositoriesIndependentlyOfJpaScans(String base) {
        var repo = lib("default-mongo-" + base)
                .file("lib/src/main/java/start/config/PersistenceConfiguration.java", """
                        package start.config;
                        @Configuration @EnableJpaRepositories("foreign") class PersistenceConfiguration {}
                        """)
                .file("lib/src/main/java/start/mongo/MongoDBRepository.java", """
                        package start.mongo;
                        interface MongoDBRepository extends %s<FranchiseDocument, String> {}
                        """.formatted(base))
                .file("lib/src/main/java/start/mongo/FranchiseDocument.java", """
                        package start.mongo;
                        @Document("franchises") class FranchiseDocument {}
                        """)
                .file("lib/src/main/java/start/mongo/IgnoredRepository.java", """
                        package start.mongo;
                        @NoRepositoryBean interface IgnoredRepository extends %s<Missing, String> {}
                        """.formatted(base))
                .file("lib/src/main/java/foreign/OtherRepository.java", """
                        package foreign;
                        interface OtherRepository extends %s<OtherDocument, String> {}
                        """.formatted(base))
                .file("lib/src/main/java/foreign/OtherDocument.java", "package foreign; @Document(\"other\") class OtherDocument {}");

        assertThat(loaded(repo)).contains("MongoDBRepository.java", "FranchiseDocument.java")
                .doesNotContain("IgnoredRepository.java", "OtherRepository.java", "OtherDocument.java");
        assertThat(edges(repo, Edge.Kind.USES)).containsExactly("service:system -> table:system/franchises");
    }

    @Test
    void explicitMongoScanDoesNotReplaceJpaOrReactiveMongoDefaults() {
        var repo = lib("separate-repository-stores")
                .file("lib/src/main/java/start/config/PersistenceConfiguration.java", """
                        package start.config;
                        @Configuration @EnableMongoRepositories("registered") class PersistenceConfiguration {}
                        """)
                .file("lib/src/main/java/registered/RegisteredRepository.java", """
                        package registered;
                        interface RegisteredRepository extends MongoRepository<RegisteredDocument, String> {}
                        """)
                .file("lib/src/main/java/registered/RegisteredDocument.java", "package registered; @Document(\"registered\") class RegisteredDocument {}")
                .file("lib/src/main/java/registered/ForeignReactiveRepository.java", """
                        package registered;
                        interface ForeignReactiveRepository extends ReactiveMongoRepository<Missing, String> {}
                        """)
                .file("lib/src/main/java/start/mongo/UnregisteredRepository.java", """
                        package start.mongo;
                        interface UnregisteredRepository extends MongoRepository<Missing, String> {}
                        """)
                .file("lib/src/main/java/start/reactive/DefaultReactiveRepository.java", """
                        package start.reactive;
                        interface DefaultReactiveRepository extends ReactiveMongoRepository<DefaultDocument, String> {}
                        """)
                .file("lib/src/main/java/start/reactive/DefaultDocument.java", "package start.reactive; @Document(\"reactive_default\") class DefaultDocument {}")
                .file("lib/src/main/java/start/jpa/DefaultJpaRepository.java", """
                        package start.jpa;
                        interface DefaultJpaRepository extends JpaRepository<JpaEntity, Long> {}
                        """)
                .file("lib/src/main/java/start/jpa/JpaEntity.java", "package start.jpa; @Entity @Table(name=\"jpa_default\") class JpaEntity {}");

        assertThat(loaded(repo)).contains("RegisteredRepository.java", "DefaultReactiveRepository.java", "DefaultJpaRepository.java")
                .doesNotContain("UnregisteredRepository.java", "ForeignReactiveRepository.java");
        assertThat(edges(repo, Edge.Kind.USES)).containsExactlyInAnyOrder("service:system -> table:system/registered",
                "service:system -> table:system/reactive_default", "service:system -> table:system/jpa_default");
    }

    @Test
    void commentedOutExcludedAndTestScopedDependenciesAreIgnored() {
        var repo = twoApps("ignored")
                .file("system-start/pom.xml", pom("system-start", """
                        <!-- <dependency><artifactId>old</artifactId></dependency> -->
                        <dependency><artifactId>fixtures</artifactId><scope>test</scope></dependency>
                        <dependency><artifactId>web</artifactId>
                          <exclusions><exclusion><artifactId>legacy</artifactId></exclusion></exclusions>
                        </dependency>
                        """))
                .file("web/pom.xml", pom("web", "legacy"))
                .file("old/pom.xml", pom("old"))
                .file("fixtures/pom.xml", pom("fixtures"))
                .file("legacy/pom.xml", pom("legacy"));
        Path root = repo.root().toAbsolutePath().normalize();

        assertThat(Modules.read(Repo.of(root)).all(root.resolve("system-start"), java.util.Set.of()))
                .containsExactly(root.resolve("web"));
    }

    @Test
    void gradleProjectDependenciesAreFollowed() {
        var repo = twoApps("gradle")
                .file("system-start/build.gradle", """
                        dependencies {
                            implementation project(':libs:biz')
                            // implementation project(':libs:old')
                            testImplementation project(':libs:fixtures')
                        }
                        """)
                .file("libs/biz/build.gradle", "dependencies { api(project(\":libs:core\")) }\n");
        Path root = repo.root().toAbsolutePath().normalize();

        assertThat(Modules.read(Repo.of(root)).all(root.resolve("system-start"), java.util.Set.of()))
                .containsExactly(root.resolve("libs/biz"), root.resolve("libs/core"));
    }

    @Test
    void selfReferencesCyclicParentsAndUnknownArtifactsDoNotLoop() {
        var repo = TempRepo.at(tmp.resolve("odd"))
                .file("a/pom.xml", """
                        <project><parent><artifactId>b</artifactId></parent><artifactId>a</artifactId><dependencies>
                        <dependency><artifactId>a</artifactId></dependency>
                        <dependency><artifactId>c</artifactId><exclusions>
                          <exclusion><artifactId>zzz</artifactId></exclusion><exclusion><artifactId>d</artifactId></exclusion>
                        </exclusions></dependency>
                        </dependencies></project>
                        """)
                .file("b/pom.xml", "<project><parent><artifactId>a</artifactId></parent><artifactId>b</artifactId>"
                        + "<dependencies><dependency><artifactId>c</artifactId></dependency></dependencies></project>")
                .file("c/pom.xml", pom("c", "d", "a"))
                .file("d/pom.xml", pom("d"))
                .file("e/build.gradle.kts", "dependencies { implementation(project(\":e\")); implementation(project(\":c\")) }")
                .file("f/pom.xml", "<project><parent><artifactId>nope</artifactId></parent><artifactId>f</artifactId></project>");
        Path root = repo.root().toAbsolutePath().normalize();
        var modules = Modules.read(Repo.of(root));

        assertThat(modules.all(root.resolve("a"), java.util.Set.of())).containsExactly(root.resolve("c"));
        assertThat(modules.all(root.resolve("e"), java.util.Set.of()))
                .containsExactlyInAnyOrder(root.resolve("c"), root.resolve("d"), root.resolve("a"));
        assertThat(modules.direct(root.resolve("f"))).isEmpty();
    }

    @Test
    void groupIdTwinsAreToldApartByVersionFromPropertiesOrDependencyManagement() {
        var repo = TempRepo.at(tmp.resolve("twins-by-version"))
                .file("pom.xml", """
                        <project><groupId>g</groupId><artifactId>root</artifactId><version>1</version>
                        <properties><lib.version>2</lib.version></properties>
                        <dependencyManagement><dependencies>
                          <dependency><groupId>${project.groupId}</groupId><artifactId>util</artifactId><version>${lib.version}</version></dependency>
                        </dependencies></dependencyManagement></project>
                        """)
                .file("v1/core/pom.xml", "<project><groupId>g</groupId><artifactId>core</artifactId><version>1</version></project>")
                .file("v2/core/pom.xml", "<project><groupId>g</groupId><artifactId>core</artifactId><version>2</version></project>")
                .file("v1/util/pom.xml", "<project><groupId>g</groupId><artifactId>util</artifactId><version>1</version></project>")
                .file("v2/util/pom.xml", "<project><groupId>g</groupId><artifactId>util</artifactId><version>2</version></project>")
                .file("other/core/pom.xml", "<project><groupId>h</groupId><artifactId>core</artifactId><version>1</version></project>")
                .file("app/pom.xml", """
                        <project><parent><groupId>g</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>app</artifactId>
                        <dependencies>
                          <dependency><groupId>g</groupId><artifactId>core</artifactId><version>${lib.version}</version></dependency>
                          <dependency><groupId>g</groupId><artifactId>util</artifactId></dependency>
                        </dependencies></project>
                        """);
        Path root = repo.root().toAbsolutePath().normalize();

        assertThat(Modules.read(Repo.of(root)).direct(root.resolve("app")))
                .containsExactly(root.resolve("v2/core"), root.resolve("v2/util"));
    }

    @Test
    void onlyProfilesActiveByDefaultAddDependencies() {
        var repo = TempRepo.at(tmp.resolve("profiles"))
                .file("app/pom.xml", pom("app", """
                        </dependencies><profiles>
                          <profile><id>on</id><activation><activeByDefault>true</activeByDefault></activation>
                            <dependencies><dependency><artifactId>default-lib</artifactId></dependency></dependencies></profile>
                          <profile><id>off</id><activation><property><name>x</name></property></activation>
                            <dependencies><dependency><artifactId>other-lib</artifactId></dependency></dependencies></profile>
                        </profiles><dependencies>
                        """))
                .file("default-lib/pom.xml", pom("default-lib"))
                .file("other-lib/pom.xml", pom("other-lib"));
        Path root = repo.root().toAbsolutePath().normalize();

        assertThat(Modules.read(Repo.of(root)).direct(root.resolve("app"))).containsExactly(root.resolve("default-lib"));
    }

    @Test
    void settingsProjectDirRemapsMoveProjectsAndTheirChildren() {
        var repo = TempRepo.at(tmp.resolve("remap"))
                .file("settings.gradle", """
                        include ':app', ':libs', ':libs:core', ':api'
                        project(':libs').projectDir = file('modules/libraries')
                        project(':api').projectDir = new File(rootDir, "contracts/api")
                        """)
                .file("app/build.gradle", "dependencies { implementation project(':libs:core'); implementation project(':api') }")
                .file("modules/libraries/core/build.gradle", "")
                .file("contracts/api/build.gradle", "");
        Path root = repo.root().toAbsolutePath().normalize();

        assertThat(Modules.read(Repo.of(root)).direct(root.resolve("app")))
                .containsExactly(root.resolve("modules/libraries/core"), root.resolve("contracts/api"));
    }

    @Test
    void exclusionDropsAModuleOnlyBelowItsDependencyUnlessAnotherPathReachesIt() {
        var repo = TempRepo.at(tmp.resolve("exclusions"))
                .file("app/pom.xml", pom("app", """
                        <dependency><artifactId>web</artifactId>
                          <exclusions><exclusion><artifactId>legacy</artifactId></exclusion><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>
                        </dependency>
                        <dependency><artifactId>batch</artifactId></dependency>
                        """))
                .file("web/pom.xml", pom("web", "legacy", "web-util"))
                .file("web-util/pom.xml", pom("web-util"))
                .file("batch/pom.xml", pom("batch", "legacy"))
                .file("legacy/pom.xml", "<project><groupId>h</groupId><artifactId>legacy</artifactId></project>")
                .file("solo/pom.xml", pom("solo", """
                        <dependency><artifactId>web</artifactId><exclusions><exclusion><groupId>h</groupId><artifactId>*</artifactId></exclusion></exclusions></dependency>
                        """))
                .file("child/pom.xml", "<project><parent><artifactId>app</artifactId></parent><artifactId>child</artifactId></project>");
        Path root = repo.root().toAbsolutePath().normalize();
        var modules = Modules.read(Repo.of(root));

        assertThat(modules.all(root.resolve("app"), java.util.Set.of()))
                .containsExactly(root.resolve("web"), root.resolve("batch"), root.resolve("legacy"));
        assertThat(modules.all(root.resolve("solo"), java.util.Set.of()))
                .containsExactly(root.resolve("web"), root.resolve("web-util"));
        assertThat(modules.all(root.resolve("child"), java.util.Set.of())) // exclusions inherited from the parent pom
                .containsExactly(root.resolve("web"), root.resolve("batch"), root.resolve("legacy"));
    }

    @Test
    void gradleExcludeBlockDropsAProjectBelowThatDependency() {
        var repo = TempRepo.at(tmp.resolve("gradle-exclude"))
                .file("settings.gradle", "include ':app', ':web', ':legacy', ':batch'")
                .file("app/build.gradle", """
                        dependencies {
                            implementation(project(':web')) { exclude group: 'g', module: 'legacy' }
                        }
                        """)
                .file("web/build.gradle", "dependencies { implementation project(':legacy'); implementation project(':batch') }")
                .file("legacy/build.gradle", "")
                .file("batch/build.gradle", "dependencies { implementation project(':app') }");
        Path root = repo.root().toAbsolutePath().normalize();

        assertThat(Modules.read(Repo.of(root)).all(root.resolve("app"), java.util.Set.of()))
                .containsExactly(root.resolve("web"), root.resolve("batch"));
    }

    @Test
    void nestedKotlinSettingsRootsTheirOwnProjectPaths() {
        var repo = TempRepo.at(tmp.resolve("gradle-kts"))
                .file("tools/settings.gradle.kts", """
                        include(":cli", ":core")
                        project(":core").projectDir = file("modules/core")
                        """)
                .file("tools/build.gradle.kts", "")
                .file("tools/cli/build.gradle.kts", """
                        dependencies {
                            implementation(project(":core")) { exclude(module = "nothing") }
                            implementation(project(":"))
                        }
                        """)
                .file("tools/modules/core/build.gradle.kts", "");
        Path root = repo.root().toAbsolutePath().normalize();

        assertThat(Modules.read(Repo.of(root)).direct(root.resolve("tools/cli")))
                .containsExactly(root.resolve("tools/modules/core"), root.resolve("tools"));
    }

    @Test
    void oddCoordinatesResolveOrStayUnmatchedWithoutLooping() {
        var repo = TempRepo.at(tmp.resolve("odd-poms"))
                .file("pom.xml", """
                        <project><groupId>g</groupId><artifactId>root</artifactId><version>1</version>
                        <properties><loop>${loop}</loop><lib.version>2</lib.version></properties>
                        <dependencyManagement><dependencies>
                          <dependency><groupId>h</groupId><artifactId>util</artifactId><version>1</version></dependency>
                          <dependency><groupId>g</groupId><artifactId>other</artifactId><version>1</version></dependency>
                          <dependency><groupId>${pom.groupId}</groupId><artifactId>util</artifactId><version>${lib.version}</version></dependency>
                        </dependencies></dependencyManagement></project>
                        """)
                .file("v1/core/pom.xml", "<project><groupId>g</groupId><artifactId>core</artifactId><version>1</version></project>")
                .file("v2/core/pom.xml", "<project><groupId>g</groupId><artifactId>core</artifactId><version>2</version></project>")
                .file("v1/util/pom.xml", "<project><groupId>g</groupId><artifactId>util</artifactId><version>1</version></project>")
                .file("v2/util/pom.xml", "<project><groupId>g</groupId><artifactId>util</artifactId><version>2</version></project>")
                .file("agg/pom.xml", "<project><modules><module>x</module></modules></project>")
                .file("templ/pom.xml", "<project><groupId>${unknown}</groupId><artifactId>templ</artifactId></project>")
                .file("nover/pom.xml", "<project><groupId>k</groupId><artifactId>nover</artifactId></project>")
                .file("app/pom.xml", """
                        <project><parent><groupId>g</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>app</artifactId>
                        <dependencies>
                          <dependency><groupId>g</groupId><artifactId>util</artifactId></dependency>
                          <dependency><groupId>g</groupId><artifactId>core</artifactId><version>${pom.version}</version></dependency>
                          <dependency><groupId>g</groupId><artifactId>core</artifactId><version>9</version></dependency>
                          <dependency><groupId>g</groupId><artifactId>app</artifactId></dependency>
                          <dependency><groupId>k</groupId><artifactId>nover</artifactId></dependency>
                          <dependency><groupId>zzz</groupId><artifactId>nover</artifactId></dependency>
                          <dependency><groupId>q</groupId><artifactId>templ</artifactId></dependency>
                          <dependency><groupId>${loop}</groupId><artifactId>unknown-x</artifactId></dependency>
                        </dependencies></project>
                        """)
                // parents naming each other: lookups through the parent chain must stop
                .file("cyc-a/pom.xml", """
                        <project><parent><groupId>g</groupId><artifactId>cyc-b</artifactId></parent><artifactId>cyc-a</artifactId>
                        <dependencies><dependency><groupId>g</groupId><artifactId>util</artifactId></dependency>
                        <dependency><groupId>${missing}</groupId><artifactId>x</artifactId></dependency></dependencies></project>
                        """)
                .file("cyc-b/pom.xml", "<project><parent><groupId>g</groupId><artifactId>cyc-a</artifactId></parent><artifactId>cyc-b</artifactId></project>");
        Path root = repo.root().toAbsolutePath().normalize();
        var modules = Modules.read(Repo.of(root));

        assertThat(modules.direct(root.resolve("app")))
                .contains(root.resolve("v2/util"), root.resolve("v1/core"), root.resolve("nover"), root.resolve("templ"))
                .doesNotContain(root.resolve("app"), root.resolve("agg"))
                .hasSizeLessThanOrEqualTo(5);
        assertThat(modules.all(root.resolve("cyc-a"), java.util.Set.of())).hasSize(1);
    }

    @Test
    void libraryBeanOutsideTheScannedPackagesIsNotLoaded() {
        assertThat(loaded(lib("scan"))).contains("App.java", "InScan.java").doesNotContain("OutOfScan.java");
    }

    @Test
    void importedConfigurationsAndTheirOwnScansAreLoaded() {
        var repo = lib("import")
                .file("system-start/src/main/java/start/App.java", """
                        package start;
                        import other.Direct;
                        @SpringBootApplication @EnableExtras @Import({Direct.class, Missing.class, int.class, CONFIGS}) class App {}
                        """)
                .file("system-start/src/main/java/start/EnableExtras.java", """
                        package start;
                        import other.Imported;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.RUNTIME)
                        @EnableExtras @Import(Imported.class) @interface EnableExtras {}
                        """)
                .file("lib/src/main/java/other/Direct.java", "package other; @Configuration class Direct {}")
                .file("lib/src/main/java/other/Imported.java", "package other; @Configuration @ComponentScan(\"other.scan\") class Imported {}")
                .file("lib/src/main/java/other/scan/Chained.java", "package other.scan; @Component class Chained {}");

        assertThat(loaded(repo)).contains("Direct.java", "Imported.java", "Chained.java").doesNotContain("OutOfScan.java");
    }

    @ParameterizedTest
    @ValueSource(strings = {"value", "locations"})
    @Timeout(10)
    void importedXmlRegistersUnannotatedLibraryBeansAndTheirJdbcReachThroughRelativeImportCycles(String attribute) {
        var repo = TempRepo.at(tmp.resolve("xml-beans-" + attribute))
                .file("system-start/pom.xml", pom("system-start", "lib"))
                .file("system-start/src/main/resources/application.properties",
                        "spring.application.name=system\nxml.context=classpath:/spring/root.xml\n")
                .file("system-start/src/main/java/start/App.java", """
                        package start;
                        import configuration.CoreConfiguration;
                        @SpringBootApplication @Import(CoreConfiguration.class) class App {}
                        """)
                .file("system-start/src/main/java/start/Controller.java", """
                        package start;
                        import shared.UserService;
                        @RestController class Controller {
                            UserService users;
                            @GetMapping("/xml") String get() { return users.load(); }
                        }
                        """)
                .file("system-start/src/main/resources/spring/root.xml", """
                        <beans xmlns="http://www.springframework.org/schema/beans">
                          <import resource="classpath:/spring/library.xml"/>
                        </beans>
                        """)
                .file("lib/pom.xml", pom("lib"))
                .file("lib/src/main/java/configuration/CoreConfiguration.java", """
                        package configuration;
                        @Configuration @ImportResource(%s = "${xml.context}") public class CoreConfiguration {}
                        """.formatted(attribute))
                .file("lib/src/main/resources/spring/library.xml", """
                        <beans:beans xmlns:beans="http://www.springframework.org/schema/beans"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          xsi:schemaLocation="http://www.springframework.org/schema/beans https://example.invalid/beans.xsd">
                          <beans:import resource="beans.xml"/>
                        </beans:beans>
                        """)
                .file("lib/src/main/resources/spring/beans.xml", """
                        <beans>
                          <bean class="shared.UserServiceImpl"/>
                          <import resource="./library.xml"/>
                        </beans>
                        """)
                .file("lib/src/main/java/shared/UserService.java",
                        "package shared; public interface UserService { String load(); }")
                .file("lib/src/main/java/shared/UserServiceImpl.java", """
                        package shared;
                        public class UserServiceImpl implements UserService {
                            JdbcTemplate jdbc;
                            public String load() { return jdbc.queryForObject("select * from xml_users", String.class); }
                        }
                        """)
                .file("lib/src/main/java/shared/UnusedImplementation.java", """
                        package shared;
                        public class UnusedImplementation implements UserService {
                            JdbcTemplate jdbc;
                            public String load() { return jdbc.queryForObject("select * from unrelated_users", String.class); }
                        }
                        """);

        assertThat(loaded(repo)).contains("CoreConfiguration.java", "UserServiceImpl.java")
                .doesNotContain("UnusedImplementation.java");
        assertThat(edges(repo, Edge.Kind.REACHES)).containsExactly("endpoint:system:GET /xml -> table:system/xml_users");
    }

    @Test
    void importedXmlCannotLoadEscapedExternalMalformedOrUnrelatedResources() {
        var repo = TempRepo.at(tmp.resolve("xml-resource-guards"))
                .file("system-start/pom.xml", pom("system-start", "lib"))
                .file("system-start/src/main/resources/application.properties", "spring.application.name=system\n")
                .file("system-start/src/main/java/start/App.java", """
                        package start;
                        @SpringBootApplication
                        @ImportResource({"classpath:/spring/root.xml", "classpath:/../outside.xml",
                          "file:/outside.xml", "https://example.invalid/outside.xml"}) class App {}
                        """)
                .file("lib/pom.xml", pom("lib"))
                .file("lib/src/main/java/shared/SafeBean.java", "package shared; public class SafeBean {}")
                .file("lib/src/main/resources/spring/root.xml", """
                        <beans xmlns:foreign="urn:unrelated">
                          <bean class="shared.SafeBean"/>
                          <foreign:bean class="forbidden.ForeignBean"/>
                          <import resource="../../outside.xml"/>
                          <import resource="classpath:/spring/xxe.xml"/>
                          <import resource="broken.xml"/>
                          <import resource="missing.xml"/>
                          <import resource="https://example.invalid/outside.xml"/>
                        </beans>
                        """)
                .file("lib/src/main/outside.xml", "<beans><bean class=\"forbidden.EscapedBean\"/></beans>")
                .file("external-entity.xml", "<bean class=\"forbidden.ExternalEntityBean\"/>")
                .file("lib/src/main/resources/spring/broken.xml", "<beans><bean class=\"forbidden.MalformedBean\"></beans>")
                .file("lib/src/main/resources/spring/unrelated.xml", "<beans><bean class=\"forbidden.UnrelatedResourceBean\"/></beans>")
                .file("lib/src/test/resources/spring/root.xml", "<beans><bean class=\"forbidden.TestResourceBean\"/></beans>")
                .file("unused/pom.xml", pom("unused"))
                .file("unused/src/main/resources/spring/root.xml", "<beans><bean class=\"forbidden.UnrelatedModuleBean\"/></beans>");
        repo.file("lib/src/main/resources/spring/xxe.xml", """
                <!DOCTYPE beans [<!ENTITY external SYSTEM "%s">]>
                <beans><bean class="forbidden.DoctypeBean"/>&external;</beans>
                """.formatted(repo.root().resolve("external-entity.xml").toUri()));
        for (String name : List.of("ForeignBean", "EscapedBean", "ExternalEntityBean", "MalformedBean",
                "UnrelatedResourceBean", "TestResourceBean", "UnrelatedModuleBean", "DoctypeBean")) {
            repo.file("lib/src/main/java/forbidden/" + name + ".java", "package forbidden; public class " + name + " {}");
        }

        assertThat(loaded(repo)).containsExactlyInAnyOrder("App.java", "SafeBean.java");
    }

    @Test
    void scanPackagesResolvePlaceholdersAndAntGlobs() {
        var repo = lib("glob")
                .file("system-start/src/main/resources/application.properties", "spring.application.name=system\nbase=start\n")
                .file("system-start/src/main/java/start/App.java", """
                        package start;
                        @SpringBootApplication(scanBasePackages = {"${base}.lib", "x.*.mapper", "y.**.dao"}) class App {}
                        """)
                .file("lib/src/main/java/x/a/mapper/One.java", "package x.a.mapper; @Mapper interface One {}")
                .file("lib/src/main/java/x/a/b/mapper/Two.java", "package x.a.b.mapper; @Mapper interface Two {}")
                .file("lib/src/main/java/y/dao/Three.java", "package y.dao; @Repository class Three {}")
                .file("lib/src/main/java/y/a/b/dao/Four.java", "package y.a.b.dao; @Repository class Four {}");

        assertThat(loaded(repo)).contains("InScan.java", "One.java", "Three.java", "Four.java")
                .doesNotContain("Two.java", "OutOfScan.java");
    }

    @Test
    void explicitComponentScanReplacesTheApplicationPackage() {
        var repo = lib("explicit")
                .file("system-start/src/main/java/start/App.java", "package start; @SpringBootApplication @ComponentScan class App {}")
                .file("system-start/src/main/java/start/Extra.java", "package start; @Configuration @ComponentScan(basePackages = \"other\") class Extra {}");

        assertThat(loaded(repo)).contains("InScan.java", "OutOfScan.java");
    }

    @Test
    void unreadableOrMissingScanInfoFallsBackToEveryBean() {
        for (String app : List.of(
                "@SpringBootApplication(scanBasePackages = Pkgs.BASE) class App {}",
                "@SpringBootApplication(scanBasePackages = \"${nope}\") class App {}",
                "@SpringBootApplication(scanBasePackageClasses = Missing.class) class App {}",
                "@SpringBootApplication @ComponentScan(basePackageClasses = Missing.class) class App {}",
                "@SpringBootApplication @ComponentScan(basePackageClasses = Pkgs.CLASSES) class App {}",
                "class App { public static void main(String[] a) { SpringApplication.run(App.class, a); } }")) {
            var repo = lib("fallback" + app.hashCode())
                    .file("system-start/src/main/java/start/App.java", "package start; " + app);

            assertThat(loaded(repo)).as(app).contains("InScan.java", "OutOfScan.java");
        }
    }

    /** A launcher and another service, for checking which service receives a shared library. */
    private TempRepo twoApps(String name) {
        return TempRepo.at(tmp.resolve(name))
                .file("system-start/src/main/java/start/App.java", "package start; @SpringBootApplication class App {}")
                .file("system-start/src/main/resources/application.properties", "spring.application.name=system\n")
                .file("users/src/main/java/users/App.java", "package users; @SpringBootApplication class App {}")
                .file("users/src/main/java/users/UserRpc.java", """
                        package users;
                        @RestController class UserRpc { @GetMapping("/rpc/users/{id}") String get(@PathVariable long id) { return ""; } }
                        """)
                .file("users/src/main/resources/application.properties", "spring.application.name=users\n");
    }

    private static String pom(String artifact, String... deps) {
        var body = new StringBuilder();
        for (String d : deps) body.append(d.contains("<") ? d : "<dependency><artifactId>" + d + "</artifactId></dependency>\n");
        return "<project><parent><artifactId>root</artifactId></parent><artifactId>" + artifact
                + "</artifactId><dependencies>" + body + "</dependencies></project>\n";
    }

    /** The system app on a library with one bean under its package and one outside it. */
    private TempRepo lib(String name) {
        return twoApps(name)
                .file("system-start/pom.xml", pom("system-start", "lib"))
                .file("lib/pom.xml", pom("lib"))
                .file("lib/src/main/java/start/lib/InScan.java", "package start.lib; @Service class InScan {}")
                .file("lib/src/main/java/other/OutOfScan.java", "package other; @Service class OutOfScan {}");
    }

    /** Auto-configuration presence keeps the old all-bean fallback from hiding an unread programmatic boot root. */
    private TempRepo bootSources(String name, String launcher) {
        return TempRepo.at(tmp.resolve(name))
                .file("system-start/pom.xml", pom("system-start", "lib"))
                .file("system-start/src/main/resources/application.properties", "spring.application.name=system\n")
                .file("system-start/src/main/java/launch/App.java", launcher)
                .file("lib/pom.xml", pom("lib"))
                .file("lib/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                        "boot.BootAutoConfiguration\n")
                .file("lib/src/main/java/boot/BootAutoConfiguration.java",
                        "package boot; @AutoConfiguration class BootAutoConfiguration {}")
                .file("lib/src/main/java/boot/RuntimeConfiguration.java",
                        "package boot; @Configuration @ComponentScan(\"runtime\") public class RuntimeConfiguration {}")
                .file("lib/src/main/java/boot/UnusedConfiguration.java",
                        "package boot; @Configuration @ComponentScan(\"unused\") public class UnusedConfiguration {}")
                .file("lib/src/main/java/runtime/ActiveController.java", """
                        package runtime;
                        @RestController class ActiveController { @GetMapping("/active") void get() {} }
                        """)
                .file("lib/src/main/java/unused/UnusedController.java", """
                        package unused;
                        @RestController class UnusedController { @GetMapping("/unused") void get() {} }
                        """);
    }

    private TempRepo persistence(String name, String configuration) {
        return TempRepo.at(tmp.resolve(name))
                .file("system-start/pom.xml", pom("system-start", "lib"))
                .file("system-start/src/main/resources/application.properties", "spring.application.name=system\n")
                .file("system-start/src/main/java/launch/App.java",
                        "package launch; @SpringBootApplication(scanBasePackages=\"configuration\") class App {}")
                .file("lib/pom.xml", pom("lib"))
                .file("lib/src/main/java/configuration/PersistenceConfiguration.java", configuration)
                .file("lib/src/main/java/runtime/entities/ActiveEntity.java",
                        "package runtime.entities; @Entity @Table(name=\"active_table\") public class ActiveEntity {}")
                .file("lib/src/main/java/runtime/entities/DetachedEntity.java",
                        "package runtime.entities; @Entity @Table(name=\"detached_table\") class DetachedEntity {}")
                .file("lib/src/main/java/unused/entities/UnusedEntity.java",
                        "package unused.entities; @Entity @Table(name=\"unused_table\") public class UnusedEntity {}");
    }

    private static List<String> loaded(TempRepo repo) {
        Service s = repo.service("system");
        return s.units().stream().map(cu -> s.index().fileOf(cu).getFileName().toString()).toList();
    }

    private static List<String> edges(TempRepo repo, Edge.Kind kind) {
        return TempRepo.scan(repo).edges().stream().filter(e -> e.kind() == kind).map(e -> e.from() + " -> " + e.to()).toList();
    }
}
