package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Fact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReachEdgeCasesTest {

    @Test
    void reachesRepositoryThroughInterfaceImplementation(@TempDir Path tmp) {
        var reaches = reaches(repo(tmp, """
                package x;
                @RestController class C { Orders orders; @GetMapping("/orders") Object get() { return orders.get(); } }
                interface Orders { Object get(); }
                class OrdersImpl implements Orders { OrderRepository repo; public Object get() { return repo.findAll(); } }
                interface OrderRepository extends JpaRepository<Order, Long> {}
                @Entity class Order {}
                """));

        assertThat(reaches).singleElement().satisfies(r -> assertThat(r.chain())
                .isEqualTo("C.get \u2192 OrdersImpl.get \u2192 repo.findAll"));
    }

    @Test
    void reachesSinkThroughAbstractBaseClass(@TempDir Path tmp) {
        var reaches = reaches(repo(tmp, """
                package x;
                @RestController class C { BaseService service; @GetMapping("/x") void get() { service.run(); } }
                abstract class BaseService { abstract void run(); }
                class ServiceImpl extends BaseService { KafkaTemplate kafkaTemplate; void run() { kafkaTemplate.send("abstract", "x"); } }
                """));

        assertThat(reaches).singleElement().satisfies(r -> assertThat(r.chain()).contains("ServiceImpl.run"));
    }

    @Test
    void concreteReceiverDoesNotFanOutToSiblingImplementations(@TempDir Path tmp) {
        var reaches = reaches(repo(tmp, """
                package x;
                @RestController class C {
                    AProducer a; Producer any;
                    @GetMapping("/a") void a() { a.send(); }
                    @GetMapping("/any") void any() { any.send(); }
                }
                interface Producer { void send(); }
                abstract class BaseProducer implements Producer {}
                class AProducer extends BaseProducer { KafkaTemplate kafkaTemplate; public void send() { kafkaTemplate.send("a", "x"); } }
                class BProducer extends BaseProducer { KafkaTemplate kafkaTemplate; public void send() { kafkaTemplate.send("b", "x"); } }
                """));

        assertThat(reaches).filteredOn(r -> r.path().equals("/a")).singleElement()
                .satisfies(r -> assertThat(r.chain()).contains("AProducer.send"));
        // an interface receiver still dispatches to every implementation, through the abstract class
        assertThat(reaches).filteredOn(r -> r.path().equals("/any")).extracting(Fact.Reach::chain)
                .anyMatch(c -> c.contains("AProducer.send")).anyMatch(c -> c.contains("BProducer.send"));
    }

    @Test
    void reachesSinkThroughGenericServiceField(@TempDir Path tmp) {
        var reaches = reaches(repo(tmp, """
                package x;
                @RestController class C { GenericService<String> service; @GetMapping("/x") void get() { service.publish("x"); } }
                class GenericService<T> { KafkaTemplate kafkaTemplate; void publish(T value) { kafkaTemplate.send("generic", value); } }
                """));

        assertThat(reaches).singleElement().satisfies(r -> assertThat(r.chain()).contains("GenericService.publish"));
    }

    @Test
    void reachesSinkThroughStaticUtilityMethod(@TempDir Path tmp) {
        var reaches = reaches(repo(tmp, """
                package x;
                @RestController class C { @GetMapping("/x") void get() { Events.publish(); } }
                class Events { static KafkaTemplate kafkaTemplate; static void publish() { kafkaTemplate.send("static", "x"); } }
                """));

        assertThat(reaches).singleElement().satisfies(r -> assertThat(r.chain()).isEqualTo("C.get \u2192 Events.publish"));
    }

    @Test
    void followsThisQualifiedCall(@TempDir Path tmp) {
        var reaches = reaches(repo(tmp, """
                package x;
                @RestController class C { KafkaTemplate kafkaTemplate; @GetMapping("/x") void get() { this.publish(); }
                    private void publish() { kafkaTemplate.send("this", "x"); }
                }
                """));

        assertThat(reaches).singleElement().satisfies(r -> assertThat(r.chain()).isEqualTo("C.get \u2192 C.publish"));
    }

    @Test
    void followsSuperMethodCall(@TempDir Path tmp) {
        var reaches = reaches(repo(tmp, """
                package x;
                class Base { KafkaTemplate kafkaTemplate; void publish() { kafkaTemplate.send("super", "x"); } }
                @RestController class C extends Base { @GetMapping("/x") void get() { super.publish(); } }
                """));

        assertThat(reaches).singleElement().satisfies(r -> assertThat(r.chain()).isEqualTo("C.get \u2192 Base.publish"));
    }

    @Test
    void interfaceDispatchReachesBothImplementations(@TempDir Path tmp) {
        var reaches = reaches(repo(tmp, """
                package x;
                @RestController class C { Publisher publisher; @PostMapping("/x") void post() { publisher.publish(); } }
                interface Publisher { void publish(); }
                class A implements Publisher { KafkaTemplate kafkaTemplate; public void publish() { kafkaTemplate.send("a", "x"); } }
                class B implements Publisher { KafkaTemplate kafkaTemplate; public void publish() { kafkaTemplate.send("b", "x"); } }
                """));

        assertThat(reaches).extracting(Fact.Reach::chain)
                .containsExactlyInAnyOrder("C.post \u2192 A.publish", "C.post \u2192 B.publish");
    }

    @Test
    void constructorInjectedFieldIsResolved(@TempDir Path tmp) {
        assertThat(reaches(repo(tmp, """
                package x;
                @RestController class C { final Service service; C(Service service) { this.service = service; }
                    @GetMapping("/x") void get() { service.publish(); } }
                class Service { KafkaTemplate kafkaTemplate; void publish() { kafkaTemplate.send("constructor", "x"); } }
                """))).hasSize(1);
    }

    @Test
    void autowiredFieldIsResolved(@TempDir Path tmp) {
        assertThat(reaches(repo(tmp, """
                package x;
                @RestController class C { @Autowired Service service; @GetMapping("/x") void get() { service.publish(); } }
                class Service { KafkaTemplate kafkaTemplate; void publish() { kafkaTemplate.send("autowired", "x"); } }
                """))).hasSize(1);
    }

    @Test
    void lombokRequiredArgsConstructorFieldIsResolvedFromDeclaration(@TempDir Path tmp) {
        assertThat(reaches(repo(tmp, """
                package x;
                @RestController @RequiredArgsConstructor class C { final Service service;
                    @GetMapping("/x") void get() { service.publish(); } }
                class Service { KafkaTemplate kafkaTemplate; void publish() { kafkaTemplate.send("lombok", "x"); } }
                """))).hasSize(1);
    }

    @Test
    void sinkInsideHandlerLambdaCountsBySourceRange(@TempDir Path tmp) {
        var reaches = reaches(repo(tmp, """
                package x;
                @RestController class C { KafkaTemplate kafkaTemplate;
                    @GetMapping("/x") void get() { values().forEach(v -> kafkaTemplate.send("lambda", v)); }
                    java.util.List<String> values() { return java.util.List.of(); }
                }
                """));

        // EXPECTATION?: Although calls inside lambdas are not traversed, a sink physically inside the endpoint handler is still direct evidence.
        assertThat(reaches).singleElement().satisfies(r -> assertThat(r.chain()).isEqualTo("C.get"));
    }

    @Test
    void knownLimitMethodReferenceIsNotFollowed(@TempDir Path tmp) {
        var reaches = reaches(repo(tmp, """
                package x;
                @RestController class C { KafkaTemplate kafkaTemplate;
                    @GetMapping("/x") void get() { values().forEach(this::publish); }
                    void publish(String v) { kafkaTemplate.send("reference", v); }
                    java.util.List<String> values() { return java.util.List.of(); }
                }
                """));

        assertThat(reaches).isEmpty();
    }

    @Test
    void knownLimitChainedReceiverCallIsNotFollowed(@TempDir Path tmp) {
        var reaches = reaches(repo(tmp, """
                package x;
                @RestController class C { Factory factory; @GetMapping("/x") void get() { factory.service().publish(); } }
                class Factory { Service service() { return null; } }
                class Service { KafkaTemplate kafkaTemplate; void publish() { kafkaTemplate.send("chain", "x"); } }
                """));

        assertThat(reaches).isEmpty();
    }

    @Test
    void oneMethodCanReachMultipleSinkKinds(@TempDir Path tmp) {
        var reaches = reaches(repo(tmp, """
                package x;
                @RestController class C { KafkaTemplate kafkaTemplate; OrderRepository repo; Billing billing;
                    @PostMapping("/x") void post() { kafkaTemplate.send("events", "x"); repo.save(null); billing.charge(); }
                }
                @FeignClient(name = "billing") interface Billing { @PostMapping("/charge") void charge(); }
                interface OrderRepository extends JpaRepository<Order, Long> {}
                @Entity class Order {}
                """));

        assertThat(reaches).hasSize(3);
    }

    @Test
    void sameSinkIsReachedIndependentlyFromTwoEndpoints(@TempDir Path tmp) {
        var reaches = reaches(repo(tmp, """
                package x;
                @RestController class C { Events events;
                    @GetMapping("/a") void a() { events.publish(); }
                    @GetMapping("/b") void b() { events.publish(); }
                }
                class Events { KafkaTemplate kafkaTemplate; void publish() { kafkaTemplate.send("shared", "x"); } }
                """));

        assertThat(reaches).extracting(Fact.Reach::path).containsExactlyInAnyOrder("/a", "/b");
        assertThat(reaches).extracting(Fact.Reach::sink).allSatisfy(s -> assertThat(s).isEqualTo(reaches.getFirst().sink()));
    }

    @Test
    void reachesSinkAtExactlyMaximumDepth(@TempDir Path tmp) {
        assertThat(reaches(repo(tmp, deepChain(ReachAnalysis.MAX_DEPTH)))).hasSize(1);
    }

    @Test
    void doesNotReachSinkBeyondMaximumDepth(@TempDir Path tmp) {
        assertThat(reaches(repo(tmp, deepChain(ReachAnalysis.MAX_DEPTH + 1)))).isEmpty();
    }

    @Test
    void recursionTerminatesAndDoesNotDuplicateSink(@TempDir Path tmp) {
        var reaches = reaches(repo(tmp, """
                package x;
                @RestController class C { @GetMapping("/x") void get() { a(); } void a() { b(); }
                    void b() { a(); Events.publish(); } }
                class Events { static KafkaTemplate kafkaTemplate; static void publish() { kafkaTemplate.send("recursive", "x"); } }
                """));

        assertThat(reaches).hasSize(1);
    }

    @Test
    void openApiAndGatewayEndpointsProduceNoReach(@TempDir Path tmp) {
        var repo = repo(tmp, "package x; @RestController class C implements MissingApi {}")
                .resource("openapi.yaml", "openapi: 3.0.0\npaths:\n  /spec:\n    get:\n      responses: {'200': {description: ok}}\n")
                .resource("application.yml", "spring.cloud.gateway.routes:\n  - uri: lb://x\n    predicates: [Path=/gateway/**]\n");
        var facts = repo.allFacts();

        assertThat(facts).filteredOn(Fact.Endpoint.class::isInstance).extracting(f -> ((Fact.Endpoint) f).source())
                .containsExactlyInAnyOrder("openapi", "gateway-route");
        assertThat(facts).noneMatch(Fact.Reach.class::isInstance);
    }

    @Test
    void handlerCanBeInterfaceDefaultMethod(@TempDir Path tmp) {
        var reaches = reaches(repo(tmp, """
                package x;
                interface Api { KafkaTemplate TEMPLATE = null; @GetMapping("/x") default void get() { TEMPLATE.send("default", "x"); } }
                @RestController class C implements Api {}
                """));

        assertThat(reaches).singleElement().satisfies(r -> assertThat(r.chain()).isEqualTo("Api.get"));
    }

    @Test
    void reachesFeignClientThroughWrapperService(@TempDir Path tmp) {
        var reaches = reaches(repo(tmp, """
                package x;
                @RestController class C { BillingService service; @GetMapping("/x") void get() { service.charge(); } }
                class BillingService { BillingClient client; void charge() { client.charge(); } }
                @FeignClient(name = "billing") interface BillingClient { @PostMapping("/charge") void charge(); }
                """));

        // the Feign call's evidence is the client method itself, so the chain ends there
        assertThat(reaches).singleElement().satisfies(r -> assertThat(r.chain())
                .isEqualTo("C.get \u2192 BillingService.charge \u2192 BillingClient.charge"));
    }

    @Test
    void repositoryUseInPrivateHelperIsReached(@TempDir Path tmp) {
        var reaches = reaches(repo(tmp, """
                package x;
                @RestController class C { OrderRepository repo; @GetMapping("/x") Object get() { return load(); }
                    private Object load() { return repo.findByCode("x"); } }
                interface OrderRepository extends JpaRepository<Order, Long> { Order findByCode(String code); }
                @Entity class Order {}
                """));

        assertThat(reaches).singleElement().satisfies(r -> assertThat(r.chain())
                .isEqualTo("C.get \u2192 C.load \u2192 repo.findByCode"));
    }

    @Test
    void chainStartsWithControllerMethodAndUsesArrowSeparator(@TempDir Path tmp) {
        var reach = reaches(repo(tmp, """
                package x;
                @RestController class Controller { Service service; @GetMapping("/x") void handler() { service.publish(); } }
                class Service { KafkaTemplate kafkaTemplate; void publish() { kafkaTemplate.send("x", "x"); } }
                """)).getFirst();

        assertThat(reach.chain()).startsWith("Controller.handler").contains(" \u2192 ").doesNotContain("->");
    }

    private static TempRepo repo(Path tmp, String source) {
        return TempRepo.at(tmp.resolve("repo")).app().java("x/Reach.java", source);
    }

    private static List<Fact.Reach> reaches(TempRepo repo) {
        return repo.allFacts().stream().filter(Fact.Reach.class::isInstance).map(Fact.Reach.class::cast).toList();
    }

    private static String deepChain(int calls) {
        var methods = new StringBuilder();
        for (int i = 1; i <= calls; i++) {
            String body = i == calls ? "kafkaTemplate.send(\"deep\", x);" : "a" + (i + 1) + "(x);";
            methods.append("void a").append(i).append("(Object x) { ").append(body).append(" }\n");
        }
        return "package x; @RestController class C { KafkaTemplate kafkaTemplate;\n"
                + "@GetMapping(\"/x\") void get(Object x) { a1(x); }\n" + methods + "}";
    }
}
