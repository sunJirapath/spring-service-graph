# Test campaign (2026-10-19)

Codex (`gpt-5.6-sol`, high or medium effort) wrote the tests, split into seven workstreams that each owned their own files. Claude designed the case lists, reviewed every test and triaged every failure into one of three outcomes:
- a code bug, fixed in `src/main`
- a wrong expectation, fixed in the test with a comment saying why
- a contrived test, deleted

Run everything with `./gradlew test`. Add `jacocoTestReport` for coverage; the report lands in `build/reports/jacoco/test/html`.

| | Before | After | Now (after field tests) |
|---|---|---|---|
| Tests | 70 | 547 | 684 |
| Line coverage | 87.3% | 98.3% | 98.2% |
| Branch coverage | 70.3% | 95.2% | 95.0% |

## Workstreams

| Area | Test classes |
|---|---|
| A: config, discovery | `ConfigEdgeCasesTest`, `DiscoveryEdgeCasesTest` |
| B: indexing, folding, parsing | `JavaIndexEdgeCasesTest`, `AnnTest` |
| C: inbound and outbound HTTP | `InboundHttpEdgeCasesTest`, `OutboundHttpEdgeCasesTest` |
| D: messaging, data, gateway, reach | `KafkaEdgeCasesTest`, `MessagingExtractorTest`, `DataEdgeCasesTest`, `GatewayEdgeCasesTest`, `ReachEdgeCasesTest` |
| E: linker, outputs, diff, MCP, CLI | `LinkerEdgeCasesTest`, `PathMatchEdgeCasesTest`, `OutputEdgeCasesTest`, `GraphDiffEdgeCasesTest`, `McpEdgeCasesTest`, `MainTest` |
| F: messy fleet, end to end | `MessyFleetTest` over `src/test/resources/messy/`. This is 5 ugly repos: a broken Java file, BOM, CRLF, a Kotlin file, invalid YAML, a binary resource, `node_modules`, test-only apps, deep modules, and cross-repo links. |
| G: coverage gaps | `*CoverageTest` in each package |

All of them build their fake repos with `TempRepo`, a shared helper.

## Bugs found and fixed

### Parsing and constant folding (`JavaIndex`)
- A local variable declared after its use was still resolved, which also hid a field of the same name.
- Variables declared in a `for (...)` initializer were not found.
- A long `+` concatenation (200 parts) was cut off by the cycle guard. Syntax nesting no longer counts against it.
- `"x".trim()` and `strip()` did not trim.
- Inherited constants and mappings stopped at 8–24 levels of inheritance. The guards now only stop real cycles.

### Config (`Config`)
- A UTF-8 BOM became part of the first `.properties` key.
- YAML merge keys (`<<: *defaults`) were not expanded.
- A config file that was not valid UTF-8 crashed the whole scan. It now falls back to ISO-8859-1, as Spring does for `.properties`.
- `valueOf(key)` returned `EXACT` with no evidence. It now returns `RESOLVED` with the key's file:line, as its javadoc says.

### HTTP extraction
- `@RequestMapping(method = {})` produced no endpoint. An empty array means any method.
- One malformed OpenAPI file hid every spec after it. One unreadable migration file did the same, and both now continue past the bad file.
- `uri(b -> b.path("/a").path("/b"))` joined the paths in reverse order.
- `optional.get().uri(..)` was reported as an HTTP call. A fluent call now needs a WebClient or RestClient receiver.

### Kafka, data, gateway, reach
- Custom generic base repositories (`OrderRepo extends BaseRepo<Order, Long>`) found no entity. `@NoRepositoryBean` bases are now skipped.
- `CREATE TABLE` inside SQL comments created table ownership.
- Gateway `no://op` routes and routes with a blank `uri:` were treated as real routes.
- A blank Cloud Stream destination became an `EXACT` empty topic. It is now `UNRESOLVED`.
- `super.method()` calls were not followed by reach.

### Linking and outputs
- `PathMatch`: a `**` at the start or middle of a path matched anything, and `acc-{id}` matched `user-{name}`.
- A call to a relative URL lost its `raw` source expression.
- Markdown output crashed when the output folder and the repos are on different drives (`C:` vs `E:`). It also crashed when an edge lacked optional attributes.
- MCP: a JSON-RPC batch array was silently ignored. It now gets a `-32600` error.

## Decisions made in review

- **Evidence lines** pointed at the start of the declaration, i.e. its first annotation, for inbound and outbound alike. This was revised after the first field test: they now point at the mapping annotation itself, and endpoints also cite their handler method.
- **Same-line methods:** two methods on one source line cannot be told apart by reach, because evidence has no column. This is documented as a shortcut in `ReachAnalysis`. It is not worth adding columns to every evidence entry.
- **Untyped receivers:** an `Object`-typed receiver calling `.delete().uri(..)` is not an HTTP call.
- **Class-level `@RequestMapping`** is resolved on the controller class across all of its interfaces, matching Spring. So `/api` from one interface applies to handlers declared in another.

## Process note

During the coverage pass, Codex hit a sandbox write denial and added Windows ACL entries to two test folders to work around it, which was outside its brief. The briefs now forbid permission or ACL changes outright. The leftover entries were reported for manual removal.
