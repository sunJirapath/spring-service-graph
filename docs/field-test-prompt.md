# Field-test prompt: run ssg on a real fleet

Give everything below the line to an agent that works inside the real project. Fill in the `<...>` placeholders first. If you use several agents (for example one per team or fleet slice), give each the same prompt with its own `<FLEET_ROOT>` and ask each for its own report.

---

You are field-testing **spring-service-graph (`ssg`)**. It is a static scanner that reads many Spring Boot repositories, without building them, and writes a cross-service dependency graph:
- service → endpoint → outbound HTTP call → target service/endpoint
- service → Kafka topic (produce/consume)
- service → database table (owns/uses)

Every edge carries file:line evidence and a confidence: `EXACT` (a literal), `RESOLVED` (through a constant or property) or `UNRESOLVED` (dynamic, kept and shown). Your job is to run it on this real fleet and measure how well it meets its original specification, summarized in section 3. Then write a feedback report.

## 0. Hard rules (read first)

- **Read-only on the fleet.** Never modify, build, commit or push anything in the scanned repositories. Write all output to `<SCRATCH_DIR>`, outside every repo.
- **Nothing proprietary leaves this project.** The report goes back to a public open-source project, so it must contain:
  - no source code, file paths, class names, service names, hostnames, topic names, table names, URLs or config values from this codebase
  - placeholders instead: `svc-A`, `svc-B`, `topic-1`, `table-1`, `host-1`, `Foo.java:120`
  - when a construct is missed, a **minimal synthetic reproduction** you invent from scratch (generic names like `OrderClient`, `orders.created`) that shows the same pattern, never copied code
- **Never print or copy secrets.** Do not open `.env` files, `.secrets/` or private keys. ssg drops values of secret-looking config keys; if you see one in any output anyway, report it as a **P0 bug** with the key name only.
- Do not send `graph.json` or the Markdown pages anywhere. Only the anonymized report leaves `<SCRATCH_DIR>`.

## 1. Get ssg (Java 21+ required)

Option A, the snapshot file (if it is reachable from your machine):
```bash
unzip <PATH_TO>/ssg-0.1.0-SNAPSHOT-aa937cf.zip -d <SCRATCH_DIR>/tool
sha256sum <PATH_TO>/ssg-0.1.0-SNAPSHOT-aa937cf.zip   # expect 97464296a8f25f76ccb01e4ac0e43d9890be506679abd17bec872b2012fd3c21
SSG=<SCRATCH_DIR>/tool/ssg-0.1.0-SNAPSHOT-aa937cf/bin/ssg     # bin\ssg.bat on Windows
```

Option B, build the same commit from source (needs internet):
```bash
git clone https://github.com/sunJirapath/spring-service-graph <SCRATCH_DIR>/ssg-src
git -C <SCRATCH_DIR>/ssg-src checkout aa937cfbe5bce224c2a66ce2128c59315d40968e
<SCRATCH_DIR>/ssg-src/gradlew -p <SCRATCH_DIR>/ssg-src installDist
SSG=<SCRATCH_DIR>/ssg-src/build/install/ssg/bin/ssg
```

Check it works: `$SSG --help`. Then read `README.md` and `docs/research.md` from the ssg repo (in the zip's source commit or on GitHub). They describe the intended behaviour and the known limits, and you should not report the documented limits as bugs. Do say whether they matter here.

## 2. Run it

```bash
$SSG scan --repos <FLEET_ROOT>/* --profile <PROFILE_OR_default> \
    [--aliases <ALIASES_YML>] [--config-repo <CONFIG_REPO_CHECKOUT>] \
    [--sql-methods <Wrapper.method>...] [--link-base '<https://your-git/{repo}/blob/<branch>/{file}#L{line}>'] \
    --out <SCRATCH_DIR>/out
```

- **JDBC wrappers:** if the code runs SQL through in-house wrapper methods, run once without `--sql-methods`. ssg reports `UNRESOLVED` findings naming the wrappers to list. Then run again with them listed, and report both runs.
- **Timing and stderr:** time it (wall clock and peak memory if you can) and keep stderr; warnings matter.
- **Aliases:** if hosts in this fleet are Kubernetes names, gateways or external systems that are not equal to a `spring.application.name`, write a small `aliases.yml` (a flat `host: target` map) and run again. Report how many aliases were needed.
- **Determinism:** run the same scan twice into two folders and diff them byte for byte. Any difference is a bug.
- **Diff on a real merge request:** if a recent merged change touched endpoints, topics or tables, scan the fleet with the repo at its parent commit and again at the merge commit, using separate checkouts with the same folder name, outside the repo. Then run `$SSG diff --base <out-before> --head <out-after>`. Judge whether the report reflects the real change.
- **MCP server:** start `$SSG mcp --graph <SCRATCH_DIR>/out` from an MCP-capable client (or pipe JSON-RPC lines into it). Ask `who_calls`, `impact_of` and `service_card` about a few real things you can verify. Judge whether the answers are correct and useful.

Outputs: `out/graph.json`, `out/index.md` (the fleet overview and findings), `out/services/<name>.md`, and `out/diagrams/`.

## 3. Original specification to check against

For each item, give a status (works / partial / missing / wrong), counts, and an anonymized example where it fails.

**Discovery and config**
1. Each repo or module with `@SpringBootApplication` becomes a service, named by `spring.application.name` and falling back to the directory name. Multi-module repos can hold more than one app.
2. `application.yml`/`.properties` plus the selected profile load correctly, and `${a:default}` placeholders resolve. Secret values are never output.

**Inbound HTTP**

3. `@RestController`/`@Controller` with `@RequestMapping` and its variants. Class-level and method-level paths are joined, and `"api"` becomes `"/api"`.
4. Mappings declared only on implemented interfaces (openapi-generator `interfaceOnly`) are found. If the generated sources are absent, the OpenAPI spec is used instead, or a `MISSING_INTERFACE` finding is raised.

**Outbound HTTP**

5. `@FeignClient` (`name`/`value`, `url`, `path`) with its method mappings.
6. `@HttpExchange` interfaces.
7. `RestClient`/`WebClient`/`RestTemplate` calls whose URL is a literal, constant or property. This is best-effort, and dynamic URLs must appear as `UNRESOLVED`, not be missing.
8. Spring Cloud Gateway routes (YAML/properties, `lb://`, `StripPrefix`, `RewritePath`).

**Kafka**

9. `@KafkaListener` `topics`/`topicPattern`.
10. `KafkaTemplate.send(topic, ...)`, including topics held in constants in other files or in properties with defaults.
11. Spring Cloud Stream `bindings.*.destination` and `StreamBridge`.

**Data**

12. `@Entity`/`@Table` and `@Document`.
13. Spring Data repositories map to their entity's table, including derived queries that don't exist in source.
14. Ownership from Liquibase `createTable`, Flyway and `schema.sql`.

**Linking**

15. Feign name / `lb://` / host = `spring.application.name` → target service; other hosts go through `aliases.yml` or become externals.
16. Outbound paths are matched to target endpoints by template. An unmatched path becomes a `CONTRACT_DRIFT` finding.

**Endpoint reach** (Phase 2)

17. `REACHES` edges from a controller endpoint to the calls, topics and tables its handler reaches inside the service.

**Confidence and evidence**

18. Every edge has a correct file:line and a sensible `EXACT`/`RESOLVED`/`UNRESOLVED` confidence. Nothing is silently dropped.

**Outputs**

19. `graph.json`, the per-service pages (YAML front matter plus the sections Exposes, Calls, Publishes, Consumes, Data and Findings, with evidence links), `index.md`, `fleet.puml` and per-service `.mmd`.
20. Output is deterministic, so it can be diffed in git.

**Use cases**

21. Impact analysis: "if I change endpoint X / topic Y / table Z, who is affected?" Answer this from the output for 3 real cases, and check each answer against what you know or can grep.
22. Onboarding: is one service page enough for a newcomer?
23. Contract drift: are the `CONTRACT_DRIFT` findings real?

**Added after field test 1** (check that these work here too):

24. JDBC: tables in SQL passed to `JdbcTemplate`, `NamedParameterJdbcTemplate`, `JdbcOperations`, `JdbcClient` or a `--sql-methods` wrapper become `USES` edges. Endpoints `REACHES` those tables.
25. Nested checkouts (folders with their own `.git`, such as agent worktrees or submodules) are skipped. Two apps sharing a name give a `DUPLICATE_SERVICE` finding.
26. The OpenAPI fallback adds only the operations of the generated interfaces that are missing, with no duplicates of controller endpoints.
27. Evidence points at the mapping annotation, and endpoints also cite their handler method.
28. MCP accepts concrete paths and any `{var}` names (`GET /orders/123` finds `GET /orders/{orderId}`).
29. `--link-base` makes page links independent of where the fleet is checked out.

**Known pitfalls** (each should be handled):
- controllers whose annotations live only on a generated interface
- a class-level `@RequestMapping` without a leading slash
- topics and Feign URLs in constants or `${...:default}` properties
- derived queries
- multiple apps in one repo

## 4. Measure precision and recall (do the arithmetic, don't estimate)

- **Precision:** randomly sample at least 30 `CALLS` edges, 15 `PRODUCES`/`CONSUMES` edges, 15 `OWNS`/`USES` edges and 15 `REACHES` edges from `graph.json`. Verify each against the source at its evidence line: correct, wrong target, wrong path/method, wrong confidence, or wrong evidence line. Report the counts.
- **Recall:** grep the fleet's main sources (not tests) for each construct and compare with what ssg found. Report each as "found N / expected M":
  - `@FeignClient` interfaces
  - `@HttpExchange` interfaces
  - `@KafkaListener` usages
  - `kafkaTemplate.send(` / `.send(` on Kafka templates
  - `StreamBridge`
  - `@Entity`
  - `@Document`
  - `extends .*Repository<`
  - `createTable` / `CREATE TABLE` in migrations
  - controller classes
  - `RestTemplate` / `WebClient` / `RestClient` usages

  For every gap, find out why and add an anonymized synthetic repro.
- **Findings:** for `CONTRACT_DRIFT`, sample up to 20 and classify them as real drift or false positive (with the reason). For `UNRESOLVED`, group them by the shape of the raw expression (for example "URL from a `@ConfigurationProperties` getter", "base URL from a builder in another class") and give a count per group. These groups are the most valuable input for the next version.
- **Unattributed code:** do any services or modules show zero endpoints, calls and topics although they clearly have them?
- **Kotlin:** what share of services is Kotlin? ssg does not scan Kotlin yet, so count what is missing because of that.

## 5. Write the report

Write it to `<SCRATCH_DIR>/ssg-field-report.md` with exactly these sections:

1. **Summary**: 5 lines, ending with your overall verdict on whether it is usable for impact analysis on this fleet today.
2. **Environment**:
   - number of repos and services
   - Java/Kotlin split
   - Spring Boot versions
   - Maven/Gradle
   - scan time and memory
   - the ssg version printed by `mcp`'s `initialize`, or the snapshot name
   - aliases needed
3. **Spec checklist**: a table with items 1–29 from section 3, showing status, counts and notes.
4. **Precision and recall**: the tables from section 4.
5. **Findings review**: drift (true/false counts) and the `UNRESOLVED` groups with counts.
6. **Missed patterns**: each with a minimal synthetic Java repro, the expected graph edge, and what ssg produced. Order them by frequency in the fleet.
7. **Wrong results**: false positives and wrong targets, confidences or evidence, each with a synthetic repro.
8. **Crashes, warnings, determinism, secrets**: anything that broke, every distinct stderr warning type, the determinism result, and any secret leak.
9. **Diff and MCP**: how the merge-request diff and the three MCP tools did on real questions.
10. **Output usability**: what a human and an LLM agent needed but did not get.
11. **Top 10 fixes**: prioritized, each with impact (how many edges or services it would fix) and the evidence above it relies on.
12. **Deviations from the original spec**: where ssg behaves differently from section 3 by design (see `docs/research.md` D1–D8) and whether that design is right for this fleet.

Check the report against the rules in section 0 before you finish: no real names, paths, code or values. Then reply with the report path and the Summary section.
