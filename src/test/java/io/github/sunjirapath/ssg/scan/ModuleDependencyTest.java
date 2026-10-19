package io.github.sunjirapath.ssg.scan;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Edge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Library modules (no app of their own) count for the services whose pom.xml or build.gradle depends on them. */
class ModuleDependencyTest {

    @TempDir
    Path tmp;

    @Test
    void controllersCountOnlyFromModulesTheLauncherDependsOnDirectly() {
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

        assertThat(edges(repo, Edge.Kind.EXPOSES)).contains("service:system -> endpoint:system:GET /sys/user")
                .noneMatch(e -> e.contains("/core/health"));
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
                .file("dao/src/main/java/dao/DaoConfig.java", "package dao; @AutoConfiguration @MapperScan(\"dao\") class DaoConfig {}")
                .file("dao/src/main/resources/META-INF/spring.factories",
                        "org.springframework.boot.autoconfigure.EnableAutoConfiguration=\\\n  dao.DaoConfig\n")
                .file("dao/src/main/resources/db/schema.sql", "create table accounts (id bigint primary key);\n")
                .file("dao/src/main/java/dao/AuditMapper.java", "package dao; @Mapper public interface AuditMapper { }")
                .file("dao/src/main/java/dao/AuditMapper.xml", """
                        <mapper namespace="dao.AuditMapper"><select id="all">select * from audit</select></mapper>
                        """)
                // generated mapper nobody injects: its XML must not give every dependent a USES edge
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
                        @EnableExtras @Import(Imported.class) @interface EnableExtras {}
                        """)
                .file("lib/src/main/java/other/Direct.java", "package other; @Configuration class Direct {}")
                .file("lib/src/main/java/other/Imported.java", "package other; @Configuration @ComponentScan(\"other.scan\") class Imported {}")
                .file("lib/src/main/java/other/scan/Chained.java", "package other.scan; @Component class Chained {}");

        assertThat(loaded(repo)).contains("Direct.java", "Imported.java", "Chained.java").doesNotContain("OutOfScan.java");
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
                "@SpringBootApplication(scanBasePackageClasses = App.class) class App {}",
                "@SpringBootApplication @ComponentScan(basePackageClasses = App.class) class App {}",
                "class App { public static void main(String[] a) { SpringApplication.run(App.class, a); } }")) {
            var repo = lib("fallback" + app.hashCode())
                    .file("system-start/src/main/java/start/App.java", "package start; " + app);

            assertThat(loaded(repo)).as(app).contains("InScan.java", "OutOfScan.java");
        }
    }

    /** Two apps, so attribution goes through the module walk rather than "one app gets everything". */
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

    private static List<String> loaded(TempRepo repo) {
        Service s = repo.service("system");
        return s.units().stream().map(cu -> s.index().fileOf(cu).getFileName().toString()).toList();
    }

    private static List<String> edges(TempRepo repo, Edge.Kind kind) {
        return TempRepo.scan(repo).edges().stream().filter(e -> e.kind() == kind).map(e -> e.from() + " -> " + e.to()).toList();
    }
}
