package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.scan.Discovery;
import io.github.sunjirapath.ssg.scan.JavaIndex;
import io.github.sunjirapath.ssg.scan.Repo;
import io.github.sunjirapath.ssg.scan.Service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReachAnalysisTest {

    @Test
    void reachesKafkaTopicThroughServiceField(@TempDir Path repo) throws IOException {
        var facts = facts(repo, """
                @RestController class C { AccountService accountService; @PostMapping("/accounts") Object create(Object x) { return accountService.create(x); } }
                @Service class AccountService { KafkaTemplate kafkaTemplate; Object create(Object x) { kafkaTemplate.send(Topics.CREATED, x); return x; } }
                class Topics { static final String CREATED = "created"; }
                """);
        var topic = facts.stream().filter(Fact.TopicUse.class::isInstance).map(Fact.TopicUse.class::cast).findFirst().orElseThrow();

        assertThat(reaches(repo, facts)).anySatisfy(r -> {
            assertThat(r.sink()).isEqualTo(topic.evidence());
            assertThat(r.chain()).isEqualTo("C.create → AccountService.create");
        });
    }

    @Test
    void reachesSpringDataRepositoryMethodThroughInterfaceImplementation(@TempDir Path repo) throws IOException {
        var facts = facts(repo, """
                @RestController class C { OrderService orders; @GetMapping("/orders") Object get(String e) { return orders.find(e); } }
                interface OrderService { Object find(String e); }
                @Service class OrderServiceImpl implements OrderService { OrderRepo repo; public Object find(String e) { return repo.findByEmail(e); } }
                interface OrderRepo extends JpaRepository<OrderEntity, Long> {}
                @Entity class OrderEntity {}
                """);
        var table = facts.stream().filter(Fact.TableUse.class::isInstance).map(Fact.TableUse.class::cast)
                .filter(t -> t.via().equals("repository OrderRepo")).findFirst().orElseThrow();

        assertThat(reaches(repo, facts)).anySatisfy(r -> assertThat(r.sink()).isEqualTo(table.evidence()));
    }

    @Test
    void reachesTheTableAQueryMethodNames(@TempDir Path repo) throws IOException {
        var facts = facts(repo, """
                @RestController class C { PetRepo pets; @GetMapping("/petTypes") Object get() { return pets.findPetTypes(); } }
                interface PetRepo extends JpaRepository<Pet, Long> {
                    @Query("SELECT t FROM PetType t") Object findPetTypes();
                }
                @Entity class Pet {}
                @Entity @Table(name = "types") class PetType {}
                """);
        var table = facts.stream().filter(Fact.TableUse.class::isInstance).map(Fact.TableUse.class::cast)
                .filter(t -> t.via().equals("@Query")).findFirst().orElseThrow();

        assertThat(table.table().text()).isEqualTo("types");
        assertThat(reaches(repo, facts)).anySatisfy(r -> assertThat(r.sink()).isEqualTo(table.evidence()));
    }

    @Test
    void reachesFeignCall(@TempDir Path repo) throws IOException {
        var facts = facts(repo, """
                @RestController class C { BillingClient billing; @GetMapping("/payments/{id}") Object get(String id) { return billing.invoice(id); } }
                @FeignClient(name = "billing") interface BillingClient { @GetMapping("/invoices/{id}") Object invoice(String id); }
                """);
        var call = facts.stream().filter(Fact.Call.class::isInstance).map(Fact.Call.class::cast).findFirst().orElseThrow();

        assertThat(reaches(repo, facts)).anySatisfy(r -> assertThat(r.sink()).isEqualTo(call.evidence()));
    }

    @Test
    void stopsBeforeSinkBeyondMaximumDepth(@TempDir Path repo) throws IOException {
        var facts = facts(repo, deepChain(8));

        assertThat(reaches(repo, facts)).isEmpty();
    }

    @Test
    void reachesSinkWithinMaximumDepth(@TempDir Path repo) throws IOException {
        var facts = facts(repo, deepChain(2));

        assertThat(reaches(repo, facts)).hasSize(1);
    }

    @Test
    void terminatesForRecursiveCallsAndRecordsSinkOnce(@TempDir Path repo) throws IOException {
        var facts = facts(repo, """
                @RestController class C { KafkaTemplate kafkaTemplate; @GetMapping("/x") Object get(Object x) { return a(x); }
                    private Object a(Object x) { return b(x); } private Object b(Object x) { kafkaTemplate.send("loop", x); return a(x); } }
                """);

        assertThat(reaches(repo, facts)).hasSize(1);
    }

    @Test
    void keepsHandlersAndSinksOnOneLineApart(@TempDir Path repo) throws IOException {
        var facts = facts(repo, """
                @RestController class C { KafkaTemplate k; @GetMapping("/x") Object a(Object x) { k.send("ta", x); return x; } @GetMapping("/y") Object b(Object x) { k.send("tb", x); return x; } }
                """);
        var topics = facts.stream().filter(Fact.TopicUse.class::isInstance).map(Fact.TopicUse.class::cast).toList();
        var ta = topics.stream().filter(t -> t.topic().text().equals("ta")).findFirst().orElseThrow().evidence();
        var tb = topics.stream().filter(t -> t.topic().text().equals("tb")).findFirst().orElseThrow().evidence();

        assertThat(reaches(repo, facts)).extracting(r -> r.path() + " " + (r.sink().equals(ta) ? "ta" : r.sink().equals(tb) ? "tb" : "?"))
                .containsExactlyInAnyOrder("/x ta", "/y tb");
    }

    @Test
    void ignoresEndpointsWithoutJavaHandlers(@TempDir Path repo) throws IOException {
        Path resources = repo.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("application.yml"), """
                spring:
                  cloud:
                    gateway:
                      routes:
                        - id: x
                          predicates: [Path=/x/**]
                          uri: lb://other
                """);
        var service = service(repo, "");
        var facts = Extractor.all().stream().flatMap(e -> e.extract(service).stream()).toList();

        assertThat(facts).anyMatch(Fact.Endpoint.class::isInstance);
        assertThat(ReachAnalysis.analyze(service, facts)).isEmpty();
    }

    private static List<Fact.Reach> reaches(Path repo, List<Fact> facts) throws IOException {
        return ReachAnalysis.analyze(service(repo, ""), facts).stream().map(Fact.Reach.class::cast).toList();
    }

    private static List<Fact> facts(Path repo, String source) throws IOException {
        var service = service(repo, source);
        return Extractor.all().stream().flatMap(e -> e.extract(service).stream()).toList();
    }

    private static Service service(Path repo, String source) throws IOException {
        Path src = repo.resolve("src/main/java/x");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "package x; @SpringBootApplication class App {}");
        if (!source.isEmpty()) Files.writeString(src.resolve("C.java"), "package x;\n" + source);
        return Discovery.discover(JavaIndex.build(Repo.of(repo)), "default", null).getFirst();
    }

    private static String deepChain(int count) {
        var methods = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            String next = i == count ? "kafkaTemplate.send(\"deep\", x); return x;" : "return a" + (i + 1) + "(x);";
            methods.append("private Object a").append(i).append("(Object x) { ").append(next).append(" }\n");
        }
        return "@RestController class C { KafkaTemplate kafkaTemplate;\n@GetMapping(\"/x\") Object get(Object x) { return a1(x); }\n"
                + methods + "}";
    }
}
