# spring-service-graph (ssg)

`ssg` is a Java 21 CLI that statically scans a fleet of Spring Boot microservice repositories and writes a cross-service dependency graph: which service exposes which endpoints, who calls them, which Kafka, RabbitMQ and RocketMQ topics are produced and consumed, and which tables each service owns or uses. Every edge carries `file:line` evidence and a confidence level (`EXACT`, `RESOLVED` or `UNRESOLVED`). Use it for impact analysis ("what breaks if I change this endpoint?"), for generated onboarding pages per service, and to catch contract drift (a client calling a path that no service exposes).

It never needs a build or a classpath. Sources are parsed with JavaParser (parse only, no symbol solver) and constants are folded by ssg's own index, so unbuilt checkouts work. Only Java sources are read.

## Build and run

Requires JDK 21.

```
./gradlew installDist
build/install/ssg/bin/ssg scan --repos ./fleet/* --profile default \
    --aliases aliases.yml --config-repo ./config-repo --out ./out
```

Or download `ssg-<version>.zip` from GitHub Releases, unzip it and run `bin/ssg`. Releases are built by `.github/workflows/release.yml` when a `v*` tag is pushed.

`./gradlew build` compiles and runs the tests. Kotlin sources are not scanned yet.

## Options

| Option | Required | Behaviour |
|---|---|---|
| `--repos <dir>...` | yes | One or more repo checkouts. A trailing `*` or `?` segment (`./fleet/*`) is expanded by ssg itself, so it works in cmd.exe too; only directories match, sorted by path. Each repo may hold several Spring Boot apps (modules). |
| `--profile <name>` | no, default `default` | Spring profile whose `application-<profile>.*` / `bootstrap-<profile>.*` files are layered over the base config. |
| `--aliases <file>` | no | Host-to-target map, see Linking rules. |
| `--config-repo <dir>` | no | Spring Cloud Config style repo. `application.*`, `application-<profile>.*`, `<app>.*` and `<app>-<profile>.*` from its root override the service's own config. Only the repo root is read. |
| `--sql-methods <m>...` | no | In-house wrapper methods that take SQL, as `method` or `Type.method`, given as several values or comma-separated. Their SQL arguments are read like `JdbcTemplate` calls. Without this, ssg reports where SQL arrives as a method parameter and suggests the wrapper to list. |
| `--link-base <url>` | no | URL template for evidence links in the Markdown, e.g. `https://git.example/{repo}/blob/main/{file}#L{line}`. Without it, links are relative paths to the local checkout. |
| `--out <dir>` | yes | Output directory. |

A service is a module with a `@SpringBootApplication` class, or with a `main` method that starts Spring through `SpringApplication` or `SpringApplicationBuilder`. Its name is `spring.application.name`, falling back to the module directory name.

## What is detected

**Inbound HTTP**

| Source | Notes |
|---|---|
| `@RestController` / `@Controller` with `@RequestMapping`, `@GetMapping` etc. | Class and method paths are joined; `server.servlet.context-path` is applied. |
| JAX-RS `@Path` resources with `@GET`, `@POST` etc. | `@ApplicationPath` and the context path are prepended. |
| WebFlux `RouterFunction` beans | `RouterFunctions.route(GET("/x"), h)`, `.andRoute(...)` and `route().GET("/x", h)` builders. |
| Mappings on implemented interfaces | openapi-generator `interfaceOnly` interfaces, including `target/` and `build/generated-sources`. |
| OpenAPI 3 / Swagger 2 spec in the module (YAML or JSON) | Fallback when the generated interface is not in the checkout. If no spec exists either, a `MISSING_INTERFACE` finding is raised. |

**Outbound HTTP**

| Source | Notes |
|---|---|
| `@FeignClient` interfaces | Name or URL, plus mapping annotations. |
| `@HttpExchange` interfaces | `@GetExchange` etc. |
| `RestTemplate` / `RestOperations` call sites | `getForObject`, `exchange`, and similar. |
| `WebClient` / `RestClient` chains | `client.get().uri(...)`, `client.method(...).uri(...)`. Best effort. |

**Gateway routes**

| Source | Notes |
|---|---|
| Config keys `spring.cloud.gateway.routes`, `...server.webflux.routes`, `...server.webmvc.routes`, `...mvc.routes` | YAML or properties. `Path` predicates and path-rewriting filters give an endpoint on the gateway and a call to the route URI. |
| Java `RouteLocator` DSL | Best effort. |

**Messaging**

| Source | Notes |
|---|---|
| `@KafkaListener` | Consumes. |
| `@SendTo`, `KafkaTemplate.send` / `sendDefault`, `StreamBridge.send` | Produces. |
| Spring Cloud Stream `bindings.*.destination` | Produces or consumes. |
| `@RabbitListener(queues)`; `RabbitTemplate` / `AmqpTemplate` `convertAndSend` / `send` | Consumes the queue; produces to the queue that `@Bean Binding` methods bind the (exchange, routing key) to, plus the queue a bound queue dead-letters to (`x-dead-letter-*`). Without a binding it produces to the routing key, so key == queue still links. Wildcard keys, keyless bindings and bindings declared in another service are not read. |
| `@RocketMQMessageListener(topic)`, `DefaultMQPushConsumer.subscribe`; `RocketMQTemplate` sends, `new Message(topic, ...)` | Consumes or produces; a `topic:tag` destination is the topic. |

**Data**

| Source | Notes |
|---|---|
| `@Entity` / `@Table`, MyBatis-Plus `@TableName`, `@Document` (Mongo) | Service USES the table or collection. |
| Spring Data repositories, MyBatis-Plus `BaseMapper<T>` mappers | Taken from the entity type argument only; derived query methods are not interpreted. |
| Liquibase `createTable`, Flyway migrations, `schema.sql` | Service OWNS the table. |
| `@Query(value = "...", nativeQuery = true)` | Service USES each table in the SQL. JPQL queries are skipped (they name entities). |
| SQL passed to `JdbcTemplate`, `NamedParameterJdbcTemplate`, `JdbcOperations`, `JdbcClient` or a `--sql-methods` wrapper | Service USES each table after `FROM`/`JOIN`/`INTO`/`UPDATE`. The SQL is folded through constants and text blocks; CTEs, comments, literals, `FOR UPDATE` and `EXTRACT(.. FROM ..)` are not mistaken for tables. |

`@LoadBalanced` bean definitions are not calls; only call sites produce edges.

## Linking rules

Call targets are resolved in this order:

1. `lb://name` (gateway routes, load-balanced clients) and the Feign `name` map to the service of that name.
2. A host equal to a known `spring.application.name` maps to that service.
3. `aliases.yml`, a flat map of `host: target`. If the target is a known service the call goes to it; otherwise it becomes an external node named by the target.
   ```yaml
   billing.internal.example.com: billing-service
   api.stripe.com: stripe
   ```
4. Any other known host becomes `external:<host>`.
5. A host that cannot be determined statically becomes `external:unresolved`.

When the target is a service, the call path is matched against that service's endpoints (and `UNRESOLVED` parts are kept separate, so an edge is not dropped because its path is unknown). Matching is segment by segment: `{var}` on either side matches one segment, `**` matches the rest, a trailing unknown `{?}` matches one or more segments, and the query string is ignored. HTTP methods must be equal unless either side is `*`. An edge's confidence is the lowest confidence of its parts. A call to a known service whose method and path match no exposed endpoint becomes a `CONTRACT_DRIFT` finding.

## Endpoint reach

For every controller endpoint, ssg walks the in-service method calls from its handler (breadth first, at most 6 calls deep) and records `REACHES` edges from the endpoint to what it ends up touching:
- the endpoints it calls in other services (via Feign, `@HttpExchange`, RestTemplate, WebClient, RestClient)
- the topics it publishes to
- the tables behind the repositories it uses
- the tables in the SQL it runs through JDBC

Calls are resolved without a symbol solver:
- `this` and unqualified calls go to the enclosing type.
- `field.m()` uses the field's declared type.
- `Type.m()` is resolved statically.
- An interface call goes to its implementations in the service.

A repository counts as soon as the walk calls any method on it, so Spring Data's synthesized methods such as `findByEmail` are covered. Each edge carries the call chain, for example `AccountController.create → AccountService.create → AccountEvents.accountCreated`.

Not followed: calls on method chains, lambdas, method references and `@Async` or event listeners. A repository maps only to its entity's table, even when a custom `@Query` reads other tables (those still give the service a USES edge, but no reach).

## Diff: merge-request impact

```
ssg scan --repos ... --out out-base     # on the target branch
ssg scan --repos ... --out out-head     # on the MR branch
ssg diff --base out-base --head out-head > impact.md
```

The report is Markdown on stdout. It opens with **Breaking changes**:
- removed endpoints or services that are still called
- topics that lost their last producer but still have consumers
- new contract drift

Then it lists what was added or removed: endpoints, calls, messaging, data and reach. It also lists confidence changes, and new and resolved findings. Evidence line numbers are ignored when matching, so moved code is not reported. The exit code is 0, or 3 when `--fail-on-breaking` is given and there are breaking changes.

To run this on every pull request, use the GitHub action in this repo (`uses: sunJirapath/spring-service-graph@<sha>`). It scans the fleet with the PR's base and head, posts the report as a PR comment and in the job summary, and can fail the check on breaking changes. For other CI systems, use `scripts/ssg-impact.sh`. See [docs/ci.md](docs/ci.md).

## MCP server

```
ssg mcp --graph out
```

This serves the graph to an MCP client over stdio (JSON-RPC 2.0). The server is read-only and exposes three tools:

| Tool | Arguments | Answers |
|---|---|---|
| `who_calls` | `target` | Incoming edges with evidence: callers of an endpoint or service, producers and consumers of a topic, owners and users of a table, and the endpoints that reach it. |
| `impact_of` | `target`, `depth` (1-10, default 3) | What may break if the target changes. It walks backwards through endpoints using reach, and returns the affected nodes with the edge that explains each one. |
| `service_card` | `service` | The service's page as Markdown, with evidence as plain `repo/file:line`. |

A `target` can be any of:
- a node id from `graph.json`
- a service or topic name
- `METHOD /path` or `service METHOD /path`
- an endpoint path
- a table name

Example client configuration:

```json
{ "mcpServers": { "ssg": { "command": "/path/to/ssg/bin/ssg", "args": ["mcp", "--graph", "/path/to/out"] } } }
```

## Output

```
out/
  graph.json          nodes, edges, findings
  index.md            fleet overview, service dependencies, topics, findings
  services/<name>.md  one page per service: exposes, calls, publishes, consumes, data
  diagrams/fleet.puml PlantUML fleet diagram
  diagrams/<service>.mmd  Mermaid diagram per service
```

Node kinds: `service`, `endpoint`, `topic`, `table`, `external`. Edge kinds: `EXPOSES`, `CALLS`, `PRODUCES`, `CONSUMES`, `OWNS`, `USES`, `REACHES` (endpoint → called endpoint, topic or table). Finding kinds: `CONTRACT_DRIFT`, `UNRESOLVED`, `MISSING_INTERFACE`, `PARSE_ERROR`, `DUPLICATE_SERVICE`, `SHARED_TABLE` (a table owned by DDL in two or more services).

A `CALLS` edge from the golden example (`src/test/resources/golden/graph.json`):

```json
{
  "kind" : "CALLS",
  "from" : "service:gateway",
  "to" : "endpoint:account:GET /api/accounts/{id}",
  "confidence" : "EXACT",
  "attrs" : { "client" : "feign", "method" : "GET", "path" : "/api/accounts/{id}" },
  "evidence" : [
    { "repo" : "gateway", "file" : "src/main/java/demo/gateway/client/AccountClient.java", "line" : 11 }
  ]
}
```

Output is deterministic. Values of secret-looking config keys (`password`, `secret`, `token`, `credential`, `api-key`, `private-key`) are never read into the output. Parts that cannot be resolved statically are shown as `{?}`, e.g. `GET {?}/reports/{?}`.

## Known limits

- Tables are scoped per service: two services using the same table name get separate nodes.
- In a repo with several Spring Boot apps, a library module (no `@SpringBootApplication`) counts for the services whose `pom.xml` or `build.gradle` depends on it, transitively; Gradle `projectDir` remaps and Maven profiles are not read.
- Profile expressions such as `!dev` or `a & b` are not evaluated, only plain profile lists.
- Config repo: only the repo root is read, not per-app subfolders or custom search locations.
- A non-final field or local variable that is reassigned later still folds to its initializer.
- OpenAPI specs: `${openapi.<name>.base-path}` overrides are not applied; the spec's server path is used.
- Values built by method calls (other than zero-argument helpers in the same class that only `return` an expression), `String.format` or `static {}` blocks are `UNRESOLVED`. Constants inherited from super types found in sources are resolved.
- Java only; Kotlin sources are not scanned.
- Memory: a scan holds the syntax trees of one repo at a time, roughly 80 MB of heap per MB of Java source in the largest repo (a 12 MB repo needs about 1 GB). Raise it with `JAVA_OPTS=-Xmx2g` if needed.
- Nested checkouts are skipped: any folder below a repo root with its own `.git` (worktrees, submodules, vendored clones).
- JDBC: an implicit comma join (`FROM a, b`) yields only the first table; SQL built at runtime is reported as `UNRESOLVED`.
- Static analysis misses runtime-only behaviour (dynamic URLs, service discovery lookups at runtime).

## Testing and benchmark

There are about 630 tests: edge-case suites per extractor, a deliberately messy fleet in `src/test/resources/messy`, and golden tests. Line coverage is 98% and branch coverage 94%; see `docs/testing.md` for the bugs the campaign found. Run `./gradlew test jacocoTestReport` for a coverage report.

Golden tests run the full scan over the synthetic fleet in `src/test/resources/fleet` and compare against `src/test/resources/golden`. After an intended change, regenerate and review the diff:

```
./gradlew test -PupdateGolden
```

Real-world benchmark: `scripts/bench-clone.sh` clones spring-petclinic-microservices, its config repo, piggymetrics and ewolff/microservice-kafka at pinned commits into a git-ignored `bench/` folder (clone and read only, never built). Findings are recorded in `docs/benchmark.md`. Design background and decisions D1-D8 are in `docs/research.md`.

## License

Apache-2.0. groupId `io.github.sunjirapath`.
