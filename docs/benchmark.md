# Benchmark: public fleets

Run on 2026-10-18 with `ssg` 0.1.0-SNAPSHOT. Recreate the clones with `sh scripts/bench-clone.sh`;
they land in the git-ignored `bench/` folder at pinned commits.

| Fleet | Commit | Command |
|---|---|---|
| spring-petclinic-microservices + its config repo | `1d76b00` / `323993c` | `ssg scan --repos bench/petclinic --config-repo bench/petclinic-config --out build/bench/petclinic` |
| sqshq/piggymetrics | `6bb2cf9` | `ssg scan --repos bench/piggymetrics --config-repo bench/piggymetrics/config/src/main/resources/shared --out build/bench/piggymetrics` |
| ewolff/microservice-kafka | `103de22` | `ssg scan --repos bench/microservice-kafka --out build/bench/microservice-kafka` |

## Results

| Fleet | Services | Endpoints | CALLS | Topics (produce/consume) | Tables (owns/uses) | Findings |
|---|---|---|---|---|---|---|
| petclinic | 8 | 20 | 19 | 0 | 7 / 6 | 1 UNRESOLVED |
| piggymetrics | 9 | 11 | 5 | 0 | 0 / 5 | 0 |
| microservice-kafka | 3 | 10 | 0 | 1 (1 / 2) | 0 / 8 | 0 |

### How the results were checked

- **Petclinic and piggymetrics:** I checked every CALLS edge and every finding by hand against
  the source.
- **Tables, topics and endpoints:** I spot-checked the customers-service and order pages and
  the topic table. I did not check them exhaustively.

### What the scanner now gets right

**Petclinic**
- Gateway YAML routes under the Spring Cloud 2025 key `spring.cloud.gateway.server.webflux.routes`.
  `lb://` targets and `StripPrefix=2` are applied, so each route reaches every endpoint of its
  target.
- WebClient calls to `http://customers-service/...`, resolved by host = service name (D2).
- `hostname + "pets/visits"`, folded through a non-final field initializer. This is `RESOLVED`.
- genai-service's `RestClient` calls through
  `discoveryClient.getInstances("customers-service")` inside a private helper, mapped to
  `lb://customers-service`.
- One call that is correctly `UNRESOLVED`: `"/owners/" + ownerId + "/pets"`, where `ownerId` is a
  runtime value. It still matches `POST /owners/{ownerId}/pets`.
- The `@LoadBalanced RestTemplate` bean, which has no call site, produces no edge, which is
  correct.
- Table ownership from both `db/hsqldb/schema.sql` and `db/mysql/schema.sql`, merged into one
  edge with two evidence entries.

**Piggymetrics**
- All 4 Feign clients.
  - `@FeignClient(url = "${rates.url}")` resolves through the config folder to the external
    host `api.exchangeratesapi.io`.
- Context paths from the config server (`server.servlet.context-path: /uaa`, ...) are applied
  to the target endpoints. All calls then match.

**microservice-kafka**
- `kafkaTemplate.send("order", ...)` connects to two `@KafkaListener(topics = "order")`
  consumers.
- `@SpringBootApplication` classes under `src/test` are ignored.

## Precision issues found, and what happened to them

| # | Fleet | Symptom | Cause | Status |
|---|---|---|---|---|
| 1 | petclinic | False CONTRACT_DRIFT: `GET /pets/visits` on visits-service | `VisitResource` has two `read(..)` overloads. Handler methods were de-duplicated by name and arity, so one was dropped. | **Fixed.** The key now includes parameter types. Test `overloadedHandlerMethodsAreAllExposed`. |
| 2 | petclinic | 3 genai calls `UNRESOLVED` to `external:unresolved` | The base URL comes from a zero-arg helper, `getCustomerServiceUri() { return discoveryClient.getInstances(..)...; }` | **Fixed.** Zero-arg same-class helpers with a single `return` are folded. Test `zeroArgHelperReturningAnExpressionIsFolded`. |
| 3 | piggymetrics | 3 false CONTRACT_DRIFT findings (e.g. `POST /uaa/users`) | Context paths live in the config server module (`config/src/main/resources/shared`), not in each service. | **Works with `--config-repo` pointing at that folder.** Without it, drift is reported, which is honest but noisy. |
| 4 | piggymetrics | Gateway shows no routes | It uses Netflix Zuul (`zuul.routes.*`, Boot 2.0) | **Not supported.** Zuul is end-of-life, and Spring Cloud Gateway (D1) is covered. |
| 5 | petclinic | Config server is named `spring-petclinic-config-server` | It has no `spring.application.name`, so the directory name is used (D8) | Harmless; as designed. |
| 6 | petclinic | 14 of the gateway's 15 CALLS edges come from 4 routes | A `/**` route links to every endpoint of its target | By design, for impact analysis ("who can reach X"). The page groups them by target, so it stays readable. |

## Known gaps that the benchmarks did not exercise

The synthetic fleet covers these instead:
- `@HttpExchange`
- Cloud Stream bindings
- Liquibase
- OpenAPI fallback (D5)
- Constants across modules

Covered by unit tests:
- The Java `RouteLocator` DSL (`GatewayJavaDslTest`). This test found that rewrite replacements such as `"/${s}"` were being resolved as property placeholders. That is now fixed.
- Flyway `V*__*.sql`, multi-line Liquibase XML, `@Document` collections and JPA default naming (`DataExtractorTest`).

Still not covered:
- Shared-database detection (tables are scoped per service).

## Phase 2: endpoint reach on the benchmarks

| Fleet | Endpoints | Endpoints with reach | REACHES edges |
|---|---|---|---|
| petclinic | 20 | 13 | 15 |
| piggymetrics | 11 | 10 | 13 |
| microservice-kafka | 10 | 9 | 10 |

I spot-checked the chains against the source. Examples:
- piggymetrics `POST /accounts` → `AccountServiceImpl.create` → `AuthServiceClient.createUser` → auth-service `POST /uaa/users`
- microservice-kafka `POST /` → `OrderService.order` → `fireOrderCreatedEvent` → topic `order`
- petclinic gateway `GET /api/gateway/owners/{ownerId}` → both the customers and visits WebClient calls

Endpoints without reach are:
- gateway routes (from config, no Java handler)
- the gateway fallback controller
- genai's chat endpoint, whose work happens inside Spring AI tool callbacks that are not followed

| # | Symptom | Cause | Status |
|---|---|---|---|
| 7 | petclinic `GET /petTypes` reaches table `pets`, but its `@Query` reads `types` | A repository maps only to its entity's table | Known limit. A fix would need `@Query` SQL/JPQL parsing. |
| 8 | Each endpoint gets only one chain per sink (the shortest) | BFS keeps the first path found | By design, to keep pages readable |

## Field test 1: a private one-repo service (2026-10-19, snapshot 56e97fa)

An agent ran ssg on a real Spring Boot 4 / Java 21 service that is not public. The fleet was one repo with one service, 124 endpoints (116 of them on openapi-generator `interfaceOnly` interfaces), 35 Flyway tables and `JdbcTemplate` data access. It followed `docs/field-test-prompt.md`, and only anonymized results were kept.

What already worked:
- endpoints: 124/124 with 0 false positives, built or unbuilt
- Flyway ownership: 35/35
- the diff of a real merge: exact
- output: deterministic
- secrets: none leaked

What it found, and what changed:

| Finding | Change |
|---|---|
| Nested git worktrees (`.claude/worktrees/*`, each with a `.git` file) were scanned as part of the repo. Same-name apps merged silently, a stale interface copy won, 3 endpoints were dropped and memory tripled. | One shared directory walk skips folders below a repo root that have their own `.git`. A `DUPLICATE_SERVICE` finding is raised. Types with the same FQN resolve to the copy in the referring module. |
| The OpenAPI fallback added every operation of the spec, including tags no controller implements. Endpoints were duplicated and counts were inconsistent. | The fallback keeps operations whose tag (or first path segment) names a missing interface. For untagged specs it keeps operations whose interface is not in the service's sources. Duplicates of controller endpoints are dropped. |
| Evidence pointed at the first annotation (`@Operation`), up to 30 lines above the mapping, and only at generated files. | Evidence is the mapping annotation, plus the handler method as a second entry. |
| 32/35 tables were used through `JdbcTemplate` SQL (251 sites), so there were no `USES` or `REACHES` edges and impact analysis failed. | New `JdbcExtractor` with `--sql-methods` for wrapper methods. Reach now reaches tables through JDBC. |
| MCP lookups needed the exact `{var}` names; concrete paths failed. | Paths match templates as the linker does. `impact_of` reports the depth it used and notes clamping. |
| Page links depend on where the fleet is checked out. | `--link-base` URL template. |
| `OutOfMemoryError` at `-Xmx256m` for 5 MB of source. | Measured at about 80 MB of heap per MB of Java in the largest repo; documented. A structural reduction is still open. |
| Reach became quadratic once JDBC sinks existed (found while testing the fix: 140 s for 1.7 MB). | Sinks and methods are indexed once: 3.5 s, identical output. |

## Messy real-world code bases (2026-10-19, ssg at 7c8de01)

These are three large public code bases, picked because they look like company code rather than samples. They are cloned by `scripts/bench-clone.sh`, which sets `core.longpaths` because dolphinscheduler has paths over 260 characters. Each was scanned on its own with no aliases or config repo:
```bash
ssg scan --repos bench/<name> --out build/bench/<name>
```
Time was measured at `-Xmx8g` on a 16-thread Windows machine. "Heap needed" is the smallest `-Xmx` that worked. yudao-cloud and dolphinscheduler were scanned again after the Maven module dependency fix (gaps #1 and #4 below), with master at `3a4c3a7` as the before build. Their changed counts show the before value in brackets, and both times were taken at `-Xmx6g` in that run, on a busier machine. fineract was not scanned again.

| Code base | Commit | Main Java | Apps | Services | Endpoints | CALLS | Topics | Tables | Findings | Time | Heap needed |
|---|---|---|---|---|---|---|---|---|---|---|---|
| YunaiV/yudao-cloud | `0757f05` | 21 MB | 25 | 25 | 3392 | 731 (479) | 3 (1) | 564 (547) | 36 CONTRACT_DRIFT, 18 (15) UNRESOLVED | 66 s (59 s) | 1.5 GB (OOM at 1 GB) |
| apache/dolphinscheduler | `c70003c` | 7 MB | 8 in 6 modules | 6 | 239 | 0 | 0 | 296 (86) | 0 | 16 s (14 s) | 384 MB (OOM at 256 MB) |
| apache/fineract | `71681f2` | 28 MB | 1, no `@SpringBootApplication` | 1 | 3 | 2 | 2 | 304 (308) | 422 (425) UNRESOLVED | 14 s | 2 GB (OOM at 1.5 GB) |

Notes on the counts:
- yudao-cloud's 479 CALLS were 418 Feign calls, 36 gateway routes and 25 calls from HTTP clients. Before gap #1 was fixed there were 61, with no Feign calls. The 252 new ones come from the `*CommonApi` Feign clients in `yudao-common` and its starters, which every service now gets through its Maven dependencies.
- Its first topic is produced from `config.getTopic()`, so it is UNRESOLVED. The 2 new topics, 3 new PRODUCES and 1 CONSUMES come from starters too, and so do the 3 new UNRESOLVED findings.
- Table nodes are per service. dolphinscheduler's 296 are the same 73 tables in each of the 4 services that depend on `dolphinscheduler-dao` (api, master, alert and tools), plus 4 in worker-server. All 296 have an OWNS edge from the `CREATE TABLE`s in `-dao` (gap #4). The 86 before were the tables that those services' entities and mapper XML named; the 43 in the first run predate gap #12.

### Recall against grep

These counts are of files under `src/main/java`.

| Construct | yudao-cloud | dolphinscheduler | fineract |
|---|---|---|---|
| Controller classes → endpoints | 657 → 3392 endpoints in 24/25 services | 35 → 239 | 3 + 170 JAX-RS `@Path` → 3 (JAX-RS is not read) |
| `@FeignClient` → CALLS | 47 → 670 CALLS from 24 services, all RESOLVED (418 from 23 before the Maven dependency fix; was 0, gap #1) | — | — |
| `@TableName` (MyBatis-Plus) → tables | 547 → 547, plus 17 from mapper XML (gap #12) = 564, used by 22 services, 8027 REACHES (was 0, gap #3) | 48 → 86 table nodes in 4 services, 296 after the DDL fix (was 0, gap #3; needs gap #1, since the mappers live in `-dao`) | — |
| `@Entity` / `extends *Repository<` → tables | — | — | 254 / 200 → 304 tables, 275 USES, 62 REACHES (was 0, gap #5; 308, 277 and 58 before gaps #12 and #13) |
| `JdbcTemplate` / `JdbcClient` | 3 → 1 UNRESOLVED (a `--sql-methods` hint) | — | 203 → 422 UNRESOLVED, mostly SQL not resolved statically |
| `KafkaTemplate` / `@KafkaListener` | 6 → 4 PRODUCES, 1 CONSUMES (was 1 PRODUCES) | — | 4 → 0 |
| `RestTemplate` / `WebClient` / `RestClient` | 34 → 25 CALLS | — | 4 → 0 |
| `CREATE TABLE` / `createTable` | 863 in root `sql/` → 0 | 378 in `dolphinscheduler-dao` (several databases) → 296 OWNS (was 0, gap #4) | 741 + 320 Liquibase → 244 OWNS |

The services are named from `spring.application.name`.

### What worked

**yudao-cloud**
- All 25 apps were found.
- 140 `/rpc-api/**` endpoints were found. Each server's `*ApiImpl` `@RestController` takes its mappings from a Feign interface in a sibling `-api` module, and these were resolved across the modules.
- Gateway YAML routes with the custom `grayLb://` scheme were linked to their services.
- WebClient calls to 3 external hosts resolved through constants.
- 11 `baseUrl + "/..."` calls were kept as UNRESOLVED rather than dropped.

**dolphinscheduler**
- Its 8 apps became 6 services. The 3 tool apps share one module.

**Both**
- No crashes and no stderr warnings.

### Gaps, by impact

| # | Code base | Symptom | Cause |
|---|---|---|---|
| 1 | yudao-cloud | **Fixed.** 0 of 47 Feign clients became CALLS, so the whole RPC graph between its 25 services is missing. | The clients live in shared `yudao-module-*-api` and `yudao-common` modules. Those have no app, and the services pull them in as Maven dependencies. ssg attributes code to a service only by module folder. |
| 2 | yudao-cloud | 36/36 CONTRACT_DRIFT findings are false positives, for example `/admin-api/crm/**` matches nothing on crm-server. | A starter adds `/admin-api` and `/app-api` in code, with `RequestMappingHandlerMapping.setPathPrefixes`, keyed by controller package. ssg sees only the annotation paths. |
| 3 | yudao-cloud, dolphinscheduler | No tables at all. | They use MyBatis-Plus (`@TableName` entities, `BaseMapper<T>` mappers) instead of JPA or Spring Data. |
| 4 | yudao-cloud, dolphinscheduler | **Fixed** for dolphinscheduler: 296 OWNS. Still open for yudao-cloud. No OWNS, although 1,200+ `CREATE TABLE` statements exist. | The DDL lives outside every service module: in the root `sql/` folder, or in a shared `-dao` module. This has the same root cause as #1. |
| 5 | fineract | The whole repo is dropped, with one `warn: no @SpringBootApplication`. | It boots through `SpringApplicationBuilder` with an `@EnableAutoConfiguration`-style configuration class. Even with that fixed, its 170 JAX-RS resources would give no endpoints. |
| 6 | dolphinscheduler, yudao-cloud | 5 of 6 dolphinscheduler services and several yudao-cloud services show no outbound edges. | They use transports ssg does not read: dolphinscheduler's own Netty RPC, plus RocketMQ, RabbitMQ and Redis streams (15 files in yudao-cloud). These are out of scope. |
| 7 | dolphinscheduler | 1 noisy MISSING_INTERFACE finding. | The controller implements Spring Boot's own `ErrorController`. Framework interfaces carry no mappings and should not be reported. |
| 8 | all | 50–70 MB of heap per MB of Java. Fineract runs out of memory at 1 GB although it yields no service. | Every source file is parsed before discovery, so even a repo with no app gets fully parsed. This adds to the open memory item from field test 1. |

**Gap #1 is fixed.** A service in a multi-app repo now also gets the library sources it uses. A library source is one in a module with no app, and it counts as used when the service's own code names one of its types. That includes `@EnableFeignClients(clients = X.class)`. The rule is applied again through the library files it pulls in. A type that the service implements or extends is left out, because a server's `*ApiImpl` is the implementation, not a client. Evidence still points at the interface in its `-api` module.
- yudao-cloud: 44 of the 47 Feign interfaces are attributed. They give 418 CALLS, every one linked to a real endpoint, and there are no self-calls.
- The other 3 (`ApiAccessLogCommonApi`, `ApiErrorLogCommonApi`, `TenantCommonApi`) are used only from starter auto-configurations, which no service names. See the Maven module dependency fix below.
- Shared Java such as dolphinscheduler-dao's entities and mappers now reaches the services that use it.
- The three public fleets above gave the same counts as before.

**Maven module dependencies are now read (gaps #1 and #4, and field test 3 rows 1, 4 and 12).** `Modules` reads every `pom.xml` `<dependency>` and Gradle `project(':a:b')` in the repo. It walks a service's module dependencies transitively, through parent poms, and skips test scope, `<exclusions>` and commented-out entries. It stops at modules that are apps themselves. In those library modules:
- Every bean (`@Component`, `@Service`, `@Repository`, `@Configuration`, `@AutoConfiguration`, `@Mapper`, `@ControllerAdvice`, `@RestControllerAdvice`) is a root for the usage walk of gap #1, as if the service named it (`Discovery.used`, `Discovery.java:84`). Feign clients, HTTP clients, Kafka and data access reached from those beans count for every dependent.
- Controllers count only from a module the launcher depends on directly, since a service seldom serves the endpoints of a library's own libraries.
- A type with the same FQN in two modules resolves to the copy in the service's own dependencies (`JavaIndex.preferring`, `JavaIndex.java:181`). jeecgboot has `ISysBaseAPI` both as a local interface and as a Feign client; without this, the local one gave self-calls.
- DDL in a library counts as OWNS for every dependent. Mapper XML in a library counts only when its `namespace` is a type the service uses, so generated mappers nobody injects add nothing (`DataExtractor.migrations`, `DataExtractor.java:129`).

Tests are in `ModuleDependencyTest`: `controllersCountOnlyFromModulesTheLauncherDependsOnDirectly`, `beanInTransitiveStarterMakesItsFeignClientCallsCount`, `sameNamedInterfaceResolvesToTheCopyInTheServicesOwnDependencies`, `sharedDdlAndUsedMapperXmlCountForEveryDependent`, `commentedOutExcludedAndTestScopedDependenciesAreIgnored` and `gradleProjectDependenciesAreFollowed`.
- yudao-cloud: CALLS 479 → 731. The 3 starter clients above are now attributed: every service gets the `*CommonApi` calls. There are still no self-calls.
- dolphinscheduler: OWNS 0 → 296 (gap #4).
- Still open: DDL in a root `sql/` or `db/` folder that is no module (yudao-cloud's 863, and mall-swarm, pig and jeecgboot below). Nothing decides which one service owns a shared schema either: every dependent gets OWNS.
- Not read: Gradle `projectDir` remaps, Maven profiles, and exclusions that apply to the whole closure rather than one edge.

**Gaps #3, #5 and #7 are fixed.**
- #3: `@TableName` classes are tables, named by the annotation or else in snake case, as MyBatis-Plus does. Interfaces extending `BaseMapper<T>` or `MPJBaseMapper<T>`, directly or through base interfaces in sources such as yudao's `BaseMapperX<T>`, are treated as Spring Data repositories. They give USES edges, and REACHES from endpoints whose handlers call them. A `*Mapper` with any other base, such as a MapStruct `EntityMapper<D, E>`, is not a repository. dolphinscheduler's tables need gap #1's fix as well: without it they are 0, because its entities and mappers live in the shared `-dao` module.
- #5: a module is also an app when a static `main` method calls `SpringApplication.run`, or creates a `SpringApplication` or a `SpringApplicationBuilder`. Fineract now gives one service. Its endpoints are still 3, because the 170 JAX-RS resources are not read.
- #7: MISSING_INTERFACE skips interfaces imported from `org.springframework`, `java`, `javax` and `jakarta`, such as `ErrorController`. dolphinscheduler has no findings now.

The JDBC support from field test 1 is now exercised by fineract. Its 203 `JdbcTemplate` files give 422 UNRESOLVED findings: 388 are SQL that cannot be resolved statically, and 6 are `--sql-methods` hints.

## Eight more messy code bases (2026-10-19)

These are eight more public code bases, cloned by `scripts/bench-clone.sh` and scanned the same way, one at a time. Each was scanned twice:
- **Before**: with the committed ssg at `dcc9f22`.
- **After**: with the fixes for gaps #1, #3, #5 and #7 above.

"Heap needed" is the smallest `-Xmx` that worked with the before build; 256 MB was the smallest size tried. Time is from the after run at `-Xmx6g`. A count that changed shows the before value in brackets. mall-swarm, pig, jeecgboot and metersphere were scanned again after the Maven module dependency fix (field test 2), with master at `3a4c3a7` as the before build; there the bracket is the master value. Those four still fit in the heap shown. At `-Xmx6g` their times went from 10, 7, 14 and 31 s to 11, 9, 34 and 37 s on that busier machine.

| Code base | Commit | Main Java | Apps | Services | Endpoints | CALLS | Topics | Tables | Findings | Time | Heap needed |
|---|---|---|---|---|---|---|---|---|---|---|---|
| macrozheng/mall-swarm | `04c442f` | 2.9 MB | 7 | 7 | 250 | 252 (253) | 0 | 81 (51) | 1 UNRESOLVED | 5 s | 256 MB |
| paascloud/paascloud-master | `781281a` | 1.6 MB | 10 | 10 | 189 | 43 (0) | 0 | 41 (4) | 0 | 4 s | 256 MB |
| pig-mesh/pig | `a0d273a` | 1.6 MB | 8 | 8 (7) | 303 (294) | 27 (24) | 0 | 38 (0) | 5 CONTRACT_DRIFT (0) | 3 s | 256 MB |
| microservices-patterns/ftgo-application | `558dfc5` | 0.3 MB | 8 | 7 (6) | 19 (16) | 1 | 0 | 9 (6) | 1 UNRESOLVED, 1 DUPLICATE_SERVICE (was 1 UNRESOLVED) | 3 s | 256 MB |
| shopizer-ecommerce/shopizer | `6a4a0a6` | 3.5 MB | 1 | 1 | 321 | 0 | 0 | 82 (81) | 0 | 15 s (62 s, gap #10) | 256 MB |
| jeecgboot/JeecgBoot | `4998e64` | 4.8 MB | 11 | 10 | 987 (74) | 123 (2) | 0 | 105 (48) | 14 CONTRACT_DRIFT, 12 UNRESOLVED, 1 DUPLICATE_SERVICE (was 1 DUPLICATE_SERVICE) | 6 s | 384 MB |
| metersphere/metersphere | `53df526` | 12.1 MB | 1 | 1 | 1053 | 2 | 5 | 161 (0) | 2 UNRESOLVED (was also 1 MISSING_INTERFACE) | 15 s | 1 GB |
| thingsboard/thingsboard | `4dc9403` | 21.8 MB | 25 | 21 (16) | 850 (0) | 26 (3) | 0 | 31 (6) | 46 UNRESOLVED, 3 DUPLICATE_SERVICE (11, 1) | 50 s | 2 GB |

Notes on the counts:
- mall-swarm's 253 CALLS are 245 gateway routes and 8 Feign calls.
- paascloud's 43 new CALLS are EXACT. They come from Feign clients in its shared `-provider-api` modules, which gap #1's fix now attributes.
- pig's 24 new CALLS are 13 RESOLVED Feign calls (11 to `pig-upms-biz`, 2 to `pig-auth`) and 11 EXACT gateway routes (gap #9). The 6 new endpoints are the gateway's route patterns, such as `* /admin/**`. Its new service is `pig-register` (gap #5).
- ftgo's 8th app is not a separate service: the delivery service sets `spring.application.name=ftgo-order-service`, so the 2 merge and give a correct DUPLICATE_SERVICE finding.
- After the Maven module dependency fix: mall-swarm's 30 new tables are from `mall-mbg` mapper XML that a service's mappers use (`PmsBrandMapper.xml:84`). pig's 9 new endpoints are `/oss/*` from `OssEndpoint.java:57` in `pig-common-oss`, which `pig-upms-biz` depends on directly, and its 3 new CALLS are `POST /log/save` from `pig-auth`, `pig-codegen` and `pig-quartz` through `RemoteLogService.java:43`, which the `pig-common-log` listener (`SysLogListener`) uses. metersphere's 161 tables are its `framework/domain` mapper XML and Flyway DDL, with 159 OWNS and 150 USES.
- jeecgboot: `jeecg-system` gets 801 endpoints from `jeecg-system-biz` and `jeecg-demo` 112. The 121 new CALLS include 114 from `jeecg-demo` to `jeecg-system` through `ISysBaseAPI`. The 14 CONTRACT_DRIFT findings are `jeecg-demo` Feign calls to `/online/api/cgform/*`, `/online/api/cgreport*`, `/airag/api/*` and `/getDepartPathNameByOrgCode`. `jeecg-online` is not in the repo. `jeecg-boot-module-airag` is, but `jeecg-system-start` depends on it only through `jeecg-system-biz`, so its controllers do not count. The 12 UNRESOLVED are `URI.create(url)` in `RestUtil.java:240` and `:313`, used by 5 services, plus 2 in `jeecg-system`.
- In thingsboard, `application` now gives 849 endpoints. It boots through `@SpringBootConfiguration` with a `main` method (gap #5).

### Recall against grep

These counts are of files under `src/main/java`.

| Construct | mall-swarm | paascloud | pig | ftgo | shopizer | jeecgboot | metersphere | thingsboard |
|---|---|---|---|---|---|---|---|---|
| Endpoints | 250 | 189, 39 of them inherited from `-api` Feign interfaces | 288 → 303 (9 `/oss/*` from `pig-common-oss`) | 13 of 13 controller, plus 3 from the gateway DSL | 321 | 74 → 987; 80 of 100 controllers are in modules with no app (Maven module fix) | 1053, an exact match | 0 → 850 (gap #5) |
| `@FeignClient` → CALLS | 8 | 20 in `-api` → 0 → 43 (gap #1) | 10 in `-upms-api` → 0 → 13 (gap #1) → 16 (Maven module fix) | — | — | 6 → 2 → 123 (Maven module fix) | — | — |
| MyBatis-Plus `@TableName` / `BaseMapper` | — | — | 16 / 37 → 0 → 38 USES (gap #3) | — | — | 50 / 65 → 0 (gaps #1 and #3, see below) → 105 tables (Maven module fix) | — | — |
| `@Entity` → tables | 3 | 4 | — | 6 → 9 | 81 → 82 | — | — | 118 in `dao/` → 6 → 33 |
| `CREATE TABLE` outside services (gap #4) | 76 in `mall.sql` | — | 60 in `db/` | — | — | 143 in the root `db/` | 156 Flyway tables in `framework/domain` → 159 OWNS (Maven module fix) | — |
| RabbitMQ, RocketMQ, Redis or other messaging (gap #6) | RabbitMQ | RocketMQ | — | 95 files of Eventuate Tram | — | — | — | TbQueue, 7 RabbitMQ files |

### What worked

- None of the 16 scans crashed.
- metersphere's 1053 endpoints match grep exactly, and its 5 Kafka topics are right.
- Endpoints that a controller inherits from a Feign interface in a sibling `-api` module were found in paascloud (39 of them).
- shopizer's 81 entities became 81 tables, one USES edge each, with no extra edges from repositories. Gap #12 added an 82nd, `USERCONNECTION`, from a `@MappedSuperclass @Table` (`AbstractUserConnectionWithCompositeKey.java:12`).
- Spot checks (5 to 7 endpoints, and every CALLS edge up to 5, per repo) were all right, except for the template match in gap 11 below.
- Gap #1's fix gave 56 new CALLS edges with no self-calls. Gap #5's fix found 7 more services. The Maven module fix gave 121 more in jeecgboot and 3 in pig, again with no self-calls.

### Gaps, by impact

Rows 1–8 are the gaps listed above, as they show up in these repos. Rows 9 and up are new.

| # | Code base | Symptom | Cause |
|---|---|---|---|
| 1 | jeecgboot | **Fixed.** `jeecg-system-start` now has 801 endpoints, from `jeecg-system-biz`. It had 0, even after gap #1's fix. 80 of 100 controllers are in `jeecg-system-biz` and other modules with no app. | The launcher names no type from `jeecg-system-biz`. The only link is the Maven dependency in its `pom.xml`, which gap #1's fix did not read. Maven module dependencies are now read (see field test 2). |
| 3 | pig | **Fixed.** There were 21 UNRESOLVED of the form "repository SysJobMapper: entity SysJob not found in sources". Now they are 0, and pig has 38 tables, `sys_job` among them. | The entity is in the sources (`SysJob.java:41`, `extends Model<SysJob>`), but it has no `@TableName`. A `BaseMapper` entity without `@TableName` now maps to its snake-case name, as MyBatis-Plus does. Spring Data repositories get no such fallback. |
| 4 | mall-swarm, pig, jeecgboot, metersphere | **Fixed** for metersphere: 159 OWNS from the Flyway DDL in `framework/domain`. Still open for mall-swarm, pig and jeecgboot: no OWNS for 76, 60 and 143 tables. | The DDL is in a root `sql/`/`db/` folder, which no service depends on, or in a shared Flyway module, which is now read through the Maven dependency. |
| 5 | jeecgboot | The bundled Nacos server `JeecgNacosApplication` is missed. | It boots through `SpringApplicationBuilder` outside a static `main`. |
| 6 | mall-swarm, paascloud, ftgo, thingsboard | Services that talk only through messaging show no edges. | RabbitMQ, RocketMQ, Redis pub/sub, Eventuate Tram and thingsboard's TbQueue are not read. |
| 9 | paascloud, pig, jeecgboot | **Fixed.** A whole `application.yml` was dropped with a stderr warning: 10 files in paascloud, 7 in pig and 5 in jeecgboot. Service names fell back to folder names, and pig's gateway routes were lost. Now there are no warnings. pig has its 5 gateway routes, plus the `openapi` route to itself, as 11 EXACT CALLS. In jeecgboot, 4 services get their real names, such as `jeecg-gateway` instead of `jeecg-cloud-gateway`. `jeecg-system-cloud-start` merges with `jeecg-system-start`, because both set `name: jeecg-system`, and gives a correct DUPLICATE_SERVICE finding. paascloud's and pig's names already matched their folders. | Maven resource filtering placeholders such as `@pom.artifactId@` or `@config.server-addr@` are not valid YAML. Before parsing, `@artifactId@`, `@project.artifactId@` and `@pom.artifactId@` now become the artifactId of the nearest `pom.xml`, and any other token becomes an unresolved `${...}`. Test `mavenFilteredYamlIsReadWithTheModuleArtifactIdAndOtherTokensUnresolved`. pig's 5 routes now give 5 CONTRACT_DRIFT findings, such as `/admin/**` matching nothing in `pig-upms-biz`. No `StripPrefix` filter is in the repo, so the prefix is probably stripped by config held in Nacos. |
| 10 | shopizer | **Fixed.** It took 62–91 s for 3.5 MB, with 4808 REACHES edges, a median of 18 tables per endpoint and a maximum of 37 where about 4 are real. Now it takes 15 s, with 762 REACHES edges, a median of 2 tables per endpoint and a maximum of 18. 3 of 264 endpoints lost all their table reach; each one got there only through a sibling populator or mapper. | `ReachAnalysis.collect` went up to a supertype and then added the same-name method of every implementation, including siblings. A `populate` call fanned out to all 25 populators, and jstack showed about 78% of the time in `ReachAnalysis.analyze`. Now a call fans out to implementations only when its receiver type is an interface or abstract class. It reaches every subtype, including indirect ones. A concrete receiver resolves only to its own type and its supertypes. Test `concreteReceiverDoesNotFanOutToSiblingImplementations`. Other repos: paascloud 36 → 35 REACHES and yudao-cloud 9243 → 9239, where every edge lost went through a sibling (`OmcCartServiceImpl` → `OmcOrderServiceImpl`, `CrmProductMapper` → `CrmBusinessMapper`). pig 62 → 63 and yudao-cloud's 5 new edges come from the indirect walk, interface → abstract → concrete (`FileClient` → `DBFileClient`). petclinic, piggymetrics, microservice-kafka, mall-swarm, ftgo, jeecgboot, metersphere, dolphinscheduler, thingsboard and fineract have the same REACHES counts. |
| 11 | mall-swarm | **Fixed.** `GET /brand/listAll` (`FeignAdminService.java:19`) was also linked to `GET /brand/{id}`. Now it links only to `/brand/listAll`: the CALLS edge to `/brand/{id}` is gone. | When a call matches a literal endpoint with exactly the call's path and a template sibling, only the literal one is linked. A call that matches only templates, or only a wildcard, is unchanged. |
| 12 | paascloud, mall-swarm, jeecgboot, metersphere | **Fixed.** Tables were missing for tk.mybatis `@Table` entities without `@Entity` (`MdcAddress.java:27`) and for plain MyBatis mappers with XML. Tables: paascloud 4 → 41, mall-swarm 3 → 51, jeecgboot 40 → 48. metersphere stayed at 0 until the Maven module fix, which gives it 161. Side effects: yudao-cloud 547 → 564 (mapper XML, e.g. `CrmStatisticsProductMapper.xml:26`), shopizer 81 → 82, and fineract gains `m_command` (Spring Data JDBC `@Table`), which also clears 3 "entity not found in sources" findings. | Only JPA, Spring Data and MyBatis-Plus were read. Now `@Table(name)` without `@Entity` maps to that table, and each `<select>`, `<insert>`, `<update>` and `<delete>` body in a `<mapper>` XML under the service's `src/main/resources` gives a USES edge per table, with the XML line as evidence. Tests `readsTableAnnotationWithoutEntity` and `readsTablesFromMyBatisMapperXml`. metersphere's 237 mapper XMLs sit in `src/main/java` of `framework/domain`, a library with no app. They now count for the services that depend on it, when the service uses the mapper type. Not done: `<include>`/`<sql>` fragments are not folded in, so a statement whose only table comes from an `<include>` is missed. |
| 13 | thingsboard | **Fixed.** The tables `columns` and `pg_tables` (`SqlPartitionService.java:53`) are gone: 908 nodes and 952 edges before, 906 and 950 now. fineract also drops 5 such tables (`columns`, `pg_indexes`, `statistics`, `table_constraints`, `tables`). | `information_schema.*`, `pg_catalog.*` and `pg_*` names are skipped, and so are bare `columns` and `tables` in a statement that mentions `information_schema`. The two statements still show as "SQL not resolved statically" findings (49 before and after). |
| 14 | thingsboard | `coap`, `http` and `mqtt` are each 2 apps (integration and transport), merged into one service with DUPLICATE_SERVICE. | Both modules have the same folder name and no `spring.application.name`. |
| 15 | thingsboard, metersphere | False UNRESOLVED findings: 2 from `ConnectionCallback` lambdas over `LargeObject` (`PostgresService.java:27`), and 2 from a URL that is a lambda parameter (`TaskRunnerClient.java:53`). | Lambda parameters are not traced. The `ConnectionCallback` cases make no SQL or HTTP call at all. |
| 16 | ftgo | The gateway's 3 routes have method `*`, and a route whose URI comes from a getter is silently dropped. | `.method("POST")` predicates in the Java route DSL are ignored (`GatewayExtractor.java:166`). A non-constant URI gives no CALLS and no finding. |
| 17 | ftgo | A WebFlux `RouterFunctions.route(GET("/orders/{orderId}"))` endpoint is missed (`OrderConfiguration.java:36`). | Functional routes are not read. |
| 18 | mall-swarm | **Fixed.** `EsProduct` (`EsProduct.java:17`) no longer gets the store `mongo`: the table `mall-search/esProduct`, its USES edge and 5 REACHES edges are gone (8 fewer edges in mall-swarm). | A `@Document` whose file imports `org.springframework.data.elasticsearch` is an index, not a collection, and so is its repository. A `@Document` with no such import is still MongoDB. Elasticsearch indexes are not shown at all. |
