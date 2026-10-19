package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.TempRepo;
import io.github.sunjirapath.ssg.model.Confidence;
import io.github.sunjirapath.ssg.model.Fact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundHttpEdgeCasesTest {

    @Test
    void feignSupportsNameValuePathAndAllMappingForms(@TempDir Path tmp) {
        var calls = calls(TempRepo.at(tmp.resolve("feign-mappings")).app().java("x/Clients.java", """
                package x;
                @FeignClient(name = "catalog", path = "/api") @RequestMapping("/v1") interface Catalog {
                    @GetMapping("/get") Object get();
                    @PostMapping(path = "/post") Object post();
                    @PutMapping("/put") Object put();
                    @DeleteMapping("/delete") void delete();
                    @PatchMapping("/patch") Object patch();
                    @RequestMapping(value = "/old", method = RequestMethod.GET) Object old();
                }
                @FeignClient("billing") interface Billing { @GetMapping("/invoice") Object invoice(); }
                """));

        assertThat(calls).extracting(OutboundHttpEdgeCasesTest::clientMethodAndUrl)
                .containsExactlyInAnyOrder(
                        "feign GET lb://catalog/api/v1/get", "feign POST lb://catalog/api/v1/post",
                        "feign PUT lb://catalog/api/v1/put", "feign DELETE lb://catalog/api/v1/delete",
                        "feign PATCH lb://catalog/api/v1/patch", "feign GET lb://catalog/api/v1/old",
                        "feign GET lb://billing/invoice");
    }

    @Test
    void feignUrlWinsOverNameAndIgnoresContextFallbackAttributes(@TempDir Path tmp) {
        var calls = calls(TempRepo.at(tmp.resolve("feign-url")).app().java("x/Client.java", """
                package x;
                @FeignClient(name = "ignored", url = "https://billing.example/", contextId = "ctx", fallback = Backup.class)
                interface Client { @GetMapping("charges") Object get(); }
                class Backup {}
                """));

        assertThat(calls).singleElement().satisfies(c -> {
            assertThat(c.url().text()).isEqualTo("https://billing.example/charges");
            assertThat(c.client()).isEqualTo("feign");
        });
    }

    @Test
    void feignNamePlaceholderResolvesFromConfiguration(@TempDir Path tmp) {
        var calls = calls(TempRepo.at(tmp.resolve("feign-name-property")).app()
                .resource("application.properties", "clients.catalog=catalog-service\n")
                .java("x/Client.java", """
                        package x;
                        @FeignClient(name = "${clients.catalog}") interface Client {
                            @GetMapping("/items") Object get();
                        }
                        """));

        assertThat(calls).singleElement().satisfies(c -> {
            assertThat(c.url().text()).isEqualTo("lb://catalog-service/items");
            assertThat(c.url().confidence()).isEqualTo(Confidence.RESOLVED);
        });
    }

    @Test
    void unresolvedFeignUrlPlaceholderStillProducesCall(@TempDir Path tmp) {
        var calls = calls(TempRepo.at(tmp.resolve("feign-url-missing")).app().java("x/Client.java", """
                package x;
                @FeignClient(name = "ignored", url = "${missing.url}") interface Client {
                    @GetMapping("/items") Object get();
                }
                """));

        assertThat(calls).singleElement().satisfies(c -> {
            assertThat(c.url().text()).isEqualTo("{?}/items");
            assertThat(c.url().confidence()).isEqualTo(Confidence.UNRESOLVED);
        });
    }

    @Test
    void feignInheritsMappedMethodsFromSharedApiAndIgnoresUnmappedMethods(@TempDir Path tmp) {
        var calls = calls(TempRepo.at(tmp.resolve("feign-inheritance")).app().java("x/Client.java", """
                package x;
                interface SharedApi {
                    @GetMapping("/shared") Object shared();
                    Object helper();
                }
                @FeignClient("shared") interface Client extends SharedApi {
                    @PostMapping("/local") Object local();
                    Object anotherHelper();
                }
                """));

        assertThat(calls).extracting(OutboundHttpEdgeCasesTest::methodAndUrl)
                .containsExactlyInAnyOrder("GET lb://shared/shared", "POST lb://shared/local");
    }

    @Test
    void feignCallEvidencePointsAtMappedMethod(@TempDir Path tmp) {
        var calls = calls(TempRepo.at(tmp.resolve("feign-evidence")).app().java("x/Client.java", """
                package x;
                @FeignClient("target") interface Client {
                    @GetMapping("/items")
                    Object get();
                }
                """));

        assertThat(calls).singleElement().satisfies(c -> {
            assertThat(c.evidence().file()).endsWith("Client.java");
            // evidence points at the start of the declaration, i.e. its first annotation (same as inbound)
            assertThat(c.evidence().line()).isEqualTo(3);
        });
    }

    @Test
    void httpExchangeSupportsTypeLevelUrlAndValue(@TempDir Path tmp) {
        var calls = calls(TempRepo.at(tmp.resolve("exchange-base")).app().java("x/Clients.java", """
                package x;
                @HttpExchange(url = "https://one.example/api") interface One {
                    @GetExchange("/x") Object x();
                }
                @HttpExchange("https://two.example/root") interface Two {
                    @PostExchange(url = "/y") Object y();
                }
                """));

        assertThat(calls).extracting(OutboundHttpEdgeCasesTest::clientMethodAndUrl)
                .containsExactlyInAnyOrder(
                        "http-exchange GET https://one.example/api/x",
                        "http-exchange POST https://two.example/root/y");
    }

    @Test
    void httpExchangeWithoutTypeUrlKeepsUnknownProxyBase(@TempDir Path tmp) {
        var calls = calls(TempRepo.at(tmp.resolve("exchange-no-base")).app().java("x/Client.java", """
                package x;
                interface Client { @GetExchange("/items") Object get(); }
                """));

        assertThat(calls).singleElement().satisfies(c -> {
            assertThat(c.url().text()).isEqualTo("{?}/items");
            assertThat(c.url().confidence()).isEqualTo(Confidence.UNRESOLVED);
        });
    }

    @Test
    void httpExchangeSupportsComposedVerbsAndExplicitMethod(@TempDir Path tmp) {
        var calls = calls(TempRepo.at(tmp.resolve("exchange-verbs")).app().java("x/Client.java", """
                package x;
                @HttpExchange("https://api.example") interface Client {
                    @GetExchange("/get") Object get();
                    @PostExchange("/post") Object post();
                    @PutExchange("/put") Object put();
                    @DeleteExchange("/delete") void delete();
                    @PatchExchange("/patch") Object patch();
                    @HttpExchange(method = "PATCH", url = "/explicit") Object explicit();
                }
                """));

        assertThat(calls).extracting(OutboundHttpEdgeCasesTest::methodAndUrl)
                .containsExactlyInAnyOrder(
                        "GET https://api.example/get", "POST https://api.example/post",
                        "PUT https://api.example/put", "DELETE https://api.example/delete",
                        "PATCH https://api.example/patch", "PATCH https://api.example/explicit");
    }

    @Test
    void httpExchangeMixedInterfaceIgnoresUnannotatedMethods(@TempDir Path tmp) {
        var calls = calls(TempRepo.at(tmp.resolve("exchange-mixed")).app().java("x/Client.java", """
                package x;
                @HttpExchange("https://api.example") interface Client {
                    @GetExchange("/mapped") Object mapped();
                    Object helper();
                }
                """));

        assertThat(calls).extracting(OutboundHttpEdgeCasesTest::methodAndUrl)
                .containsExactly("GET https://api.example/mapped");
    }

    @Test
    void restTemplateRecognizesEveryConvenienceMethod(@TempDir Path tmp) {
        var calls = calls(restRepo(tmp.resolve("rest-methods"), """
                class Calls {
                    RestTemplate restTemplate;
                    void call() {
                        restTemplate.getForObject("http://api/get-object", Object.class);
                        restTemplate.getForEntity("http://api/get-entity", Object.class);
                        restTemplate.postForObject("http://api/post-object", null, Object.class);
                        restTemplate.postForEntity("http://api/post-entity", null, Object.class);
                        restTemplate.postForLocation("http://api/post-location", null);
                        restTemplate.put("http://api/put", null);
                        restTemplate.delete("http://api/delete");
                        restTemplate.patchForObject("http://api/patch", null, Object.class);
                        restTemplate.headForHeaders("http://api/head");
                        restTemplate.optionsForAllow("http://api/options");
                        restTemplate.execute("http://api/trace", HttpMethod.TRACE, null);
                    }
                }
                """));

        assertThat(calls).extracting(OutboundHttpEdgeCasesTest::methodAndUrl)
                .containsExactlyInAnyOrder(
                        "GET http://api/get-object", "GET http://api/get-entity",
                        "POST http://api/post-object", "POST http://api/post-entity", "POST http://api/post-location",
                        "PUT http://api/put", "DELETE http://api/delete", "PATCH http://api/patch",
                        "HEAD http://api/head", "OPTIONS http://api/options", "TRACE http://api/trace");
    }

    @Test
    void restTemplateExchangeUsesLiteralOrWildcardHttpMethod(@TempDir Path tmp) {
        var calls = calls(restRepo(tmp.resolve("rest-exchange"), """
                class Calls {
                    RestOperations operations;
                    void call(HttpMethod dynamic) {
                        operations.exchange("http://api/get", HttpMethod.GET, null, Object.class);
                        operations.exchange("http://api/dynamic", dynamic, null, Object.class);
                    }
                }
                """));

        assertThat(calls).extracting(OutboundHttpEdgeCasesTest::methodAndUrl)
                .containsExactlyInAnyOrder("GET http://api/get", "* http://api/dynamic");
    }

    @Test
    void restTemplateRecognizesFieldLocalConstructorParameterAndThisReceiver(@TempDir Path tmp) {
        var calls = calls(restRepo(tmp.resolve("rest-receivers"), """
                class Calls {
                    RestTemplate field;
                    RestOperations operations;
                    Calls(RestTemplate injected) { injected.delete("http://api/injected"); }
                    void call() {
                        RestTemplate local = field;
                        field.getForObject("http://api/field", Object.class);
                        operations.getForEntity("http://api/operations", Object.class);
                        local.put("http://api/local", null);
                        this.field.delete("http://api/this");
                    }
                }
                """));

        assertThat(calls).extracting(OutboundHttpEdgeCasesTest::methodAndUrl)
                .containsExactlyInAnyOrder(
                        "DELETE http://api/injected", "GET http://api/field", "GET http://api/operations",
                        "PUT http://api/local", "DELETE http://api/this");
    }

    @Test
    void unrelatedReceiversWithRestMethodNamesDoNotProduceCalls(@TempDir Path tmp) {
        var calls = calls(restRepo(tmp.resolve("receiver-false-positives"), """
                class Calls {
                    Map map; Repository repository; Cache cache;
                    void call() {
                        map.put("key", "value");
                        repository.delete("id");
                        cache.put("key", "value");
                    }
                }
                interface Repository { void delete(String id); }
                interface Cache { void put(String key, String value); }
                """));

        assertThat(calls).isEmpty();
    }

    @Test
    void uriAndRequestEntityArgumentsRemainUnresolved(@TempDir Path tmp) {
        var calls = calls(restRepo(tmp.resolve("object-urls"), """
                class Calls {
                    RestTemplate restTemplate;
                    void call() {
                        URI uri = URI.create("http://api/items");
                        RequestEntity request = new RequestEntity();
                        restTemplate.getForObject(uri, Object.class);
                        restTemplate.exchange(request, Object.class);
                    }
                }
                """));

        assertThat(calls).hasSize(2).allSatisfy(c -> {
            assertThat(c.url().text()).isEqualTo("{?}");
            assertThat(c.url().confidence()).isEqualTo(Confidence.UNRESOLVED);
        });
    }

    @Test
    void singleRootUriPrefixesRelativeRestTemplateUrls(@TempDir Path tmp) {
        var calls = calls(restRepo(tmp.resolve("root-uri"), """
                class Calls {
                    RestTemplate restTemplate;
                    Object config() { return new RestTemplateBuilder().rootUri("https://api.example/base"); }
                    Object call() { return restTemplate.getForObject("items", Object.class); }
                }
                """));

        assertThat(calls).singleElement().satisfies(c -> assertThat(c.url().text()).isEqualTo("https://api.example/base/items"));
    }

    @Test
    void differentRootUrisMakeRelativeRestTemplateBaseUnresolved(@TempDir Path tmp) {
        var calls = calls(restRepo(tmp.resolve("ambiguous-root-uri"), """
                class Calls {
                    RestTemplate restTemplate;
                    Object one() { return new RestTemplateBuilder().rootUri("https://one.example"); }
                    Object two() { return new RestTemplateBuilder().rootUri("https://two.example"); }
                    Object call() { return restTemplate.getForObject("/items", Object.class); }
                }
                """));

        assertThat(calls).singleElement().satisfies(c -> {
            assertThat(c.url().text()).isEqualTo("{?}/items");
            assertThat(c.url().confidence()).isEqualTo(Confidence.UNRESOLVED);
        });
    }

    @Test
    void fluentClientsSupportConvenienceVerbsMethodAndExtraUriArguments(@TempDir Path tmp) {
        var calls = calls(restRepo(tmp.resolve("fluent-verbs"), """
                class Calls {
                    WebClient webClient; RestClient restClient;
                    void call() {
                        webClient.get().uri("http://api/get");
                        webClient.post().uri("http://api/post");
                        webClient.put().uri("http://api/put");
                        webClient.delete().uri("http://api/delete");
                        webClient.patch().uri("http://api/patch");
                        webClient.head().uri("http://api/head");
                        webClient.options().uri("http://api/options");
                        restClient.method(HttpMethod.PUT).uri("http://api/{id}", 42);
                    }
                }
                """));

        assertThat(calls).extracting(OutboundHttpEdgeCasesTest::clientMethodAndUrl)
                .containsExactlyInAnyOrder(
                        "webclient GET http://api/get", "webclient POST http://api/post",
                        "webclient PUT http://api/put", "webclient DELETE http://api/delete",
                        "webclient PATCH http://api/patch", "webclient HEAD http://api/head",
                        "webclient OPTIONS http://api/options", "restclient PUT http://api/{id}");
    }

    @Test
    void fluentUriBuilderJoinsPathsAndIgnoresQueryBuilder(@TempDir Path tmp) {
        var calls = calls(restRepo(tmp.resolve("uri-builder"), """
                class Calls {
                    WebClient webClient;
                    void call() {
                        webClient.get().uri(b -> b.path("/a").path("/b").queryParam("q", "x").build());
                    }
                }
                """));

        assertThat(calls).singleElement().satisfies(c -> assertThat(c.url().text()).isEqualTo("{?}/a/b"));
    }

    @Test
    void fluentUriCreatedByMethodCallIsUnresolved(@TempDir Path tmp) {
        var calls = calls(restRepo(tmp.resolve("uri-create"), """
                class Calls {
                    WebClient webClient;
                    void call() { webClient.get().uri(URI.create("http://api/items")); }
                }
                """));

        assertThat(calls).singleElement().satisfies(c -> {
            assertThat(c.url().text()).isEqualTo("{?}");
            assertThat(c.url().confidence()).isEqualTo(Confidence.UNRESOLVED);
        });
    }

    @Test
    void webClientBuilderChainIsRecognized(@TempDir Path tmp) {
        var calls = calls(restRepo(tmp.resolve("builder-chain"), """
                class Calls {
                    WebClientBuilder webClientBuilder;
                    void call() { webClientBuilder.build().get().uri("http://api/items"); }
                }
                """));

        assertThat(calls).singleElement().satisfies(c -> assertThat(c.client()).isEqualTo("webclient"));
    }

    @Test
    void singleBaseUrlPrefixesRelativeFluentUrlAndLabelsClientKinds(@TempDir Path tmp) {
        var calls = calls(restRepo(tmp.resolve("fluent-base"), """
                class Calls {
                    WebClient webClient; RestClient restClient; Object client;
                    Object config() { return WebClient.builder().baseUrl("https://api.example/root"); }
                    void call() {
                        webClient.get().uri("/web");
                        restClient.post().uri("/rest");
                        client.delete().uri("/generic");
                    }
                }
                """));

        // a receiver that is neither a WebClient nor a RestClient (here: Object) is not an HTTP call;
        // same rule that keeps optional.get().uri(..) out
        assertThat(calls).extracting(OutboundHttpEdgeCasesTest::clientMethodAndUrl)
                .containsExactlyInAnyOrder(
                        "webclient GET https://api.example/root/web",
                        "restclient POST https://api.example/root/rest");
    }

    @Test
    void optionalGetUriChainIsNotMistakenForHttpClient(@TempDir Path tmp) {
        var calls = calls(restRepo(tmp.resolve("optional-false-positive"), """
                class Calls {
                    java.util.Optional<UriTarget> optional;
                    void call() { optional.get().uri("http://not-a-call"); }
                }
                interface UriTarget { void uri(String value); }
                """));

        // EXPECTATION?: A fluent HTTP call should require a WebClient/RestClient receiver, not merely get().uri(...).
        assertThat(calls).isEmpty();
    }

    @Test
    void fluentCallsInsideRouteLocatorMethodAreSkipped(@TempDir Path tmp) {
        var calls = calls(restRepo(tmp.resolve("gateway-skip"), """
                class Routes {
                    RouteLocator routes(RouteLocatorBuilder b) {
                        return b.routes().route(r -> r.path("/x").and().method(HttpMethod.GET).uri("lb://target")).build();
                    }
                }
                """));

        assertThat(calls).isEmpty();
    }

    @Test
    void literalQueryStringAndFragmentArePreserved(@TempDir Path tmp) {
        var calls = calls(restRepo(tmp.resolve("query-fragment"), """
                class Calls {
                    WebClient webClient;
                    void call() { webClient.get().uri("https://api.example/items?q=a%20b#section"); }
                }
                """));

        assertThat(calls).singleElement().satisfies(c ->
                assertThat(c.url().text()).isEqualTo("https://api.example/items?q=a%20b#section"));
    }

    private static TempRepo restRepo(Path root, String types) {
        return TempRepo.at(root).app().java("x/Calls.java", "package x;\n" + types);
    }

    private static List<Fact.Call> calls(TempRepo repo) {
        return repo.facts(new OutboundHttpExtractor()).stream()
                .filter(Fact.Call.class::isInstance).map(Fact.Call.class::cast).toList();
    }

    private static String methodAndUrl(Fact.Call call) {
        return call.method() + " " + call.url().text();
    }

    private static String clientMethodAndUrl(Fact.Call call) {
        return call.client() + " " + methodAndUrl(call);
    }
}
