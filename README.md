# spring-service-graph (ssg)

`ssg` is a Java 21 CLI that statically scans a fleet of Spring Boot microservice repositories and writes a cross-service dependency graph: which service exposes which endpoints, who calls them, which Kafka topics are produced and consumed, and which tables each service owns or uses. Every edge carries `file:line` evidence and a confidence level (`EXACT`, `RESOLVED` or `UNRESOLVED`). Use it for impact analysis ("what breaks if I change this endpoint?"), for generated onboarding pages per service, and to catch contract drift (a client calling a path that no service exposes).

It never needs a build or a classpath. Sources are parsed with JavaParser (parse only, no symbol solver) and constants are folded by ssg's own index, so unbuilt checkouts work. Only Java sources are read.

## Build and run

Requires JDK 21.

```
./gradlew installDist
build/install/ssg/bin/ssg scan --repos ./fleet/* --profile default \
    --aliases aliases.yml --config-repo ./config-repo --out ./out
```

Or download `ssg-<version>.zip` from GitHub Releases, unzip it and run `bin/ssg`. Releases are built by `.github/workflows/release.yml` when a `v*` tag is pushed.

`./gradlew build` compiles and runs the tests. `ssg diff` is planned for Phase 2 and is not implemented (it exits with code 2).

## Options

| Option | Required | Behaviour |
|---|---|---|
| `--repos <dir>...` | yes | One or more repo checkouts. A trailing `*` or `?` segment (`./fleet/*`) is expanded by ssg itself, so it works in cmd.exe too; only directories match, sorted by path. Each repo may hold several Spring Boot apps (modules). |
| `--profile <name>` | no, default `default` | Spring profile whose `application-<profile>.*` / `bootstrap-<profile>.*` files are layered over the base config. |
| `--aliases <file>` | no | Host-to-target map, see Linking rules. |
| `--config-repo <dir>` | no | Spring Cloud Config style repo. `application.*`, `application-<profile>.*`, `<app>.*` and `<app>-<profile>.*` from its root override the service's own config. Only the repo root is read. |
| `--out <dir>` | yes | Output directory. |

A service is a module with a `@SpringBootApplication` class. Its name is `spring.application.name`, falling back to the module directory name.

## What is detected

**Inbound HTTP**

| Source | Notes |
|---|---|
| `@RestController` / `@Controller` with `@RequestMapping`, `@GetMapping` etc. | Class and method paths are joined; `server.servlet.context-path` is applied. |
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

**Kafka**

| Source | Notes |
|---|---|
| `@KafkaListener` | Consumes. |
| `@SendTo`, `KafkaTemplate.send` / `sendDefault`, `StreamBridge.send` | Produces. |
| Spring Cloud Stream `bindings.*.destination` | Produces or consumes. |

**Data**

| Source | Notes |
|---|---|
| `@Entity` / `@Table`, `@Document` (Mongo) | Service USES the table or collection. |
| Spring Data repositories | Taken from the `<Entity, ID>` type argument only; derived query methods are not interpreted. |
| Liquibase `createTable`, Flyway migrations, `schema.sql` | Service OWNS the table. |

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

## Output

```
out/
  graph.json          nodes, edges, findings
  index.md            fleet overview, service dependencies, topics, findings
  services/<name>.md  one page per service: exposes, calls, publishes, consumes, data
  diagrams/fleet.puml PlantUML fleet diagram
  diagrams/<service>.mmd  Mermaid diagram per service
```

Node kinds: `service`, `endpoint`, `topic`, `table`, `external`. Edge kinds: `EXPOSES`, `CALLS`, `PRODUCES`, `CONSUMES`, `OWNS`, `USES`. Finding kinds: `CONTRACT_DRIFT`, `UNRESOLVED`, `MISSING_INTERFACE`, `PARSE_ERROR`.

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
- In a repo with several Spring Boot apps, library modules (no `@SpringBootApplication`) are attributed to no service; their constants and interfaces are still visible to the index.
- Profile expressions such as `!dev` or `a & b` are not evaluated, only plain profile lists.
- Config repo: only the repo root is read, not per-app subfolders or custom search locations.
- A non-final field or local variable that is reassigned later still folds to its initializer.
- OpenAPI specs: `${openapi.<name>.base-path}` overrides are not applied; the spec's server path is used.
- Values built by method calls (other than zero-argument helpers in the same class that only `return` an expression), `String.format` or `static {}` blocks are `UNRESOLVED`. Constants inherited from super types found in sources are resolved.
- Java only; Kotlin sources are not scanned.
- Static analysis misses runtime-only behaviour (dynamic URLs, service discovery lookups at runtime).

## Testing and benchmark

Golden tests run the full scan over the synthetic fleet in `src/test/resources/fleet` and compare against `src/test/resources/golden`. After an intended change, regenerate and review the diff:

```
./gradlew test -PupdateGolden
```

Real-world benchmark: `scripts/bench-clone.sh` clones spring-petclinic-microservices, its config repo, piggymetrics and ewolff/microservice-kafka at pinned commits into a git-ignored `bench/` folder (clone and read only, never built). Findings are recorded in `docs/benchmark.md`. Design background and decisions D1-D8 are in `docs/research.md`.

## License

Apache-2.0. groupId `io.github.sunjirapath`.
