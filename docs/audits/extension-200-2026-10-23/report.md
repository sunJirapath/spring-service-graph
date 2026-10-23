# Extension to 200 public repositories

> Historical build results. See the [full 200-repository follow-up](../full-200-2026-10-23/report.md)
> for complete current-build coverage and subsequent corrections.

The combined audit now covers **200 unique public repositories**. This extension adds
**90 new Spring Boot 4+ projects: 45 Maven and 45 Gradle**, containing 14,489 tracked
production Java files. It found **two additional scanner bugs**, both fixed with
regressions. The library passes **756 tests**, and all **90 added repositories pass**
the final repeated-scan, graph-integrity, identical-input diff and MCP checks.

The [200-repository catalog](catalog-200.json) preserves campaign membership and pinned
commits. The [new inventory](repositories.md), [CSV](repositories.csv) and
[manifest](manifest.json) identify the 90 added inputs. Public repository names are
intentionally included; the private local run-history registry is excluded.

## Scope and counting

| Campaign | Repositories | New unique repositories | Scanner | Successful scan processes |
|---|---:|---:|---|---:|
| [Original baseline](../github40-2026-10-23/report.md) | 40 | 40 | Before the first eight fixes | 80 |
| [Boot 4 post-fix validation](../boot4-80-2026-10-23/report.md) | 80 | 70 | First eight fixes | 160 |
| This extension, before new fixes | 90 | 90 | First eight fixes | 180 |
| Same extension, intermediate fixes | 90 | 0 | Ten fixes, before final method-constraint hardening | 180 |
| Same extension, final fixes | 90 | 0 | All ten fixes plus method-constraint hardening | 180 |
| Total | 390 repository/scanner records | **200 unique repositories** | Multiple builds | **780** |

The 80-repository campaign overlaps the original baseline by ten repositories.
The new 90 overlap neither earlier corpus. **170 unique repositories have pinned
Boot 4+ evidence, split 85 Maven / 85 Gradle**; the other 30 retain their historical
baseline qualification. **The latest scanner was tested on the new 90**, not on all
200. Earlier results keep their original scanner hashes and limitations.

| Added tier, by tracked production Java paths | Maven | Gradle |
|---|---:|---:|
| Small: 1–49 | 20 | 15 |
| Medium: 50–249 | 17 | 25 |
| Large: 250–999 | 8 | 4 |
| Huge: 1,000+ | 0 | 1 |
| Total | **45** | **45** |

This extension prioritizes new repositories and equal build-system counts; it is
not balanced across tiers. The previous Boot 4 corpus still contains ten repositories
per tier for each build system. Selection is purposive, including related Bancolombia
scaffolds and samples, so these results are not a random-sample accuracy estimate.

Every new source commit, clean checkout, production-Java count and Boot declaration
was verified locally. Build labels identify the selected descriptor, not exclusive
build-tool support or a guarantee that every module uses that Boot version.
`brasil-transparente/brasil-transparente-processor` was rejected before scanning because
its batch wrapper has inconsistent CRLF normalization; it was replaced by
`bancolombia/async-dataflow` and is not counted. `sajo-team/sajo` was qualified using
the corrected root `build.gradle:2` evidence.

## Two confirmed bugs and fixes

| Bug | Root cause → fix | Public before/after evidence |
|---|---|---|
| Production package names mistaken for build output | `JavaIndex` searched the whole source path for `/target/`, `/build/` and `/src/test/`. Limit directory exclusions to the layout before `src/main/java`. | `target/POSSUM` previously produced an empty graph; it now produces one service and 50 endpoints. |
| Missing WebFlux nested route prefixes | Functional-route extraction ignored enclosing `RouterFunctions.nest(...)`. Compose supported nesting paths with inner routes and the configured context path; preserve unresolved predicates as unresolved. | Nine `robalexlimas/franquicias-api` routes now include `/api`; its separate `/health/db` route is unchanged. |

POSSUM's pinned [DeviceMain launcher](https://github.com/target/POSSUM/blob/1cc04b0de3329adc0645b9dcaf967c8052925b37/src/main/java/com/target/devicemanager/DeviceMain.java#L25)
uses the ordinary `@SpringBootApplication` annotation. Its package is `com.target`.
The corrected graph includes `GET /v1/scan`, with source evidence in
`ScannerController.java`. This was a source-filter defect, not an unsupported launcher.

The pinned [WebFlux route declaration](https://github.com/robalexlimas/franquicias-api/blob/ee0c6206a3da054041aa8d67f78c9cae2a03af1e/infrastructure/entry-points/reactive-web/src/main/java/co/com/bancolombia/api/RouterRest.java#L193)
nests its route tree under `path("/api")` combined with an `accept(...)` predicate.
The old graph claimed `GET /franchises/{franchiseId}/top-products` with `EXACT`
confidence. The new graph correctly emits
`GET /api/franchises/{franchiseId}/top-products`, preserving handler/source evidence.
Spring's [functional routing documentation](https://docs.spring.io/spring-framework/reference/web/webflux-functional.html)
describes this nesting behavior.

Five focused regression tests cover package-directory collisions, composed nesting
and context paths, routes outside a nest, unresolved/disjunctive predicates, and
preventing nesting predicates from becoming handlerless endpoints. Contradictory or
unknown nested method constraints are marked unresolved rather than claiming an exact
path; matching method constraints have a passing control. The first three
were run against the prior scanner and failed as expected; their failures are retained
in [baseline-regressions.json](evidence/baseline-regressions.json). Only these two
public graphs changed across the 90-repository rerun; all other 88 graph JSON values
are unchanged. [Graph changes](evidence/graph-changes.json) list every changed endpoint.

Final method-constraint hardening was found through review and a focused regression.
All 90 final corpus graphs are byte-identical to the intermediate graphs that were
independently checked against source; final source-check records retain that provenance.

## Verification and what it establishes

| Check | Final result |
|---|---|
| Library test suite | 756 passed; zero failures, errors or skips |
| Earlier eight CLI reproductions | 8/8 still pass; retained separately from the original wrong-result evidence |
| Repeated source scans | 180/180 exit 0; two serial JVM runs per repository, 768 MB heap |
| Determinism | 90/90; SHA-256 inventories agree for all 592 generated files per run |
| Graph integrity | 90/90 nonempty service graphs; references and node IDs validated |
| Source evidence | 15,363 edge/finding locations checked against regular source files and line bounds |
| Identical-input diff | 90/90 exit 0 with “No changes” under `--fail-on-breaking` |
| MCP smoke | 90/90 initialize, tools/list and all three tools pass; sampled incoming edges, depth-one impact and service cards agree with the graph |
| Source-to-graph sampling | 15 Maven repositories / 25 facts verified; 19 Gradle facts verified, one SQS fact unsupported; POSSUM's regression checked separately |
| Harness input checks | 9 pass, including original tier balance, extension build balance, duplicates, Boot floor and checkout containment |

Before the two new fixes, all 180 scans also exited 0, but only 89/90 met structural
checks: POSSUM was empty. The WebFlux prefix bug passed those checks and was found
through source inspection. This demonstrates why an exit code, deterministic output,
or MCP agreement alone does not establish semantic correctness.

Final extracted totals are 161 services, 2,608 endpoints, 511 `CALLS` and 3,221
`REACHES` edges. These are scanner output counts, not complete ground truth. The
graphs retain 195 unresolved findings, 12 shared-table findings, four contract-drift
findings and one duplicate-service finding; they are not silently treated as resolved.
Three `gpt-6-luna` agents handled candidate qualification and focused source checks;
the coordinator independently reproduced the bugs, integrated fixes and ran checks.

## Scanner identity and reproduction

The final application JAR SHA-256 is
`c0265ff65ec110a06b75b52c3ebf70f89011e3554202eafe2805fe759acf07b4`.
The dependency-inclusive distribution SHA-256 is
`208008249d565c316fe6f1fd9d35804e79a72223c706821046522e46c47c3a69`.
It was built from the uncommitted production fixes and tests on top of
`c667253b7bb36684825ba55f53dee51b23931395`. That base commit alone does not identify
the tested scanner: use these hashes and [source hashes](evidence/scanner-source-sha256.json).
The before phase used the previously tested JAR `30863a952bcc4c8acf034b2f64be71e34023b5bbda60ef3d844db4ec8975b333`.

From the repository root, with Java 21, Python 3 and Git available:

```powershell
New-Item -ItemType Directory -Force build/audit200/tmp | Out-Null
$env:TEMP = "$PWD/build/audit200/tmp"
$env:TMP = $env:TEMP
$env:GRADLE_OPTS = "-Djava.io.tmpdir=$env:TEMP"
./gradlew.bat test installDist --no-daemon
python docs/audits/extension-200-2026-10-23/acquire.py
python docs/audits/extension-200-2026-10-23/run-corpus.py
python docs/audits/github40-2026-10-23/reproduce.py --expect fixed
```

Acquisition checks the published manifest and refuses dirty or incorrect pins. The
runner retains build-specific results and appends completed runs to the private local
history. Future builds must be checked independently; repository-name reuse does not
mean a new scanner has been validated. See the [evidence index](evidence/README.md)
for archived graphs, output hashes, results and source-check records.

## Limits

No downloaded project was compiled, tested, started, or connected to its external
services. These are source-scanner checks, not application runtime or security audits.
Sparse snapshots include tracked production Java, `src/main` resources, build
descriptors, Gradle catalogs, SQL and named API specifications; unrelated assets and
arbitrarily named specifications may be absent. Kotlin sources, dynamic runtime
behavior, custom external launchers and unrecognized APIs remain coverage limits.

`sivaprasadreddy/spring-cloud-aws-demo` uses `SqsTemplate.send`; AWS SQS extraction
is unsupported, so its messaging fact remains explicitly unverified. No precision or
recall percentage is inferred from the source spot-checks. The five historical
Taotao empty-graph results in the previous 80-repository report retain their original
status and were not rerun as part of this extension.

Task-created source clones and scratch data are removed after evidence retention.
The private history and pre-existing benchmark checkouts remain local; global Windows
TEMP/TMP settings are unchanged. Cleanup counts are retained in
[cleanup.json](evidence/cleanup.json).
