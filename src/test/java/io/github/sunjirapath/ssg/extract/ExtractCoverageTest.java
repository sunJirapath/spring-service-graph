package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Evidence;
import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.model.Value;
import io.github.sunjirapath.ssg.scan.Service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractCoverageTest {

    @Test
    void gatewayRouteWithNullUriIsIgnored(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("application.yml", """
                        spring.cloud.gateway.routes:
                          - uri:
                            predicates: [Path=/ignored/**]
                        """).facts(new GatewayExtractor());

        assertThat(facts).isEmpty();
    }

    @Test
    void malformedGatewayRouteKeysDoNotHideValidRoute(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("application.properties", """
                        spring.cloud.gateway.routes.not-an-index=value
                        spring.cloud.gateway.routes[0].id=orders
                        spring.cloud.gateway.routes[0].uri=lb://orders
                        spring.cloud.gateway.routes[0].predicates[0]=Path=/orders/**
                        """).facts(new GatewayExtractor());

        assertThat(calls(facts)).containsExactly("lb://orders/orders/**");
    }

    @Test
    void gatewayShortcutWithoutEqualsHasNoArguments(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("application.properties", """
                        spring.cloud.gateway.routes[0].uri=lb://orders
                        spring.cloud.gateway.routes[0].predicates[0]=Path
                        """).facts(new GatewayExtractor());

        assertThat(facts).isEmpty();
    }

    @Test
    void emptyGatewayFilterArgumentsUseDocumentedDefaults() {
        Value rewritten = GatewayExtractor.rewrite("/api/orders", List.of(
                def("SetPath"), def("PrefixPath"), def("StripPrefix")));

        assertThat(rewritten.text()).isEqualTo("/orders");
    }

    @Test
    void rewritePathWithTooFewArgumentsIsSkipped() {
        Value rewritten = GatewayExtractor.rewrite("/api/orders", List.of(def("RewritePath", "/api/(.*)")));

        assertThat(rewritten.text()).isEqualTo("/api/orders");
        assertThat(rewritten.confidence()).isEqualTo(Confidence.EXACT);
    }

    @Test
    void rewritePathWithMissingCaptureGroupBecomesUnresolved() {
        Value rewritten = GatewayExtractor.rewrite("/api/orders",
                List.of(def("RewritePath", "/api/(.*)", "/$2")));

        assertThat(rewritten.confidence()).isEqualTo(Confidence.UNRESOLVED);
        assertThat(rewritten.raw()).contains("RewritePath");
    }

    @Test
    void javaGatewayDslWithLocalUriExposesButDoesNotCall(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class Routes {
                    RouteLocator routes(RouteLocatorBuilder b) {
                        Runnable unrelated = () -> log();
                        return b.routes().route(r -> r.path("/local/**").uri("forward:/local")).build();
                    }
                }
                """).facts(new GatewayExtractor());

        assertThat(endpoints(facts)).containsExactly("/local/**");
        assertThat(calls(facts)).isEmpty();
    }

    @Test
    void javaGatewayDslWithUnknownPathKeepsUnresolvedEndpoint(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class Routes {
                    String dynamic;
                    RouteLocator routes(RouteLocatorBuilder b) {
                        return b.routes().route(r -> r.path(dynamic).uri()).build();
                    }
                }
                """).facts(new GatewayExtractor());

        assertThat(facts).singleElement().isInstanceOfSatisfying(Fact.Endpoint.class, endpoint -> {
            assertThat(endpoint.path().confidence()).isEqualTo(Confidence.UNRESOLVED);
            assertThat(endpoint.path().text()).contains(Value.UNKNOWN);
        });
    }

    @Test
    void noRepositoryBeanSuppressesRepositoryFact(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                @Entity class Item {}
                @NoRepositoryBean interface BaseRepository extends JpaRepository<Item, Long> {}
                """).facts(new DataExtractor());

        assertThat(facts).filteredOn(Fact.TableUse.class::isInstance).map(Fact.TableUse.class::cast)
                .noneMatch(table -> table.via().startsWith("repository "));
    }

    @Test
    void wildcardRepositoryEntityIsReportedAsUnresolved(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                @Entity class Item {}
                interface Items extends JpaRepository<? extends Item, Long> {}
                """).facts(new DataExtractor());

        assertThat(facts).filteredOn(Fact.Problem.class::isInstance).singleElement()
                .isInstanceOfSatisfying(Fact.Problem.class, problem ->
                        assertThat(problem.message()).contains("Items", "? extends Item", "not found"));
    }

    @Test
    void deeplyNestedRepositoryBaseStillMapsConcreteEntity(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                @Entity class Item {}
                interface B0<T, ID> extends JpaRepository<T, ID> {}
                interface B1<T, ID> extends B0<T, ID> {} interface B2<T, ID> extends B1<T, ID> {}
                interface B3<T, ID> extends B2<T, ID> {} interface B4<T, ID> extends B3<T, ID> {}
                interface B5<T, ID> extends B4<T, ID> {} interface B6<T, ID> extends B5<T, ID> {}
                interface B7<T, ID> extends B6<T, ID> {} interface B8<T, ID> extends B7<T, ID> {}
                interface B9<T, ID> extends B8<T, ID> {}
                interface Items extends B9<Item, Long> {}
                """).facts(new DataExtractor());

        // EXPECTATION?: A legal custom Spring Data hierarchy should resolve regardless of an arbitrary depth of nine.
        assertThat(tableUses(facts)).filteredOn(table -> table.via().equals("repository Items")).singleElement()
                .satisfies(table -> assertThat(table.table().text()).isEqualTo("item"));
    }

    @Test
    void rootLevelVersionedSqlFileIsClassifiedAsFlyway(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("V2__legacy.sql", "CREATE TABLE Legacy_Table(id int);\n")
                .facts(new DataExtractor());

        assertThat(tableUses(facts)).singleElement().satisfies(table -> {
            assertThat(table.table().text()).isEqualTo("legacy_table");
            assertThat(table.via()).isEqualTo("flyway");
        });
    }

    @Test
    void nonLiquibaseXmlIsIgnored(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("mapping.xml", "<root><createTable tableName=\"ghost\"/></root>")
                .facts(new DataExtractor());

        assertThat(facts).isEmpty();
    }

    @Test
    void malformedLiquibaseNodeShapesAreIgnored(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("db/changelog/bad-shapes.yml", """
                        databaseChangeLog:
                          - wrongKey: {tableName: ghost_one}
                          - createTable: ghost_two
                          - createTable:
                              wrongAttribute: ghost_three
                              tableName: {nested: ghost_four}
                        """).facts(new DataExtractor());

        assertThat(facts).isEmpty();
    }

    @Test
    void unresolvedEntityTableNameIsNotNormalizedIntoInventedText(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; @Entity @Table(name = runtimeName()) class Item {}")
                .facts(new DataExtractor());

        assertThat(tableUses(facts)).singleElement().satisfies(table -> {
            assertThat(table.table().confidence()).isEqualTo(Confidence.UNRESOLVED);
            assertThat(table.table().text()).isEqualTo(Value.UNKNOWN);
            assertThat(table.table().raw()).isEqualTo("runtimeName()");
        });
    }

    @Test
    void endpointWithHandlerOutsideIndexedSourcesHasNoReach(@TempDir Path tmp) {
        Service service = repo(tmp, "package x; class Empty {}").service();
        var endpoint = new Fact.Endpoint("GET", Value.exact("/x"), ev(service, "Missing.java", 1),
                "controller", ev(service, "Missing.java", 1));

        assertThat(ReachAnalysis.analyze(service, List.of(endpoint))).isEmpty();
    }

    @Test
    void sinkInAnotherFileIsNotAttributedToUnrelatedHandler(@TempDir Path tmp) {
        var repository = TempRepo.at(tmp.resolve("reach-files")).app()
                .java("x/A.java", """
                        package x;
                        @RestController class A { KafkaTemplate kafkaTemplate;
                            @GetMapping("/a") void a() { kafkaTemplate.send("a", "x"); }
                        }
                        """)
                .java("x/B.java", """
                        package x;
                        class B { KafkaTemplate kafkaTemplate; void b() { kafkaTemplate.send("b", "x"); } }
                        """);

        var reaches = repository.allFacts().stream().filter(Fact.Reach.class::isInstance).map(Fact.Reach.class::cast).toList();

        assertThat(reaches).singleElement().satisfies(reach -> assertThat(reach.chain()).isEqualTo("A.a"));
    }

    @Test
    void openApiScalarDocumentDoesNotHideLaterMappingDocument(@TempDir Path tmp) {
        var service = repo(tmp, "package x; class Empty {}")
                .resource("api.yml", """
                        openapi marker only
                        ---
                        openapi: 3.0.0
                        paths:
                          /ok:
                            get: {}
                        """).service();

        assertThat(OpenApiSpec.endpoints(service, Value.exact(""))).extracting(e -> e.method() + " " + e.path().text())
                .containsExactly("GET /ok");
    }

    @Test
    void openApiSpecsInEveryIgnoredTreeAreExcluded(@TempDir Path tmp) {
        var repository = repo(tmp, "package x; class Empty {}")
                .file("target/generated/api.yml", spec("/target"))
                .file("node_modules/pkg/api.yml", spec("/node"))
                .file(".git/api.yml", spec("/git"));

        assertThat(OpenApiSpec.endpoints(repository.service(), Value.exact(""))).isEmpty();
    }

    @Test
    void openApiMalformedShapesAreSkippedWhileValidOperationRemains(@TempDir Path tmp) {
        var service = repo(tmp, "package x; class Empty {}")
                .resource("api.yml", """
                        openapi: 3.0.0
                        servers: [not-a-map]
                        paths:
                          /not-a-map: ignored
                          /valid:
                            summary: ignored
                            get: {}
                        """).service();

        assertThat(OpenApiSpec.endpoints(service, Value.exact("/ctx"))).extracting(e -> e.method() + " " + e.path().text())
                .containsExactly("GET /ctx/valid");
    }

    @Test
    void openApiWithoutMappingPathsProducesNoEndpoints(@TempDir Path tmp) {
        var absent = repo(tmp.resolve("absent"), "package x; class Empty {}")
                .resource("api.yml", "openapi: 3.0.0\ninfo: {title: x}\n").service();
        var scalar = repo(tmp.resolve("scalar"), "package x; class Empty {}")
                .resource("api.yml", "openapi: 3.0.0\npaths: nope\n").service();

        assertThat(OpenApiSpec.endpoints(absent, Value.exact(""))).isEmpty();
        assertThat(OpenApiSpec.endpoints(scalar, Value.exact(""))).isEmpty();
    }

    @Test
    void openApiOpaqueAndInvalidServerUrlsContributeNoBasePath(@TempDir Path tmp) {
        var opaque = repo(tmp.resolve("opaque"), "package x; class Empty {}")
                .resource("api.yml", specWithServer("urn:orders", "/one")).service();
        var invalid = repo(tmp.resolve("invalid"), "package x; class Empty {}")
                .resource("api.yml", specWithServer("http://[broken", "/two")).service();

        assertThat(OpenApiSpec.endpoints(opaque, Value.exact(""))).extracting(e -> e.path().text()).containsExactly("/one");
        assertThat(OpenApiSpec.endpoints(invalid, Value.exact(""))).extracting(e -> e.path().text()).containsExactly("/two");
    }

    @Test
    void controllerChecksLaterSupertypeWhenFirstHasNoClassPath(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                interface Empty { @GetMapping("/empty") void empty(); }
                @RequestMapping("/api") interface Api { @GetMapping("/items") void items(); }
                @RestController class C implements Empty, Api {
                    public void empty() {} public void items() {}
                }
                """).facts(new InboundHttpExtractor());

        // Spring resolves the class-level @RequestMapping on the controller class (searching all its interfaces),
        // so /api applies to every handler of C, including the one declared in Empty
        assertThat(endpoints(facts)).containsExactlyInAnyOrder("/api/empty", "/api/items");
    }

    @Test
    void deeplyInheritedMappingIsStillExposed(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                interface I0 { @GetMapping("/deep") void get(); }
                interface I1 extends I0 {} interface I2 extends I1 {} interface I3 extends I2 {}
                interface I4 extends I3 {} interface I5 extends I4 {} interface I6 extends I5 {}
                interface I7 extends I6 {} interface I8 extends I7 {} interface I9 extends I8 {}
                @RestController class C implements I9 { public void get() {} }
                """).facts(new InboundHttpExtractor());

        // EXPECTATION?: Legal interface hierarchies deeper than eight levels should retain inherited mappings.
        assertThat(endpoints(facts)).contains("/deep");
    }

    @Test
    void emptyKafkaSendHasNoTopicFact(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class P { KafkaTemplate template; void p() { template.send(); } }")
                .facts(new KafkaExtractor());

        assertThat(facts).isEmpty();
    }

    @Test
    void producerRecordWithoutArgumentsIsKeptAsUnresolved(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class P { KafkaTemplate template; void p() { template.send(new ProducerRecord()); } }")
                .facts(new KafkaExtractor());

        assertThat(topicUses(facts)).singleElement().satisfies(topic -> {
            assertThat(topic.topic().confidence()).isEqualTo(Confidence.UNRESOLVED);
            assertThat(topic.topic().raw()).contains("new ProducerRecord()");
        });
    }

    @Test
    void nullCloudStreamDestinationIsUnresolvedInsteadOfDiscarded(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class Empty {}")
                .resource("application.yml", """
                        spring.cloud.stream.bindings.orders-in-0.destination:
                        """).facts(new KafkaExtractor());

        assertThat(topicUses(facts)).singleElement().satisfies(topic -> {
            assertThat(topic.produce()).isFalse();
            assertThat(topic.topic().confidence()).isEqualTo(Confidence.UNRESOLVED);
        });
    }

    @Test
    void chainedAndUnknownKafkaReceiversAreNotFalsePositives(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class P { void p(Object v) { factory().send("x", v); sender.send("y", v); } }
                """).facts(new KafkaExtractor());

        assertThat(facts).isEmpty();
    }

    @Test
    void blankFeignUrlFallsBackToLoadBalancedName(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                @FeignClient(name="orders", url="") interface Client { @GetMapping("/items") void get(); }
                """).facts(new OutboundHttpExtractor());

        assertThat(calls(facts)).containsExactly("lb://orders/items");
    }

    @Test
    void blankHttpExchangeTypeUrlKeepsUnknownRuntimeBase(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                @HttpExchange("") interface Client { @GetExchange("/items") void get(); }
                """).facts(new OutboundHttpExtractor());

        assertThat(callFacts(facts)).singleElement().satisfies(call -> {
            assertThat(call.url().confidence()).isEqualTo(Confidence.UNRESOLVED);
            assertThat(call.url().text()).isEqualTo(Value.UNKNOWN + "/items");
        });
    }

    @Test
    void malformedRestTemplateCallShapesAreIgnored(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class C { RestTemplate template;
                    void call() { template.getForObject(); getForObject("/x"); unrelated("/x"); }
                }
                """).facts(new OutboundHttpExtractor());

        assertThat(facts).isEmpty();
    }

    @Test
    void fluentUriWithoutArgumentIsIgnored(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class C { WebClient webClient; void call() { webClient.get().uri(); } }")
                .facts(new OutboundHttpExtractor());

        assertThat(facts).isEmpty();
    }

    @Test
    void fluentMethodRequiresExactlyOneVerbArgument(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class C { WebClient webClient; void call() {
                    webClient.method().uri("/zero");
                    webClient.method(GET, POST).uri("/two");
                } }
                """).facts(new OutboundHttpExtractor());

        assertThat(facts).isEmpty();
    }

    @Test
    void uriBuilderWithoutPathIsKeptAsUnresolved(@TempDir Path tmp) {
        var facts = repo(tmp, """
                package x;
                class C { WebClient webClient; void call() {
                    webClient.get().uri(builder -> builder.queryParam("q", "x").build());
                } }
                """).facts(new OutboundHttpExtractor());

        assertThat(callFacts(facts)).singleElement().satisfies(call ->
                assertThat(call.url().confidence()).isEqualTo(Confidence.UNRESOLVED));
    }

    @Test
    void objectCreationRootCanStillIdentifyFluentClientKind(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class C { void call() { new WebClient().get().uri(\"https://api/items\"); } }")
                .facts(new OutboundHttpExtractor());

        assertThat(callFacts(facts)).singleElement().satisfies(call -> assertThat(call.client()).isEqualTo("webclient"));
    }

    @Test
    void methodCallReceiverNamedRestTemplateIsRecognizedByHint(@TempDir Path tmp) {
        var facts = repo(tmp, "package x; class C { void call() { restTemplate().getForObject(\"https://api/items\"); } }")
                .facts(new OutboundHttpExtractor());

        assertThat(callFacts(facts)).singleElement().satisfies(call -> assertThat(call.method()).isEqualTo("GET"));
    }

    @Test
    void urlCompositionKeepsTheAvailableRawExpression() {
        Value pathOnly = OutboundHttpExtractor.url(new Value("https://api", Confidence.RESOLVED, "", List.of()),
                new Value("items", Confidence.RESOLVED, "pathExpr", List.of()));
        Value baseOnly = OutboundHttpExtractor.url(new Value("https://api/", Confidence.RESOLVED, "baseExpr", List.of()),
                new Value("", Confidence.EXACT, "", List.of()));
        Value both = OutboundHttpExtractor.url(new Value("https://api", Confidence.RESOLVED, "baseExpr", List.of()),
                new Value("/items", Confidence.RESOLVED, "pathExpr", List.of()));

        assertThat(pathOnly.raw()).isEqualTo("pathExpr");
        assertThat(baseOnly.raw()).isEqualTo("baseExpr");
        assertThat(both.raw()).isEqualTo("baseExpr + pathExpr");
    }

    private static TempRepo repo(Path tmp, String source) {
        return TempRepo.at(tmp.resolve("repo")).app().java("x/Source.java", source);
    }

    private static GatewayExtractor.Def def(String name, String... args) {
        return new GatewayExtractor.Def(name, List.of(args), null);
    }

    private static List<String> endpoints(List<Fact> facts) {
        return facts.stream().filter(Fact.Endpoint.class::isInstance).map(Fact.Endpoint.class::cast)
                .map(endpoint -> endpoint.path().text()).toList();
    }

    private static List<String> calls(List<Fact> facts) {
        return callFacts(facts).stream().map(call -> call.url().text()).toList();
    }

    private static List<Fact.Call> callFacts(List<Fact> facts) {
        return facts.stream().filter(Fact.Call.class::isInstance).map(Fact.Call.class::cast).toList();
    }

    private static List<Fact.TableUse> tableUses(List<Fact> facts) {
        return facts.stream().filter(Fact.TableUse.class::isInstance).map(Fact.TableUse.class::cast).toList();
    }

    private static List<Fact.TopicUse> topicUses(List<Fact> facts) {
        return facts.stream().filter(Fact.TopicUse.class::isInstance).map(Fact.TopicUse.class::cast).toList();
    }

    private static Evidence ev(Service service, String file, int line) {
        return new Evidence(service.repo().name(), file, line);
    }

    private static String spec(String path) {
        return "openapi: 3.0.0\npaths:\n  " + path + ":\n    get: {}\n";
    }

    private static String specWithServer(String url, String path) {
        return "openapi: 3.0.0\nservers:\n  - url: '" + url + "'\npaths:\n  " + path + ":\n    get: {}\n";
    }
}
