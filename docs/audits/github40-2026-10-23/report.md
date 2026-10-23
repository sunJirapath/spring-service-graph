# spring-service-graph: 40-repository audit

> **Historical baseline:** these results describe scanner `93a1d78f57063866bf075f14d36e81517e9a70b0`, before the eight reported defects were fixed. All eight findings are now fixed; see the [post-fix 80-repository audit](../boot4-80-2026-10-23/report.md) for verification against the updated scanner. The findings and measurements below are preserved as the original baseline record.

What this repo does: `ssg` scans unbuilt Java/Spring repositories into a graph of HTTP, messaging, data and endpoint reach, then exposes that graph through Markdown, diff and MCP. This audit assumes a developer scans multiple public projects locally and may use `--fail-on-breaking` in CI.

**Result: 8 reproducible issues: 4 observed in public repositories (3 extraction errors and 1 diagnostic-evidence error), plus 4 synthetic edge cases.** Production code and existing tests were not changed. Unsupported custom integrations are listed separately.

## Scope and verification

| Check | Result |
|---|---|
| Library | `93a1d78f57063866bf075f14d36e81517e9a70b0`; Temurin JDK 21.0.11 |
| Public corpus | Exactly 40 distinct GitHub repositories, pinned by full commit; all checkouts clean before scans |
| Build systems | 29 Maven-only, 4 Gradle-only, 7 using both; evidence and project scope in [build-systems.json](evidence/build-systems.json) |
| Size | 10 small (1–49), 10 medium (50–249), 10 large (250–999), 10 huge (1,000+) tracked `src/main/java/**/*.java` files |
| Corpus size | 41,845 production Java files; Kotlin excluded from tier calculation and unsupported by the scanner |
| Smaller agents | Three `gpt-6-luna` agents, high effort: HTTP/linking; data/messaging; discovery/config/modules. Coordinator reproduced and consolidated findings. |
| Existing tests | Fresh `gradlew.bat --offline test --rerun-tasks`: **729 passed, 0 failed/errors/skipped** |
| Scanner runs | **80 successful scans**: twice per repository, default profile, no aliases or explicit config repo |
| Determinism | **40/40** graphs and all **550 output files per run** byte-identical between runs |
| Graph integrity | 0 dangling edges; 101,092 edge/finding evidence references checked for existing file and valid line; 2 finding references point to directories (issue 8); no invalid edge references |
| Diff/MCP smoke | **40/40 passed**: run1→run2 diff returns 0 / “No changes”; MCP initialize, tools/list and all 3 tools; incoming counts and depth-1 impact checked against each graph |
| Retained reproductions | **8/8** reproduce the recorded wrong behavior, including a control for issue 1 |

Graphs total 215 service nodes, 11,068 endpoint nodes, 6,796 CALLS, 22,234 REACHES, 2,302 service-scoped tables and 30 topics. These are scanner outputs, not independently established ground truth.

One first JeecgBoot attempt failed in the host JVM because native `malloc` could not allocate 294,416 bytes. After stopping the completed Gradle daemon, two isolated scans at `-Xmx768m` succeeded. The final 80-successful-run count excludes that failed attempt. The diff/MCP harness initially encountered the missing Jeecg graph; all missing checks were subsequently completed.

Most initial scans used `-Xmx1536m` with two scan workers. The isolated retry and retained rerun helpers use 768 MB; remaining audit TEMP/TMP and `java.io.tmpdir` were directed to E:. Per-repository timings below are elapsed process times under concurrent host load, **not an isolated performance benchmark**. The largest measured process peak working set was 1,261.86 MB; this is not a minimum heap requirement.

## Must fix: incorrect extraction and CI decisions

### 1. [P2] Generated application APIs under `org.springframework.*` silently disappear

- **What this is:** The documented OpenAPI fallback recovers mappings from an unbuilt checkout when generated Java interfaces are absent.
- **Problem:** [InboundHttpExtractor.java:172](https://github.com/sunJirapath/spring-service-graph/blob/93a1d78f57063866bf075f14d36e81517e9a70b0/src/main/java/io/github/sunjirapath/ssg/extract/InboundHttpExtractor.java#L172) treats every `org.springframework.*` interface as framework-owned. [PetClinic REST's OwnersApi implementor](https://github.com/spring-petclinic/spring-petclinic-rest/blob/afc8fc1d3b8ec8de69414482b3bd70a1da78cb95/src/main/java/org/springframework/samples/petclinic/rest/controller/v1/OwnerRestControllerV1.java#L54) is application code under that prefix. Nine absent generated APIs bypass both spec fallback and `MISSING_INTERFACE`; the corpus graph contains only the root redirect endpoint despite a checked-in OpenAPI spec.
- **Fix:** Narrow framework exclusions to actual framework packages/types so application interfaces reach the existing fallback. In the retained fixture, changing only the missing interface import from `org.springframework.samples...` to `com.example.api...` restores the endpoint.
- **If skipped:** Impact analysis omits those APIs without warning. Verified on the pinned public checkout and the corrected one-operation fixture (`openapi-framework-prefix`).

### 2. [P2] Elasticsearch's explicit index name is replaced by the entity name

- **What this is:** Elasticsearch `@Document(indexName=...)` identifies the persisted index.
- **Problem:** [DataExtractor.java:117](https://github.com/sunJirapath/spring-service-graph/blob/93a1d78f57063866bf075f14d36e81517e9a70b0/src/main/java/io/github/sunjirapath/ssg/extract/DataExtractor.java#L117) reads only Mongo's `collection`/`value`, even after recognizing the Elasticsearch store. [Mall Swarm's `@Document(indexName="pms")`](https://github.com/macrozheng/mall-swarm/blob/04c442fe318356bae4445a109dde7af297ce1e84/mall-search/src/main/java/com/macro/mall/search/domain/EsProduct.java#L17) becomes `table:mall-search/esProduct` instead of index `pms`. The existing Elasticsearch test checks the store but not the name.
- **Fix:** Read `indexName` for Elasticsearch and retain `collection`/`value` for Mongo. [Spring's annotation contract](https://docs.spring.io/spring-data/elasticsearch/docs/5.1.x/api/org/springframework/data/elasticsearch/annotations/Document.html) identifies `indexName` as the required index name.
- **If skipped:** Data impact queries target a fictitious index. Verified on Mall Swarm and the `elasticsearch-index` fixture (`catalog` expected, `product` observed).

### 3. [P2] MongoRepository loses an entity that has no `@Document`

- **What this is:** MongoDB supports convention-based domain mapping without a `@Document` annotation.
- **Problem:** [DataExtractor.java:149](https://github.com/sunJirapath/spring-service-graph/blob/93a1d78f57063866bf075f14d36e81517e9a70b0/src/main/java/io/github/sunjirapath/ssg/extract/DataExtractor.java#L149) requires annotation-derived mapping before it can add a repository's collection. The [official MongoDB guide's repository](https://github.com/spring-guides/gs-accessing-data-mongodb/blob/1b095970bf66642800d57c54d33cd12e7652440f/complete/src/main/java/com/example/accessingdatamongodb/CustomerRepository.java#L8) uses `MongoRepository<Customer, String>` with a present but unannotated domain class. The graph has no USES edge and incorrectly says the entity was not found in sources.
- **Fix:** When a resolved Mongo repository domain class lacks an explicit mapping, derive the default collection by decapitalizing its class name. Preserve explicit collection overrides; apply the store-specific rule through custom repository bases as well. [Spring's mapping documentation](https://docs.spring.io/spring-data/mongodb/reference/mongodb/mapping/mapping.html) permits annotation-free mapping.
- **If skipped:** Valid Mongo persistence dependencies disappear. Verified on the public guide and `mongo-default`: expected collection `customer`, observed no collection edge plus UNRESOLVED.

### 4. [P2] Retiring the caller and endpoint together still fails the breaking-change gate

- **What this is:** Diff should identify removed endpoints that remain called by the head revision.
- **Problem:** [GraphDiff.java:119](https://github.com/sunJirapath/spring-service-graph/blob/93a1d78f57063866bf075f14d36e81517e9a70b0/src/main/java/io/github/sunjirapath/ssg/diff/GraphDiff.java#L119) checks base callers and whether their service still exists, rather than whether the call remains. An invented base contains caller → orders; head removes that call and `/orders` while keeping both services. Head has no CALLS or findings, but diff reports “is still called by caller” and exits **3**, not 0. An existing comparison test encodes this overly broad expectation.
- **Fix:** Match surviving head calls by target service, method and path, including the service-level call left after an endpoint disappears. Update the test to distinguish an unchanged caller from a surviving service that removed the call.
- **If skipped:** Coordinated, nonbreaking retirements block CI. Synthetic-only; no historical public-repository change pair was evaluated. Retained case: `coordinated-retirement`.

### 5. [P2] A Rabbit post-processor overload publishes the payload as the destination

- **What this is:** Rabbit supports both exchange/key/payload and routing-key/payload/post-processor overloads.
- **Problem:** [MessagingExtractor.java:80](https://github.com/sunJirapath/spring-service-graph/blob/93a1d78f57063866bf075f14d36e81517e9a70b0/src/main/java/io/github/sunjirapath/ssg/extract/MessagingExtractor.java#L80) treats every 3+-argument call as exchange/key/payload. `convertAndSend("orders.key", "payload", postProcessor)` produces topic `payload`. The [RabbitTemplate API](https://docs.spring.io/spring-amqp/api/org/springframework/amqp/rabbit/core/RabbitTemplate.html) defines this overload with the routing key first.
- **Fix:** Disambiguate identifiable post-processor/correlation overloads using argument shape/type. When the overload cannot be established, emit UNRESOLVED rather than a confident destination guessed from the payload.
- **If skipped:** Message consumers and downstream impact are linked to the wrong topic. Synthetic-only: the corpus Rabbit calls checked used other overloads. Retained case: `rabbit-overload`, with real Spring imports.

### 6. [P2] A root URL's query becomes part of its host

- **What this is:** `http://target?x=1` has host `target` and an empty/root path.
- **Problem:** [Linker.java:230](https://github.com/sunJirapath/spring-service-graph/blob/93a1d78f57063866bf075f14d36e81517e9a70b0/src/main/java/io/github/sunjirapath/ssg/link/Linker.java#L230) terminates authority only at `/`. The fixture links this URL to `external:target?x=1`, even though service `target` exposes `GET /`. Adding a slash before `?` avoids the defect. The [JDK URI contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/URI.html) separates authority, path, query and fragment.
- **Fix:** End authority at the first `/`, `?` or `#`; retain root path `/` when no path is supplied, while preserving unresolved-value handling.
- **If skipped:** A local service becomes an external dependency and impact traversal stops. Synthetic-only: no production call with this exact URL shape was established in the 40 snapshots. Retained case: `url-query`.

### 7. [P2] Package-scanned Feign clients in dependency modules are omitted

- **What this is:** A client can be registered by `@EnableFeignClients(basePackages=...)` without a direct source reference from the application module.
- **Problem:** [Discovery.java:214](https://github.com/sunJirapath/spring-service-graph/blob/93a1d78f57063866bf075f14d36e81517e9a70b0/src/main/java/io/github/sunjirapath/ssg/scan/Discovery.java#L214) collects component/mapper scan roots but does not collect Feign package roots; Feign declarations also do not qualify as its library bean roots. The three-module fixture depends on a shared client module and explicitly enables its package, but the resulting graph has only the target EXPOSES edge and no CALLS or warning.
- **Fix:** Include matching Feign declarations from eligible dependency modules for Feign-specific scan roots, using the existing attribution flow. Keep service and implemented-interface boundaries intact.
- **If skipped:** Declaration-based Feign coverage depends on incidental source references. Synthetic-only; Pig and Yudao demonstrate related registration patterns but their checked client edges resolve. Retained case: `feign-package`.

## Should fix: diagnostic evidence

### 8. [P3] Duplicate-service diagnostics link to directories as source files

- **What this is:** A duplicate service warning should identify the configuration or launcher that declares the duplicate name.
- **Problem:** [Pipeline.java:61](https://github.com/sunJirapath/spring-service-graph/blob/93a1d78f57063866bf075f14d36e81517e9a70b0/src/main/java/io/github/sunjirapath/ssg/Pipeline.java#L61) creates `Evidence(repo, service.path(), 1)`, where `service.path()` is a module directory. FTGO's finding points to `ftgo-order-service:1`; Jeecg's points to `jeecg-boot/jeecg-server-cloud/jeecg-system-cloud-start:1`. Both are directories, so the diagnostic cannot provide a source line. The duplicate-name detection and documented merge behavior themselves are not bugs.
- **Fix:** Use the `spring.application.name` configuration evidence, falling back to the application's declaration file/line when its name is inferred.
- **If skipped:** These two real diagnostics have unusable line references. All checked edge references are valid. Also reproduced independently in `duplicate-evidence`.

## Public integration limits, not counted as defects

- **Halo:** 47 `CustomEndpoint` implementations contain 176 literal HTTP-builder calls through third-party `SpringdocRouteBuilder`; these are outside the README's listed direct `RouterFunctions` forms. Its documented standard router declarations are detected. The graph's 17 endpoints therefore do not represent the whole application API.
- **Spring Boot Admin:** Custom `@AdminController` plus `AdminControllerHandlerMapping` routes are outside the documented standard MVC annotations. Its samples yield 0 endpoint nodes despite real server APIs.
- **Jeecg:** Rabbit/Rocket listener test modules without a discovered application are not services in this scan. Their raw annotation counts are not missed-edge counts.
- **Dynamic destinations/SQL, Zuul, custom transports and Kotlin:** Existing support boundaries still apply. Raw grep indicators do not establish per-construct recall. Of 27 drift findings, the sampled Rapter gateway-to-report route was confirmed against a controller with no handler methods; all 27 were not adjudicated.
- **Config sibling paths:** A synthetic `../outside` search-path case reads sibling config. No containment/security contract or public triggering case was established; it is not counted as a confirmed vulnerability or defect.

## The 40 repositories

Size uses tracked Java files under `src/main/java`, not stars, Git history, generated output, binaries or frontend assets. Guide `initial` and `complete` modules count when they meet that rule. This is a purposive compatibility corpus, not a random or statistically representative GitHub sample. Fourteen existing pinned app checkouts were reused; the config-only PetClinic repository was not counted. Thirty-two new candidates were inspected; six surplus candidates were excluded after measured tier counts.

Each repository link below opens its pinned commit. Time is the first successful scan; precise commands, both measurements and findings by kind are in [scan-results.json](evidence/scan-results.json). Representative source checks for every repository are in [source-review.md](source-review.md).

| Tier | Public repository at pinned commit | Build systems | Java files | Services | Endpoints | CALLS | Topics | Tables | Findings | Seconds |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| small | [spring-guides/gs-accessing-data-jpa](https://github.com/spring-guides/gs-accessing-data-jpa/tree/ef48947528dba9021776282e12299e5298e66bca) | Maven + Gradle | 4 | 2 | 0 | 0 | 0 | 1 | 0 | 2.34 |
| small | [spring-guides/gs-accessing-data-mongodb](https://github.com/spring-guides/gs-accessing-data-mongodb/tree/1b095970bf66642800d57c54d33cd12e7652440f) | Maven + Gradle | 4 | 2 | 0 | 0 | 0 | 0 | 1 | 2.09 |
| small | [spring-guides/gs-messaging-rabbitmq](https://github.com/spring-guides/gs-messaging-rabbitmq/tree/91eee3fb69f47cb9a43f925935e3e4643f75d328) | Maven + Gradle | 4 | 1 | 0 | 0 | 1 | 0 | 0 | 1.97 |
| small | [spring-guides/gs-rest-hateoas](https://github.com/spring-guides/gs-rest-hateoas/tree/7207301476baa8263cb1a4a18b921daed52b5762) | Maven + Gradle | 4 | 1 | 1 | 0 | 0 | 0 | 0 | 2.14 |
| small | [spring-guides/gs-rest-service](https://github.com/spring-guides/gs-rest-service/tree/3f4cef01152596c19bc4d37939409358812b1421) | Maven + Gradle | 4 | 1 | 1 | 0 | 0 | 0 | 0 | 2.28 |
| small | [piomin/sample-spring-kafka-microservices](https://github.com/piomin/sample-spring-kafka-microservices/tree/a76daeb2517a2d4656f8f1a8a1b1385bc41c8ea9) | Maven | 13 | 3 | 3 | 0 | 3 | 2 | 0 | 2.78 |
| small | [koushikkothagal/spring-boot-microservices-workshop](https://github.com/koushikkothagal/spring-boot-microservices-workshop/tree/8b01c6d153f7b6aee49fa91a5b1c62c1a0c09408) | Maven | 15 | 4 | 4 | 3 | 0 | 0 | 3 | 1.95 |
| small | [murraco/spring-boot-jwt](https://github.com/murraco/spring-boot-jwt/tree/72b5b8a24db42cdc514c9f6870f046d7039aada9) | Maven | 20 | 1 | 7 | 0 | 0 | 2 | 0 | 3.50 |
| small | [spring-projects/spring-petclinic](https://github.com/spring-projects/spring-petclinic/tree/500158f732419217507c7656904b8e6aa1bcc0d6) | Maven + Gradle | 30 | 1 | 17 | 0 | 0 | 7 | 0 | 2.58 |
| small | [ewolff/microservice-kafka](https://github.com/ewolff/microservice-kafka/tree/103de22da497c631708f0541a0cad0e662d04aa5) | Maven | 37 | 3 | 10 | 0 | 1 | 8 | 0 | 4.67 |
| medium | [spring-petclinic/spring-petclinic-microservices](https://github.com/spring-petclinic/spring-petclinic-microservices/tree/1d76b00d683e86b62867a6bf59530f8ab301244f) | Maven | 53 | 8 | 20 | 19 | 0 | 7 | 1 | 2.97 |
| medium | [shabbirdwd53/spring-boot-microservices](https://github.com/shabbirdwd53/spring-boot-microservices/tree/41d14d882634a6b76b56755fb224bf59593f71ea) | Maven | 65 | 7 | 26 | 11 | 0 | 4 | 2 | 4.81 |
| medium | [eventuate-tram/eventuate-tram-sagas-examples-customers-and-orders](https://github.com/eventuate-tram/eventuate-tram-sagas-examples-customers-and-orders/tree/894f2dbbe9aba2be6129748b6bbf5f30356deb07) | Gradle | 66 | 3 | 16 | 8 | 0 | 2 | 4 | 3.58 |
| medium | [sqshq/piggymetrics](https://github.com/sqshq/piggymetrics/tree/6bb2cf9ddbca980b664d3edbb6ff775d75369278) | Maven | 72 | 9 | 11 | 5 | 0 | 5 | 0 | 6.66 |
| medium | [Rapter1990/SpringBootMicroservices](https://github.com/Rapter1990/SpringBootMicroservices/tree/2c7d011cf661f36257230acfa65087eb34ff07f4) | Maven | 81 | 7 | 24 | 32 | 2 | 3 | 4 | 4.22 |
| medium | [spring-petclinic/spring-petclinic-rest](https://github.com/spring-petclinic/spring-petclinic-rest/tree/afc8fc1d3b8ec8de69414482b3bd70a1da78cb95) | Maven | 87 | 1 | 1 | 0 | 0 | 9 | 0 | 4.20 |
| medium | [eventuate-tram/eventuate-tram-examples-customers-and-orders](https://github.com/eventuate-tram/eventuate-tram-examples-customers-and-orders/tree/8632d030301c2a477141350df1e77f3f991f64e0) | Maven + Gradle | 98 | 5 | 20 | 7 | 0 | 3 | 2 | 4.59 |
| medium | [xuxueli/xxl-job](https://github.com/xuxueli/xxl-job/tree/693d27640be76d02ed7214ede57ce75f7f9ebb71) | Maven | 145 | 3 | 43 | 0 | 0 | 8 | 0 | 12.36 |
| medium | [SelimHorri/ecommerce-microservice-backend-app](https://github.com/SelimHorri/ecommerce-microservice-backend-app/tree/695a6d49a8fb5559b025e22017df1ec657104309) | Maven | 227 | 10 | 150 | 221 | 0 | 11 | 13 | 13.33 |
| medium | [codecentric/spring-boot-admin](https://github.com/codecentric/spring-boot-admin/tree/8562dbace417b31f9f6209dd4410b0f81bf549c0) | Maven | 244 | 9 | 0 | 149 | 0 | 0 | 178 | 13.66 |
| large | [yangzongzhuan/RuoYi-Vue](https://github.com/yangzongzhuan/RuoYi-Vue/tree/13db1fcef36bee9ce45d2d636a1d4e8f5ed5bbc3) | Maven | 266 | 1 | 148 | 0 | 0 | 20 | 0 | 11.44 |
| large | [yangzongzhuan/RuoYi-Cloud](https://github.com/yangzongzhuan/RuoYi-Cloud/tree/ec83e0032b549ecba23f627b270533cb96ab7027) | Maven | 274 | 7 | 142 | 172 | 0 | 20 | 0 | 29.19 |
| large | [microservices-patterns/ftgo-application](https://github.com/microservices-patterns/ftgo-application/tree/558dfc53b11d30a5f1d995c0c6d58d5106c28189) | Gradle | 277 | 7 | 24 | 1 | 0 | 9 | 9 | 13.83 |
| large | [linlinjava/litemall](https://github.com/linlinjava/litemall/tree/a1ef964a718b7277925b19ea26afe78ea3a1d325) | Maven | 304 | 4 | 219 | 0 | 0 | 136 | 34 | 61.70 |
| large | [macrozheng/mall-swarm](https://github.com/macrozheng/mall-swarm/tree/04c442fe318356bae4445a109dde7af297ce1e84) | Maven | 526 | 7 | 250 | 252 | 2 | 82 | 18 | 23.81 |
| large | [apolloconfig/apollo](https://github.com/apolloconfig/apollo/tree/ea356770c6393deccd1dbfb18e5a7607e71f2f9e) | Maven | 529 | 4 | 236 | 7 | 0 | 58 | 38 | 30.73 |
| large | [pig-mesh/pig](https://github.com/pig-mesh/pig/tree/a0d273afd3adc6678b065c0f2d25433541343ad7) | Maven | 545 | 8 | 303 | 51 | 0 | 41 | 5 | 14.84 |
| large | [dromara/RuoYi-Vue-Plus](https://github.com/dromara/RuoYi-Vue-Plus/tree/9227f626a3a37e1546d6c291ed398183e94df92b) | Maven | 689 | 4 | 286 | 0 | 0 | 28 | 3 | 56.81 |
| large | [paascloud/paascloud-master](https://github.com/paascloud/paascloud-master/tree/781281a9503332ed3cef44ea618349d14230a127) | Maven | 704 | 10 | 190 | 73 | 1 | 41 | 7 | 41.94 |
| large | [jeecgboot/JeecgBoot](https://github.com/jeecgboot/JeecgBoot/tree/4998e645db547ff614b6568df3895bf5586effbf) | Maven | 946 | 10 | 988 | 123 | 0 | 106 | 32 | 26.50 |
| huge | [halo-dev/halo](https://github.com/halo-dev/halo/tree/eb235e17f480bdf25547147c88dfb9a5b3e0effd) | Gradle | 1,002 | 1 | 17 | 1 | 0 | 1 | 7 | 39.70 |
| huge | [shopizer-ecommerce/shopizer](https://github.com/shopizer-ecommerce/shopizer/tree/6a4a0a65a3408ee8f62597b51d1b3aac24b77dee) | Maven | 1,167 | 1 | 321 | 0 | 0 | 82 | 0 | 32.06 |
| huge | [dataease/dataease](https://github.com/dataease/dataease/tree/4a395b16956b5f99da08feba143291a6a1ef8a1f) | Maven | 1,168 | 1 | 246 | 0 | 0 | 70 | 1 | 38.89 |
| huge | [apache/dolphinscheduler](https://github.com/apache/dolphinscheduler/tree/c70003c2aa325a143e033e6b753ac7de30ec31a9) | Maven | 1,959 | 6 | 239 | 0 | 0 | 296 | 73 | 25.12 |
| huge | [metersphere/metersphere](https://github.com/metersphere/metersphere/tree/53df526e2fb08bdf933662acf7eb5ae0f1bd5e3a) | Maven | 2,476 | 1 | 1,053 | 2 | 5 | 162 | 2 | 48.25 |
| huge | [alibaba/nacos](https://github.com/alibaba/nacos/tree/ef52650006d1086bb1007a223e35875cb63ec146) | Maven | 3,039 | 10 | 374 | 0 | 0 | 46 | 132 | 102.94 |
| huge | [apache/fineract](https://github.com/apache/fineract/tree/71681f2d8828f377e7a5b93dba3721384db1daaf) | Gradle | 5,488 | 1 | 968 | 2 | 2 | 304 | 428 | 64.19 |
| huge | [thingsboard/thingsboard](https://github.com/thingsboard/thingsboard/tree/4dc94037fa1f58e87e028cf9480514a6ea0ce565) | Maven | 5,493 | 24 | 914 | 1,702 | 0 | 116 | 1,801 | 100.83 |
| huge | [YunaiV/ruoyi-vue-pro](https://github.com/YunaiV/ruoyi-vue-pro/tree/555bd9749261ce77d11c4330172d27ca381ea22e) | Maven | 6,787 | 2 | 347 | 1 | 6 | 48 | 8 | 71.56 |
| huge | [YunaiV/yudao-cloud](https://github.com/YunaiV/yudao-cloud/tree/0757f0597e44357d5f2777a2f9e1370cc1534d5f) | Maven | 6,933 | 25 | 3,449 | 3,954 | 7 | 564 | 35 | 85.56 |

## Evidence and reproduction

Historical execution and cleanup paths are normalized relative to the audit workspace
for publication. Original graph bytes and their SHA-256 inventories are unchanged.

- [Manifest](manifest.json) and [repository CSV](repositories.csv): exact commits, source sizes and checkout provenance.
- [Build-system evidence](evidence/build-systems.json): verified build descriptors at each pinned commit; 29 Maven-only, 4 Gradle-only and 7 repositories with both.
- [Source review](source-review.md): 20 small/medium, 10 large and 10 huge representative checks.
- [Metadata](evidence/metadata.json): test totals, scanner JAR hash, agent models, resource caveats and initial environment failure.
- [Original graph outputs](evidence/scan-graphs.zip): all 40 first-run graphs, compressed; [SHA-256 inventory](evidence/output-sha256.json) records all 550 files in both runs.
- [Diff/MCP results](evidence/diff-mcp-results.json): 40 graph-backed smoke checks; these do not certify every protocol edge case.
- [Reproduction results](evidence/repro-results.json) and [outputs](evidence/reproduction-outputs.zip): eight recorded wrong results. These are successful defect reproductions, not passing tests of intended behavior.

The retained reproduction helper now defaults to `--expect fixed`. To verify the corrected behavior on the current fixed source:

```powershell
.\gradlew.bat installDist
python docs\audits\github40-2026-10-23\reproduce.py --expect fixed
```

To reproduce the original wrong results, install a distribution built from baseline commit `93a1d78f57063866bf075f14d36e81517e9a70b0` into this workspace's `build/install/ssg`, then run `python docs\audits\github40-2026-10-23\reproduce.py --expect baseline`. The archived baseline outputs remain the recorded evidence; do not interpret the baseline mode as a passing test of intended behavior.

To re-acquire exactly the same 40 commits and repeat all 80 source-only scans, use `python docs\audits\github40-2026-10-23\run-corpus.py`. That helper runs serially at 768 MB and directs its own TEMP/TMP to E:; it does not modify Windows user settings. Its syntax and safeguards were checked, but its full network acquisition was not repeated after cleanup; the original acquisition/scanning campaign is the evidence above.

## Cleanup and boundaries

The user's cleanup request was explicitly limited to this audit. New audit clones, duplicate scan directories, temporary reproduction outputs and JVM crash/replay files are removed after preserving this compact report, minimal invented fixtures and compressed evidence. Pre-existing `bench/` checkouts and global tool caches are preserved. See [cleanup.json](evidence/cleanup.json) for the completed removal record. No Windows-wide TEMP/TMP setting was changed.

**Verdict:** Useful for supported patterns, but fix issues 1–3 before trusting graph completeness on the affected stacks, and issue 4 before using the diff exit code as a strict CI gate.

**Not checked:** Downloaded projects were never built or run. Source sampling does not establish full precision/recall or runtime behavior; no CVE/dependency security audit was performed.
