package io.github.sunjirapath.ssg;

import io.github.sunjirapath.ssg.diff.GraphDiff;
import io.github.sunjirapath.ssg.model.Edge;
import io.github.sunjirapath.ssg.model.Finding;
import io.github.sunjirapath.ssg.model.Graph;
import io.github.sunjirapath.ssg.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regressions for the eight independently reproduced public-corpus audit findings. */
class AuditFixesTest {
    private static final Path FIXTURES = Path.of("docs/audits/github40-2026-10-23/fixtures");

    @TempDir Path tmp;

    @Test
    void applicationInterfacesInSpringSamplesUseOpenApiFallback() {
        Graph graph = scan("openapi-framework-prefix");
        assertThat(graph.nodes()).filteredOn(Node.Endpoint.class::isInstance)
                .extracting(n -> ((Node.Endpoint) n).path()).contains("/petclinic/api/owners");
        assertThat(graph.findings()).noneMatch(f -> f.kind() == Finding.Kind.MISSING_INTERFACE);
    }

    @Test
    void absoluteOpenApiServerPathsDoNotRepeatTheServletContext() {
        var repo = TempRepo.at(tmp.resolve("petclinic")).app()
                .java("demo/Owners.java", """
                        package demo;
                        import org.springframework.samples.petclinic.api.OwnersApi;
                        @RestController class Owners implements OwnersApi {}
                        """)
                .resource("application.properties", "server.servlet.context-path=/petclinic/\n")
                .resource("openapi.yml", """
                        openapi: 3.0.1
                        servers:
                          - url: http://localhost:9966/petclinic/api
                        paths:
                          /owners:
                            get:
                              tags: [owners]
                              responses: {}
                        """);
        assertThat(TempRepo.scan(repo).nodes()).filteredOn(Node.Endpoint.class::isInstance)
                .extracting(n -> ((Node.Endpoint) n).path()).containsExactly("/petclinic/api/owners");
    }

    @Test
    void elasticsearchUsesTheExplicitIndexName() {
        assertThat(scan("elasticsearch-index").nodes()).filteredOn(Node.Table.class::isInstance)
                .extracting(n -> ((Node.Table) n).name()).containsExactly("catalog");
    }

    @Test
    void mongoRepositoryInfersAnUnannotatedDomainsCollection() {
        Graph graph = scan("mongo-default");
        assertThat(graph.nodes()).filteredOn(Node.Table.class::isInstance)
                .extracting(n -> ((Node.Table) n).name()).containsExactly("customer");
        assertThat(graph.findings()).isEmpty();
    }

    @Test
    void mongoRepositorySubstitutesReorderedGenericBaseParameters() {
        var repo = TempRepo.at(tmp.resolve("mongo")).app().java("demo/CustomerRepository.java", """
                package demo;
                class Customer {}
                @NoRepositoryBean interface BaseMongo<ID, T> extends MongoRepository<T, ID> {}
                interface CustomerRepository extends BaseMongo<String, Customer> {}
                """);
        assertThat(TempRepo.scan(repo).nodes()).filteredOn(Node.Table.class::isInstance)
                .extracting(n -> ((Node.Table) n).name()).containsExactly("customer");
    }

    @Test
    void rabbitPostProcessorOverloadKeepsRoutingKeyFirst() {
        assertThat(scan("rabbit-overload").nodes()).filteredOn(Node.Topic.class::isInstance)
                .extracting(n -> ((Node.Topic) n).name()).containsExactly("orders.key");
    }

    @Test
    void rabbitCorrelationAndDefaultDestinationOverloadsDoNotUseThePayload() {
        var repo = TempRepo.at(tmp.resolve("rabbit")).app().java("demo/Sender.java", """
                package demo;
                class Sender {
                    RabbitTemplate rabbit;
                    void send(MessagePostProcessor processor, CorrelationData correlation, Message message) {
                        rabbit.convertAndSend("convert.key", "payload", correlation);
                        rabbit.convertAndSend("processed.key", "payload", processor, correlation);
                        rabbit.send("raw.key", message, correlation);
                        rabbit.convertAndSend("payload", processor);
                    }
                }
                """);
        Graph graph = TempRepo.scan(repo);
        assertThat(graph.nodes()).filteredOn(Node.Topic.class::isInstance)
                .extracting(n -> ((Node.Topic) n).name())
                .contains("convert.key", "processed.key", "raw.key").doesNotContain("payload");
        assertThat(graph.findings()).anyMatch(f -> f.kind() == Finding.Kind.UNRESOLVED);
    }

    @Test
    void rootUrlQueryLinksToTheService() {
        Graph graph = TempRepo.scan(fixture("url-query/caller"), fixture("url-query/target"));
        assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.CALLS)
                .extracting(Edge::to).containsExactly("endpoint:target:GET /");
    }

    @Test
    void enabledFeignPackagesIncludeUnreferencedDependencyClients() {
        assertThat(scan("feign-package").edges()).filteredOn(e -> e.kind() == Edge.Kind.CALLS)
                .extracting(Edge::to).containsExactly("endpoint:users:GET /users/{id}");
    }

    @Test
    void mavenAndGradleBoot4LayoutsKeepFeignAndComponentScansSeparate() {
        for (boolean gradle : List.of(false, true)) {
            var repo = clientFleet(gradle, "@EnableFeignClients(basePackages = \"clients\")");
            Graph graph = TempRepo.scan(repo);
            assertThat(graph.edges()).filteredOn(e -> e.kind() == Edge.Kind.CALLS)
                    .singleElement().satisfies(e -> {
                        assertThat(e.from()).isEqualTo("service:orders");
                        assertThat(e.to()).isEqualTo("endpoint:users:GET /users/{id}");
                    });
        }
    }

    @Test
    void explicitFeignClientsReplaceThePackageScan() {
        var repo = clientFleet(false, "@EnableFeignClients(clients = clients.UsersApi.class, basePackages = \"clients\")")
                .file("shared/src/main/java/clients/OtherApi.java", """
                        package clients; @FeignClient(name="other")
                        interface OtherApi { @GetMapping("/other") Object get(); }
                        """);
        assertThat(TempRepo.scan(repo).edges()).filteredOn(e -> e.kind() == Edge.Kind.CALLS)
                .extracting(Edge::to).containsExactly("endpoint:users:GET /users/{id}");
    }

    @Test
    void composedFeignAliasesUseTheApplicationOverride() {
        var repo = clientFleet(false, "@RemoteClients(basePackages = \"clients\")")
                .file("orders/src/main/java/app/RemoteClients.java", """
                        package app;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.RUNTIME)
                        @EnableFeignClients(basePackages="wrong")
                        @interface RemoteClients {
                            @AliasFor(annotation=EnableFeignClients.class, attribute="basePackages")
                            String[] basePackages() default {"unused"};
                        }
                        """)
                .file("shared/src/main/java/wrong/WrongApi.java", """
                        package wrong; @FeignClient(name="wrong")
                        interface WrongApi { @GetMapping("/wrong") Object get(); }
                        """);
        assertThat(TempRepo.scan(repo).edges()).filteredOn(e -> e.kind() == Edge.Kind.CALLS)
                .extracting(Edge::to).containsExactly("endpoint:users:GET /users/{id}");
    }

    @Test
    void composedFeignAliasesPreserveIndependentMetaPackages() {
        var repo = clientFleet(false, "@RemoteClients(basePackages = \"unused\")")
                .file("orders/src/main/java/app/RemoteClients.java", """
                        package app;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.RUNTIME)
                        @EnableFeignClients("clients")
                        @interface RemoteClients {
                            @AliasFor(annotation=EnableFeignClients.class, attribute="basePackages")
                            String[] basePackages() default {};
                        }
                        """);
        assertThat(TempRepo.scan(repo).edges()).filteredOn(e -> e.kind() == Edge.Kind.CALLS)
                .extracting(Edge::to).containsExactly("endpoint:users:GET /users/{id}");
    }

    @Test
    void pairedComposedFeignAliasesKeepTheExplicitValueAndBasePackages() {
        for (String usage : List.of("@RemoteClients(value = \"clients\")",
                "@RemoteClients(basePackages = \"clients\")")) {
            var repo = clientFleet(false, usage)
                    .file("orders/src/main/java/app/RemoteClients.java", """
                            package app;
                            import java.lang.annotation.*;
                            @Retention(RetentionPolicy.RUNTIME)
                            @EnableFeignClients
                            @interface RemoteClients {
                                @AliasFor(annotation=EnableFeignClients.class, attribute="value")
                                String[] value() default {};
                                @AliasFor(annotation=EnableFeignClients.class, attribute="basePackages")
                                String[] basePackages() default {};
                            }
                            """);

            assertThat(TempRepo.scan(repo).edges()).filteredOn(e -> e.kind() == Edge.Kind.CALLS)
                    .extracting(Edge::to).containsExactly("endpoint:users:GET /users/{id}");
        }
    }

    @Test
    void composedFeignRegistrationRequiresRuntimeRetention() {
        for (String retention : List.of("", "@Retention(RetentionPolicy.CLASS)",
                "@Retention(RetentionPolicy.SOURCE)")) {
            var repo = clientFleet(false, "@RemoteClients(basePackages = \"clients\")")
                    .file("orders/src/main/java/app/RemoteClients.java", """
                            package app;
                            import java.lang.annotation.*;
                            %s
                            @EnableFeignClients
                            @interface RemoteClients {
                                @AliasFor(annotation=EnableFeignClients.class, attribute="basePackages")
                                String[] basePackages() default {};
                            }
                            """.formatted(retention));

            assertThat(TempRepo.scan(repo).edges()).noneMatch(e -> e.kind() == Edge.Kind.CALLS);
        }

        var runtime = clientFleet(false, "@RemoteClients(basePackages = \"clients\")")
                .file("orders/src/main/java/app/RemoteClients.java", """
                        package app;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.RUNTIME)
                        @EnableFeignClients
                        @interface RemoteClients {
                            @AliasFor(annotation=EnableFeignClients.class, attribute="basePackages")
                            String[] basePackages() default {};
                        }
                        """);
        assertThat(TempRepo.scan(runtime).edges()).filteredOn(e -> e.kind() == Edge.Kind.CALLS)
                .extracting(Edge::to).containsExactly("endpoint:users:GET /users/{id}");
    }

    @Test
    void feignPackageMarkerClassSelectsOnlyItsPackage() {
        var repo = clientFleet(true, "@EnableFeignClients(basePackageClasses = clients.UsersApi.class)");
        assertThat(TempRepo.scan(repo).edges()).filteredOn(e -> e.kind() == Edge.Kind.CALLS)
                .extracting(Edge::to).containsExactly("endpoint:users:GET /users/{id}");
    }

    @Test
    void feignEnableOnAnUnscannedConfigurationDoesNotRegisterClients() {
        var repo = clientFleet(false, "")
                .file("orders/src/main/java/isolated/RemoteConfig.java", """
                        package isolated; @Configuration @EnableFeignClients(basePackages="clients")
                        class RemoteConfig {}
                        """);
        assertThat(TempRepo.scan(repo).edges()).noneMatch(e -> e.kind() == Edge.Kind.CALLS);
        repo.file("orders/src/main/java/app/Orders.java",
                "package app; @SpringBootApplication @Import(isolated.RemoteConfig.class) class Orders {}");
        assertThat(TempRepo.scan(repo).edges()).filteredOn(e -> e.kind() == Edge.Kind.CALLS)
                .extracting(Edge::to).containsExactly("endpoint:users:GET /users/{id}");
    }

    @Test
    void removingBothCallAndEndpointDoesNotBreakASurvivingService() {
        Graph before = TempRepo.scan(fixture("retirement/base/caller"), fixture("retirement/base/orders"));
        Graph after = TempRepo.scan(fixture("retirement/head/caller"), fixture("retirement/head/orders"));
        assertThat(GraphDiff.compare(before, after).breaking()).isEmpty();
    }

    @Test
    void duplicateServiceEvidenceNamesActualFilesInBothCheckouts() {
        var alpha = fixture("duplicate-evidence/alpha");
        var beta = fixture("duplicate-evidence/beta");
        Graph graph = TempRepo.scan(alpha, beta);
        assertThat(graph.findings()).filteredOn(f -> f.kind() == Finding.Kind.DUPLICATE_SERVICE)
                .singleElement().satisfies(f -> {
                    assertThat(f.evidence()).hasSize(2).allSatisfy(e -> {
                        Path root = e.repo().equals("alpha") ? alpha.root() : beta.root();
                        assertThat(Files.isRegularFile(root.resolve(e.file()))).isTrue();
                        assertThat(e.line()).isPositive();
                    });
                });
    }

    @Test
    void duplicateInferredNamesPointToLauncherFiles() {
        var alpha = TempRepo.at(tmp.resolve("a")).file("same/src/main/java/app/App.java",
                "package app; @SpringBootApplication class App {}");
        var beta = TempRepo.at(tmp.resolve("b")).file("same/src/main/java/app/App.java",
                "package app; @SpringBootApplication class App {}");
        assertThat(TempRepo.scan(alpha, beta).findings()).filteredOn(f -> f.kind() == Finding.Kind.DUPLICATE_SERVICE)
                .singleElement().satisfies(f -> assertThat(f.evidence()).hasSize(2)
                        .allSatisfy(e -> assertThat(e.file()).isEqualTo("same/src/main/java/app/App.java")));
    }

    @Test
    void distinctCheckoutRootsCannotShareAnEvidenceName() {
        var alpha = TempRepo.at(tmp.resolve("a/same")).app();
        var beta = TempRepo.at(tmp.resolve("b/same")).app();
        assertThatThrownBy(() -> TempRepo.scan(alpha, beta))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("repository name 'same' is ambiguous");
        assertThatThrownBy(() -> TempRepo.scan(new Pipeline.Options(List.of(alpha.root()), "default", null, beta.root())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("repository name 'same' is ambiguous");
    }

    @Test
    void configSearchPathsCannotReadSiblingFiles() {
        var config = TempRepo.at(tmp.resolve("config-repo"));
        TempRepo.at(tmp.resolve("outside")).file("orders.yml", "server.servlet.context-path: /escaped\n");
        for (String search : List.of("../outside", "../outside/*", "/../outside")) {
            var repo = TempRepo.at(tmp.resolve("fleet"))
                    .file("config/src/main/java/config/App.java", "package config; @SpringBootApplication @EnableConfigServer class App {}")
                    .file("config/src/main/resources/application.properties", "spring.application.name=config\nspring.cloud.config.server.git.search-paths=" + search + "\n")
                    .file("orders/src/main/java/orders/App.java", "package orders; @SpringBootApplication @RestController class App { @GetMapping(\"/x\") Object x() { return null; } }")
                    .file("orders/src/main/resources/application.properties", "spring.application.name=orders\n");
            Graph graph = TempRepo.scan(new Pipeline.Options(List.of(repo.root()), "default", null, config.root()));
            assertThat(graph.nodes()).filteredOn(Node.Endpoint.class::isInstance)
                    .extracting(n -> ((Node.Endpoint) n).path()).containsExactly("/x");
        }
    }

    private TempRepo clientFleet(boolean gradle, String enable) {
        var repo = TempRepo.at(tmp.resolve(gradle ? "gradle" : "maven"))
                .file("orders/src/main/java/app/Orders.java", "package app; @SpringBootApplication " + enable + " class Orders {}")
                .file("orders/src/main/resources/application.properties", "spring.application.name=orders\n")
                .file("users/src/main/java/users/Users.java", """
                        package users; @SpringBootApplication @RestController class Users {
                            @GetMapping("/users/{id}") Object get() { return null; }
                        }
                        """)
                .file("users/src/main/resources/application.properties", "spring.application.name=users\n")
                .file("shared/src/main/java/clients/UsersApi.java", """
                        package clients; @FeignClient(name="users")
                        public interface UsersApi { @GetMapping("/users/{id}") Object get(); }
                        """)
                .file("shared/src/main/java/clients/Unregistered.java", """
                        package clients; @Service class Unregistered {
                            RestTemplate rest;
                            void send() { rest.getForObject("https://unexpected.example/", Object.class); }
                        }
                        """);
        if (gradle) return repo.file("settings.gradle", "rootProject.name='fleet'\ninclude 'orders', 'users', 'shared'\n")
                .file("orders/build.gradle", "plugins { id 'org.springframework.boot' version '4.0.0' }\ndependencies { implementation project(':shared') }\n")
                .file("shared/build.gradle", "plugins { id 'java-library' }\n");
        return repo.file("orders/pom.xml", """
                <project><parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId>
                <version>4.0.0</version></parent><groupId>demo</groupId><artifactId>orders</artifactId>
                <dependencies><dependency><groupId>demo</groupId><artifactId>shared</artifactId></dependency></dependencies></project>
                """).file("shared/pom.xml", "<project><groupId>demo</groupId><artifactId>shared</artifactId></project>");
    }

    private static TempRepo fixture(String name) { return TempRepo.at(FIXTURES.resolve(name)); }
    private static Graph scan(String name) { return TempRepo.scan(fixture(name)); }
}
