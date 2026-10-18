# Phase 0 research: spring-service-graph

Checked 2026-10-18. Items marked *unverified* were not confirmed from a primary source.

## TL;DR

1. **Build our own.** No maintained tool scans a fleet of repos into an
   endpoint-level graph covering Feign, WebClient, Kafka and tables, with
   file:line evidence and a confidence level on every fact. Instead of
   contributing to another tool, we emit formats other tools read
   (Backstage, Structurizr) in Phase 2.
2. **Parser: JavaParser 3.28.2, parse-only**, at language level `JAVA_21` or higher
   (the default is `JAVA_11`). We skip the symbol solver and build our own
   index of constants. We skip bytecode, because annotations have no line
   numbers and a build would be required.
3. **Benchmark: spring-petclinic-microservices** (Apache-2.0, Boot 4.0.1), pinned
   to a commit, together with its config repo. It has no Feign and no Kafka, so
   **piggymetrics** (MIT, Feign) and **ewolff/microservice-kafka** (Apache-2.0,
   Kafka) are secondary benchmarks.
4. **Design changes** are listed in the last section. The main ones:
   - Gateway-route extraction.
   - A host that equals a `spring.application.name` maps straight to that service.
   - An option for an external config repo.
   - Table ownership from Flyway and `schema.sql`.
   - Fallbacks for generated interfaces.

---

## 1. Existing tools

| Tool | What it covers | Multi-repo | Static / runtime | Cross-service HTTP/Kafka/JPA edges | Verdict |
|---|---|---|---|---|---|
| **jQAssistant** (+ Spring plugin) | Scans bytecode and Maven modules into Neo4j; rules are written in Cypher. The Spring plugin does DI and layering checks. | Partly: several artifacts can go into one Neo4j, but it has no concept of a "service" | Static (bytecode) | No. It stores structure only, so we would write every HTTP and Kafka rule ourselves | Build. It brings a heavy Neo4j dependency, its Spring plugin looks stale (1.12.2, 2022, *unverified* whether maintained), and it needs a build. |
| **Structurizr** (component finder) | C4 model and DSL; Java component discovery | No, one software system per workspace | Static | No | Build. Emit Structurizr DSL as an output later. The old `structurizr/java` repo was archived in 2026-03 and moved to `structurizr/structurizr`. |
| **Spring Modulith Documenter** | PlantUML diagrams and module canvases for the modules of **one** app | No | Built from the classpath of one app | No. It covers in-process application events only | Don't extend. Borrow its "canvas" layout for the per-service page. |
| **Backstage catalog** | Components, APIs and Resources, with `dependsOn`, `providesApis` and `consumesApis`, read from `catalog-info.yaml` in many repos | Yes | Declarative, written by hand | Only what people write in YAML. No tool generating it from Spring code was found (*not proof that none exists*). | Interoperate. Generating `catalog-info.yaml` from code fills a real gap (Phase 2). |
| **Context Mapper** (context-map-discovery) | Reverse-engineers a DDD context map in CML. The Spring strategy finds `@SpringBootApplication` classes and REST aggregates. | Several bounded contexts, but relationships come only from Docker Compose | Reflection, so it needs the classpath | No | Build. It is a prototype with a different goal. |
| **ArchUnit** | Rules over imported bytecode within one codebase | No | Static | No | Not a scanner. Could be used later to keep our own extractors independent of each other. |
| **OTel service graphs** (Tempo metrics-generator, Jaeger, Alloy servicegraph) | Service-to-service edges with rate and latency, built from spans | Yes, fleet-wide | **Runtime** | Edges appear only if traffic happened; no endpoint or contract view and no file:line | Complementary. It misses dormant paths, and we miss dynamic ones. Possible Phase 2+ work: raise confidence when static edges are confirmed by traces. |
| **Ariadne** (whyy9527/ariadne, MIT, 2026) | Offline index of REST endpoints, Kafka topics and GraphQL for Spring and TypeScript, exposed as CLI and MCP | **Yes**, from a list of repos | Static | REST and Kafka yes. Feign, WebClient and RestTemplate *unconfirmed*; no JPA; file:line *unconfirmed* | **Closest overlap.** It is new and has no community. It targets fuzzy matching for MCP, not an evidence and confidence graph. Read its detectors before coding the linker. |
| **Code2DFD** (TUHH, academic, Python) | Security dataflow diagrams from microservice source, with traceability | Unclear | Static | *Unverified* | Don't extend, but its extractor design is prior art worth reading. |
| **MicroGraal** (Baylor / BUT Brno) | REST, WebSocket and GraphQL links from bytecode via GraalVM | Yes | Static (bytecode) | REST via annotations and RestTemplate; Feign and Kafka *unconfirmed* | No public repo found. Papers only. |
| **Prophet** (cloudhubs) | Context maps and communication diagrams | *Unverified* | Static | Not documented | Early stage (v0.0.8). |

**Decision: build.** Reuse ideas, not code. Before coding the linker, spend
about 30 minutes reading Ariadne's Spring detectors to collect edge cases.

Sources: backstage.io descriptor-format docs, docs.spring.io/spring-modulith
documentation page, docs.structurizr.com/java, jqassistant.github.io manual,
contextmapper.org reverse-engineering docs, archunit.org user guide,
grafana.com Tempo service-graph docs, the GitHub repos named above.

---

## 2. Parser: JavaParser source vs bytecode (ClassGraph/ASM)

### JavaParser

- **Version and language levels.** The latest is **3.28.2** (2026-05-31). The
  `LanguageLevel` enum goes up to **`JAVA_26`**.
  - 3.28.0 added Java 23 and 24 syntax: module imports, flexible constructor
    bodies, compact source files, and Markdown comments.
  - Java 21 records, sealed types, pattern switch and record patterns are all
    supported.
  - Unnamed `_` variables have a validator. Two related issues were closed in
    2026-04 and 2026-05. *Which release contains those fixes is unverified.*
- **The default level is `POPULAR` = JAVA_11, which rejects records and sealed
  types.** We must set `BLEEDING_EDGE` (or `JAVA_26`).
- **On bad syntax** `parse()` returns a `ParseResult` with a problems list and
  often a partial AST. It does not throw. We log the problems per file and keep
  whatever parsed. (*From documented behavior, not tested here.*)

### JavaSymbolSolver

- It can resolve a `NameExpr` to its field declaration across files. It does
  **not** evaluate the initializer, so we would still write the folding of
  `"a" + B.C` ourselves.
- Without Spring jars on the classpath, it throws `UnsolvedSymbolException` for
  framework types.
- Its performance issues are long-standing and still open: #1898, #3292 and
  #4975.
- Resolving the declaration is the easy part, so the solver buys us little at a
  real cost.

### Own constant index (recommended)

1. **Pass 1** parses every file and records every `static final` String or
   primitive field, and every interface constant, as `FQN → (initializer expr,
   file:line)`.
2. **Pass 2** folds annotation and argument expressions. It handles:
   - literals and `+` concatenation
   - `NAME`, `Class.NAME` and `pkg.Class.NAME`
   - static and single-type imports, `import pkg.*`, and same-package lookup
   - cycle protection
3. The result is `RESOLVED`, with the constant's file:line added as a second
   evidence entry.
4. Anything we cannot fold becomes `UNRESOLVED` with the raw expression text.
   Examples are method calls, `String.format`, assignments in a `static {}`
   block, and constants inherited through `extends` (deferred).

Annotations are matched by simple name and checked against imports, because
there is no classpath.

### Bytecode (ClassGraph 6.0.0 / ASM 9.11.1)

- javac inlines compile-time constants into annotation values, which is nice.
  (*JLS behavior from memory.*)
- **Annotations have no line numbers.** `LineNumberTable` exists only for method
  bodies. That breaks the file:line evidence requirement for `@KafkaListener`,
  `@RequestMapping` and `@FeignClient`.
- It needs every repo built. A fleet has mixed JDKs, Maven and Gradle, private
  registries and credentials, and takes minutes per repo. Many checkouts are
  never built.

### Recommendation

**JavaParser 3.28.2, parse-only, with our own constant index.** If
`build/generated-sources` or `target/generated-sources` exists, add it as an
extra source root, because that is where openapi-generator `interfaceOnly`
interfaces live. See design change D5 for the case where it does not exist.

---

## 3. Benchmark fleet

### spring-petclinic/spring-petclinic-microservices (primary)

- `main` branch, pushed 2026-10-07, Apache-2.0.
- Boot **4.0.1**, Java 17, Spring Cloud 2025.1.0.
- Its config lives in a separate repo, `spring-petclinic-microservices-config`,
  with one `<service>.yml` per service.

| Module | App name | Relevant content |
|---|---|---|
| api-gateway | `api-gateway` | Gateway routes in **YAML** under `spring.cloud.gateway.server.webflux.routes` (new key). Each route is `uri: lb://<svc>` with `Path=/api/<x>/**` and `StripPrefix=2`, plus CircuitBreaker and Retry filters. WebClient clients `CustomersServiceClient` (`"http://customers-service/owners/{ownerId}"`) and `VisitsServiceClient` (a `hostname` field `"http://visits-service/"` + `"pets/visits?petId={petId}"`). A `@LoadBalanced RestTemplate` bean that is never called. Inbound `ApiGatewayController` and `FallbackController`. |
| customers-service | `customers-service` (*inferred from lb:// URIs*) | `OwnerResource`, `PetResource`; JPA `Owner`, `Pet`, `PetType`; `db/{hsqldb,mysql}/schema.sql` |
| vets-service | `vets-service` (*inferred*) | `VetResource`; JPA `Vet`, `Specialty`; `schema.sql` |
| visits-service | `visits-service` | `VisitResource`; JPA `Visit`; `schema.sql` |
| genai-service | `genai-service` | Spring AI `@Tool` methods (not HTTP). `RestClient` calls whose base URL comes from `discoveryClient.getInstances("customers-service")` and then `+ "/owners/" + id + "/pets"`. |
| config, discovery, admin servers | *unverified* | Infrastructure only, with no business edges |

**Call styles:**
- WebClient: yes.
- RestClient: yes.
- RestTemplate: a bean exists but is never used.
- Gateway routes: YAML.
- Feign: **none**.
- `@HttpExchange`: **none**.
- Kafka: **none**.
- Data: JPA with `schema.sql`. No Liquibase or Flyway.

### Alternatives

| Repo | Last push | License | Covers | Use? |
|---|---|---|---|---|
| sqshq/piggymetrics | 2024-05 | MIT | `@FeignClient(name="statistics-service")` and others, Mongo `@Document`, gateway (Boot 2.0.3, old Zuul-era config) | **Secondary, for Feign** |
| ewolff/microservice-kafka | 2024-08 | Apache-2.0 | `@KafkaListener` in invoicing and shipping, JPA in all three services | **Secondary, for Kafka** (producer style *unverified*) |
| piomin/sample-spring-kafka-microservices | 2026-09 | GPL-3.0 | Kafka saga, JPA | No (license) |
| piomin/sample-spring-microservices-new | 2026-10 | none declared | Gateway, discovery, 3 services | No (no license) |
| microservices-patterns/ftgo-application | 2026-03 | unclear | Gradle, Eventuate | No (*license file not checked*) |

**No permissive public repo covers `@HttpExchange`, Cloud Stream, Liquibase or
Flyway.** The synthetic fixture fleet in `src/test/resources/fleet/` remains the
only test for those.

**Plan:**
- Clone the three repos at pinned SHAs into a git-ignored `bench/` folder via
  a script, never committed.
- Record precision issues in `docs/benchmark.md`.

---

## 4. Proposed design changes

### Must have, found on the benchmark

| ID | Change | Why |
|---|---|---|
| D1 | **Gateway-route extractor.** Read `spring.cloud.gateway.routes`, `…server.webflux.routes` and `…server.webmvc.routes` from YAML or properties. Apply `Path`, `StripPrefix` and `RewritePath` (simple regex), producing a gateway endpoint → `lb://svc` + rewritten path. A Java `RouteLocator` is best-effort and `UNRESOLVED`. | Petclinic and most fleets route through the gateway. Without this the gateway looks like a dead end. Spring Cloud 2025 renamed the key. |
| D2 | **Host resolution order:** `lb://name`, then a host equal to a known `spring.application.name`, then `aliases.yml`, then an `external` node. | `http://customers-service/...` is load-balanced by name. Aliases stay for k8s names and external systems. |
| D3 | **Track URL parts separately.** A target can be resolved while its path is `UNRESOLVED`, for example a `hostname` field plus a literal, or a `DiscoveryClient.getInstances("x")` base. Edge confidence = the lowest confidence among its parts. The edge still points to the service and is listed in Findings. | We keep useful edges instead of dropping them, while still being honest about what is unresolved. |
| D4 | **`--config-repo <dir>` option.** Load `application.yml` plus `<app-name>.yml` and `<app-name>-<profile>.yml` with Spring Cloud Config precedence, above the service's own config. | Petclinic keeps per-service config outside the service repo. This is common in real fleets. |
| D5 | **Fallbacks for generated interfaces**, in order: `build/` or `target/generated-sources` if present; otherwise the OpenAPI spec in the repo (`openapi*.yml`/`.yaml`, read with SnakeYAML); otherwise an "interface not found" finding. | An unbuilt checkout has no generated interfaces, so `interfaceOnly` controllers would expose nothing. |
| D6 | **Table ownership** from Liquibase `createTable` (XML, YAML, SQL), **Flyway** `db/migration/V*.sql`, and `schema.sql`. The SQL sources use a `CREATE TABLE` regex. | Petclinic uses `schema.sql`, and many fleets use Flyway. A regex is enough; no SQL parser is needed. |
| D7 | **The JavaParser language level must be set explicitly** to `BLEEDING_EDGE`. Parse problems are reported per file in `index.md`. | Otherwise any record or sealed type fails the whole file without anyone noticing. |
| D8 | **A service needs `@SpringBootApplication` plus an app name**, falling back to the directory name. Infra apps (config, discovery, admin) remain services. They may be tagged later. | Keep it simple; tagging is cosmetic. |

### Clarifications (no scope change)

- **Spring Data derived queries** are not facts. Repository → entity comes only
  from the generic `<Entity, ID>` argument, so nothing is invented.
- **`@LoadBalanced RestTemplate`/`WebClient.Builder` beans** are not calls.
  Only call sites produce edges.
- **Spring AI `@Tool` methods** are ignored, as they are not HTTP endpoints.
- **Path-template matching** normalizes `{var}` to `{}`, ignores the query
  string, and treats `/**` in gateway predicates as a prefix.

### Phase 2 additions to consider (not MVP)

- Output Backstage `catalog-info.yaml` and Structurizr DSL.
- Cross-check against OTel service graphs.

### Dependencies

No change: JavaParser, SnakeYAML, Jackson, plus JUnit 5 and AssertJ for tests.
`.properties` files are read with `java.util.Properties`.

---

## Open questions for you

1. **D1 and D4** add two features to the MVP: gateway routes and a config repo.
   Should both go in? Without them petclinic yields very few edges.
2. **Benchmarks:** is it acceptable to clone petclinic, its config repo,
   piggymetrics and microservice-kafka into a git-ignored `bench/` folder?
3. **D5 (OpenAPI spec fallback):** should it be in the MVP, or should unbuilt
   repos just get the "interface not found" finding for now?
