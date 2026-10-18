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
