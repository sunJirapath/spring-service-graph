package io.github.sunjirapath.ssg.scan;

import io.github.sunjirapath.ssg.TempRepo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryBranchGap2Test {

    @TempDir
    Path tmp;

    @Test
    void persistenceEntityPackagesReadListAndSetFactoriesAndZeroArgumentHelpers() {
        var repo = TempRepo.at(tmp.resolve("entity-factories"))
                .file("src/main/java/start/App.java", """
                        package start;
                        import java.util.List;
                        import java.util.Set;
                        @SpringBootApplication(scanBasePackages="none") class App {
                            EntityManagerFactoryBuilder builder() { return null; }
                            @Bean Object factory() {
                                builder().packages(Set.of(one.Entities.class).toArray(String[]::new));
                                builder().packages(List.of(two.Entities.class).toArray(String[]::new));
                                builder().packages(roots());
                                return null;
                            }
                            Set<Class<?>> roots() { return Set.of(three.Entities.class); }
                        }
                        """)
                .file("src/main/java/one/Entities.java", "package one; @Entity class Entities {}")
                .file("src/main/java/two/Entities.java", "package two; @Entity class Entities {}")
                .file("src/main/java/three/Entities.java", "package three; @Entity class Entities {}");

        var service = repo.service();
        assertThat(service.units().stream().map(cu -> service.index().fileOf(cu).getFileName().toString()).toList())
                .containsExactlyInAnyOrder("App.java", "Entities.java", "Entities.java", "Entities.java");
    }

    @Test
    void searchPathGlobIgnoresMatchingSymlinkDirectoriesThatEscapeTheConfigRoot() throws IOException {
        var repo = TempRepo.at(tmp.resolve("services"))
                .file("orders/src/main/java/orders/App.java", "package orders; @SpringBootApplication class App {}")
                .file("orders/src/main/resources/application.yml", "spring.application.name: orders\n")
                .file("server/src/main/java/server/Server.java", "package server; @SpringBootApplication @EnableConfigServer class Server {}")
                .file("server/src/main/resources/application.yml", """
                        spring.application.name: config-server
                        spring.cloud.config.server.git.search-paths: locations/team-*
                        """);
        var configRepo = TempRepo.at(tmp.resolve("config"))
                .file("locations/team-in/orders.properties", "owner: inside\n");
        var outside = TempRepo.at(tmp.resolve("outside-team")).file("orders.properties", "owner: outside\n");
        Path escape = configRepo.root().resolve("locations/team-out");
        boolean linked;
        try {
            Files.createSymbolicLink(escape, outside.root());
            linked = true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            linked = false;
        }
        Assumptions.assumeTrue(linked, "symbolic link creation is unavailable");

        var orders = repo.services("default", configRepo.root()).stream()
                .filter(service -> service.name().equals("orders")).findFirst().orElseThrow();

        assertThat(orders.config().get("owner").value()).isEqualTo("inside");
        assertThat(orders.config().get("owner").value()).isNotEqualTo("outside");
    }
}
