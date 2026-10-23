# Public repository audit summary

On **2026-10-23**, the final `spring-service-graph` scanner was tested against
**200 pinned public repositories**: **195 validation passes, five known unsupported
inputs, zero unexpected failures**. All **16 confirmed scanner defects** were fixed
with regression coverage and merged in [PR #2](https://github.com/sunJirapath/spring-service-graph/pull/2).

This tests the scanner against source snapshots. The applications were not built,
started or tested against live services.

## Coverage and verification

| Check | Final audited build |
|---|---:|
| Unique repository/commit pairs | 200 |
| Qualified Spring Boot 4+ inputs | 170: 85 Maven, 85 Gradle |
| Other historical inputs | 30; Boot versions unspecified |
| Selected build descriptors across all inputs | 111 Maven, 89 Gradle |
| Measured tiers: small / medium / large / huge | 62 / 69 / 38 / 31 |
| Tracked production-layout Java paths | 105,648 |
| Successful source scan processes | 400: two per repository |
| Byte-identical repeated-output checks | 200/200 |
| Identical-input breaking diffs | 200/200 |
| MCP graph-query consistency checks | 195/195 nonempty graphs |
| Source evidence references checked | 231,655 |
| Independent source spot checks | 12 across ten repositories: 11 verified, one unsupported SQS operation, zero misses |
| Library unit tests | 768 passed; zero failures, errors or skips |
| CLI regression cases | 16 passed |

Tiers count tracked Java paths beneath `src/main/java`: small 1–49, medium 50–249,
large 250–999, huge 1,000+. Resource templates remain excluded from scanning.
The original Boot 4+ campaign had ten repositories per tier for each build system;
the expanded 200-repository corpus is not balanced across tiers. Four historical
inputs contain both build descriptor types. Selection was purposive, including
related projects, so these results are not a random-sample accuracy estimate.

The final graphs contain 638 services, 23,899 endpoints and 106,411 edges. Compared
with the preceding complete campaign, 199 graphs are byte-identical; one corrects
a lexical-error evidence location from line 1 to 155. All 16 graphs changed from
older retained references have source explanations in the archived evidence.

## Confirmed defects fixed

| # | Root cause → fix |
|---|---|
| 1 | Broad framework filtering hid application HTTP APIs → narrow the exclusion and retain OpenAPI fallback. |
| 2 | Elasticsearch used Mongo naming attributes → read `indexName`, preserving Mongo collection overrides. |
| 3 | Mongo mapping required annotated domains → infer default collections and resolve generic repository entity parameters. |
| 4 | API retirement checked surviving services without surviving calls → require a remaining call before reporting a breaking removal. |
| 5 | Rabbit overloads confused payloads with destinations → recognize metadata arguments and leave ambiguous destinations unresolved. |
| 6 | URL authority parsing consumed root queries/fragments → stop at `/`, `?` or `#`, preserving IPv6 literals. |
| 7 | Component scan roots omitted enabled dependency-module Feign clients → resolve Feign package/class registration separately. |
| 8 | Duplicate-service findings cited directories → cite the configuration or launcher file and line. |
| 9 | Build-directory exclusions matched production package names → apply layout exclusions before the production source root. |
| 10 | WebFlux extraction lost nested route prefixes → compose supported nesting paths and preserve unknown constraints as unresolved. |
| 11 | Record type-use annotations caused false parser errors → narrowly revalidate the specific diagnostic while preserving the AST and real errors. |
| 12 | Resource archetype templates looked like production sources → exclude resource layouts before nested production-source markers. |
| 13 | Discovery missed source-defined composed application annotations → resolve runtime-retained meta-annotations with cycle detection. |
| 14 | Composed Feign overrides erased independent package attributes → override only the target member and union both package arrays. |
| 15 | Non-runtime composed configuration activated clients → require runtime retention throughout source meta-annotation traversal. |
| 16 | Lexical exceptions without token ranges defaulted to line 1 → recover the anchored line only for the lexical exception type. |

Related safeguards prevent duplicated OpenAPI context paths, keep configuration
search paths inside their root and reject ambiguous checkout basenames. Regression
fixtures remain in [`src/test/resources/audit-fixtures`](../../src/test/resources/audit-fixtures);
the existing unit suite runs with `./gradlew.bat test --no-daemon` on Java 21.

## Limits and remaining findings

Five `shuigedeng/taotao-cloud-*` inputs (`sys`, `goods`, `order`, `member`, `message`)
depend on external launcher definitions absent from the snapshots. They produce
deterministic empty graphs and remain **unsupported**, with no MCP check.

The graphs retain **3,955 findings**: 3,448 unresolved values/types, 415 shared-table
findings, 26 missing interfaces, 48 contract drifts, five duplicate-service findings
and 13 parse errors. All 13 parse errors were verified as malformed upstream Java;
the other findings were not all individually reviewed. A validation pass does not
mean an input has no findings or establish whole-corpus precision/recall. MCP checks
verify consistency with the extracted graph, not independent extraction accuracy.

`SqsTemplate.send`, Kotlin-only behavior, dynamic configuration, generated/dependency
bytecode, application startup, databases, brokers and live traffic remain unsupported
or untested. Runs used the default profile without aliases or an external config repo.

## Build identity and full evidence

The audited scanner source is `84831f02916e3090a031b6e0433927e05f72f67a`.

```text
Scanner JAR SHA256:
3a6448851936edfd488b5cf98d093047f4bdea1207c9cbe2d321c47e94abdaf9
Dependency-inclusive distribution SHA256:
313f4172f17aaca473be2ab4a7f3a79d09f9bbab594c24af1e2024bb2c8ebcf1
```

This summary replaces the campaign folders. Their full reports, pinned repository
names, manifests, harnesses, source reviews and graph archives remain available at
the immutable published commit `1fb147ac3aa227ba99a2a4596aa0012c1b513adc`:

- [All five historical campaigns and evidence](https://github.com/sunJirapath/spring-service-graph/tree/1fb147ac3aa227ba99a2a4596aa0012c1b513adc/docs/audits)
- [Detailed final report and reproduction instructions](https://github.com/sunJirapath/spring-service-graph/blob/1fb147ac3aa227ba99a2a4596aa0012c1b513adc/docs/audits/full-200-2026-10-23/report.md)
- [200-repository pinned manifest](https://github.com/sunJirapath/spring-service-graph/blob/1fb147ac3aa227ba99a2a4596aa0012c1b513adc/docs/audits/full-200-2026-10-23/manifest.json)
- [Final per-repository results](https://github.com/sunJirapath/spring-service-graph/blob/1fb147ac3aa227ba99a2a4596aa0012c1b513adc/docs/audits/full-200-2026-10-23/evidence/after-results.json)
- [Retained graphs and verification evidence](https://github.com/sunJirapath/spring-service-graph/tree/1fb147ac3aa227ba99a2a4596aa0012c1b513adc/docs/audits/full-200-2026-10-23/evidence)
- [Successful audit CI run](https://github.com/sunJirapath/spring-service-graph/actions/runs/39970430023)

These are historical results for the identified scanner and source pins; changes
require fresh verification. The private local run-history registry remains excluded
from Git. Task-created clones and scratch outputs were removed after archiving;
pre-existing benchmark checkouts were preserved.
