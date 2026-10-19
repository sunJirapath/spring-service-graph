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
                .file("system-biz/src/main/java/biz/UserController.java", """
                        package biz;
                        @RestController class UserController { @GetMapping("/sys/user") String list() { return ""; } }
                        """)
                .file("system-core/pom.xml", pom("system-core"))
                .file("system-core/src/main/java/core/HealthController.java", """
                        package core;
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
                .file("biz/src/main/java/biz/Lookup.java", """
                        package biz;
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

    private static List<String> edges(TempRepo repo, Edge.Kind kind) {
        return TempRepo.scan(repo).edges().stream().filter(e -> e.kind() == kind).map(e -> e.from() + " -> " + e.to()).toList();
    }
}
