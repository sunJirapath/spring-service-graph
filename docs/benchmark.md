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
Time was measured at `-Xmx8g` on a 16-thread Windows machine. "Heap needed" is the smallest `-Xmx` that worked.

| Code base | Commit | Main Java | Apps | Services | Endpoints | CALLS | Topics | Tables | Findings | Time | Heap needed |
|---|---|---|---|---|---|---|---|---|---|---|---|
| YunaiV/yudao-cloud | `0757f05` | 21 MB | 25 | 25 | 3392 | 61 | 1 | 0 | 36 CONTRACT_DRIFT, 15 UNRESOLVED | 31 s | 1.5 GB (OOM at 1 GB) |
| apache/dolphinscheduler | `c70003c` | 7 MB | 8 in 6 modules | 6 | 239 | 0 | 0 | 0 | 1 MISSING_INTERFACE | 6 s | 384 MB (OOM at 256 MB) |
| apache/fineract | `71681f2` | 28 MB | 1, no `@SpringBootApplication` | 0 | 0 | 0 | 0 | 0 | 0 (one stderr warning) | 12 s | over 1 GB (peak 1.9 GB) |

Notes on the counts:
- yudao-cloud's 61 CALLS are 36 gateway routes and 25 calls from HTTP clients.
- Its one topic is produced from `config.getTopic()`, so it is UNRESOLVED.

### Recall against grep

These counts are of files under `src/main/java`.

| Construct | yudao-cloud | dolphinscheduler | fineract |
|---|---|---|---|
| Controller classes → endpoints | 657 → 3392 endpoints in 24/25 services | 35 → 239 | 3 + 170 JAX-RS `@Path` → 0 |
| `@FeignClient` → CALLS | **47 → 0** | — | — |
| `@TableName` (MyBatis-Plus) → tables | **547 → 0** | **48 → 0** | — |
| `@Entity` / `extends *Repository<` → tables | — | — | 254 / 200 → 0 |
| `JdbcTemplate` / `JdbcClient` | 3 → 1 UNRESOLVED (a `--sql-methods` hint) | — | 203 → 0 |
| `KafkaTemplate` / `@KafkaListener` | 6 → 1 PRODUCES | — | 4 → 0 |
| `RestTemplate` / `WebClient` / `RestClient` | 34 → 25 CALLS | — | 4 → 0 |
| `CREATE TABLE` / `createTable` | 863 in root `sql/` → 0 | 378 in `dolphinscheduler-dao` → 0 | 741 + 320 Liquibase → 0 |

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
| 1 | yudao-cloud | 0 of 47 Feign clients become CALLS, so the whole RPC graph between its 25 services is missing. | The clients live in shared `yudao-module-*-api` and `yudao-common` modules. Those have no app, and the services pull them in as Maven dependencies. ssg attributes code to a service only by module folder. |
| 2 | yudao-cloud | 36/36 CONTRACT_DRIFT findings are false positives, for example `/admin-api/crm/**` matches nothing on crm-server. | A starter adds `/admin-api` and `/app-api` in code, with `RequestMappingHandlerMapping.setPathPrefixes`, keyed by controller package. ssg sees only the annotation paths. |
| 3 | yudao-cloud, dolphinscheduler | No tables at all. | They use MyBatis-Plus (`@TableName` entities, `BaseMapper<T>` mappers) instead of JPA or Spring Data. |
| 4 | yudao-cloud, dolphinscheduler | No OWNS, although 1,200+ `CREATE TABLE` statements exist. | The DDL lives outside every service module: in the root `sql/` folder, or in a shared `-dao` module. This has the same root cause as #1. |
| 5 | fineract | The whole repo is dropped, with one `warn: no @SpringBootApplication`. | It boots through `SpringApplicationBuilder` with an `@EnableAutoConfiguration`-style configuration class. Even with that fixed, its 170 JAX-RS resources would give no endpoints. |
| 6 | dolphinscheduler, yudao-cloud | 5 of 6 dolphinscheduler services and several yudao-cloud services show no outbound edges. | They use transports ssg does not read: dolphinscheduler's own Netty RPC, plus RocketMQ, RabbitMQ and Redis streams (15 files in yudao-cloud). These are out of scope. |
| 7 | dolphinscheduler | 1 noisy MISSING_INTERFACE finding. | The controller implements Spring Boot's own `ErrorController`. Framework interfaces carry no mappings and should not be reported. |
| 8 | all | 50–70 MB of heap per MB of Java. Fineract runs out of memory at 1 GB although it yields no service. | Every source file is parsed before discovery, so even a repo with no app gets fully parsed. This adds to the open memory item from field test 1. |

The JDBC support from field test 1 was barely exercised. Fineract would have exercised it with 203 `JdbcTemplate` files, but gap #5 hides it.
