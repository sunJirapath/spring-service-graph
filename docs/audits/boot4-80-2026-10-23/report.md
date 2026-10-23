# Spring Boot 4+ post-fix validation: 80 public repositories

> Historical build results. The record-annotation false diagnostic and archetype-template
> source selection were subsequently fixed; see the [remaining-fixes report](../remaining-fixes-2026-10-23/report.md).

All eight findings from the [original 40-repository audit](../github40-2026-10-23/report.md)
are fixed, with retained regressions. The fixed library passed **751 tests**
and all **8 CLI reproductions**. The new corpus contains **80 distinct pinned public
repositories: 40 Maven and 40 Gradle**, with 10 in every requested size tier for each build system.

**Corpus result: 75/80 pass the combined nonempty-graph, integrity,
determinism and smoke checks; 5 remain explicitly unsuccessful.**
All 160 source-scan processes exited successfully; an empty graph is
not counted as a passing repository. This is a scanner validation campaign, not
a claim that the downloaded applications build, run, or contain no bugs.

## Scope and selection

| Tier by tracked production Java files | Maven | Gradle |
|---|---:|---:|
| small | 10 | 10 |
| medium | 10 | 10 |
| large | 10 | 10 |
| huge | 10 | 10 |
| Total | 40 | 40 |

Small means 1–49, medium 50–249, large 250–999 and huge 1,000+ tracked paths
matching `**/src/main/java/**/*.java`. The corpus totals **52,091 files**.
Counts, clean checkout status, full commit SHA and Boot version evidence were verified locally.
The [inventory](repositories.md), [manifest](manifest.json) and [CSV](repositories.csv)
retain every repository name and pin. Public repository names are intentionally published.

Build labels identify the selected Maven or Gradle descriptors, not exclusive build-tool
support: some official guides contain both. Each repository occupies one slot only.
Boot 4+ is established from a pinned parent, dependency BOM, plugin or version catalog;
this does not establish that every module uses the same version or that its runtime works.
SCM Platform declares a 4.1.0 parent and explicit starter versions from a 4.1.1 property;
both are recorded. Selection is purposive, not random: the Gradle group contains several
Taotao and kl3inIT projects, and the small group contains multiple Spring guides.

Checkouts were sparse source snapshots containing all tracked production Java,
`src/main` resources, build descriptors, Gradle catalogs, SQL and named API specs.
Other assets and arbitrarily named specs outside these paths may be absent. No foreign
build scripts, tests, services, containers or application entry points were executed.
The scanner used the default profile, no aliases and no explicit config repository.

## Changes and verification

See [root cause, fix and regression coverage](fixes.md) for all eight fixes and the
related OpenAPI context-path, config-root and evidence-name hardening.
Three `gpt-6-luna` agents handled qualification and focused review; the coordinator
implemented and integrated the fixes, reproduced the cases and ran verification.

| Check | Result |
|---|---|
| Library tests | 751 passed; 0 failures, 0 errors, 0 skipped |
| Fixed CLI cases | 8/8; original wrong-result evidence remains separate |
| Repeated scans | 160/160 exit 0; serial JVMs with 768 MB heap |
| Determinism | 80/80 repositories; all 854 generated files per run compared by SHA-256 |
| Evidence checks | 123,231 edge/finding references checked against regular source files and line bounds |
| Identical-input diff | 80/80 return 0 and “No changes” with `--fail-on-breaking` |
| MCP smoke | 75 pass initialize, tools/list and all three tools; 5 skipped for empty graphs; incoming edges and depth-1 impact compared with the graph |
| Independent source sampling | 8 exact source-to-graph matches across both build systems and all four tiers; one unsupported sample recorded separately |

Graph totals are 307 services, 12,018 endpoints,
1,365 CALLS and 35,881 REACHES edges. These are extracted
counts, not established ground truth. Structural checks and MCP agreement with the
graph do not establish extraction precision or recall. Parse-error, unresolved-value,
shared-table and other findings remain visible in the retained graphs.

The harness initially treated repository-owned parse diagnostics as missing service
references in three graphs. That assertion was corrected to match the scanner's
diagnostic contract; both output hash inventories were rechecked and MCP checks
completed. Scanner code and graph outputs were unchanged. The corrections are retained
in the machine-readable results.

The scanner application JAR SHA-256 is `30863a952bcc4c8acf034b2f64be71e34023b5bbda60ef3d844db4ec8975b333`;
the dependency-inclusive distribution SHA-256 is `f34362d1091cbc8716fa6ec12e497c7717ec9e25aec3cafdd7d3a7028ec2de82`.
The build was made from the uncommitted fixes on `codex/github40-audit-fixes`, based on
`93a1d78f57063866bf075f14d36e81517e9a70b0`. That base commit alone does not identify the fixed scanner;
use the JAR hash and the source in this PR. The same installed distribution was used
throughout this campaign. Timings reflect host load and are not a comparative benchmark.

## Remaining limits and unsuccessful cases

- `shuigedeng/taotao-cloud-sys`: scan returned zero services.
- `shuigedeng/taotao-cloud-goods`: scan returned zero services.
- `shuigedeng/taotao-cloud-order`: scan returned zero services.
- `shuigedeng/taotao-cloud-member`: scan returned zero services.
- `shuigedeng/taotao-cloud-message`: scan returned zero services.

The five empty Taotao service graphs use `@TaoTaoBootApplication` and
`StartupSpringApplication`, supplied by external dependencies. Their Boot 4 build
declarations qualify them for the corpus, but their custom app discovery is outside
the scanner's documented direct `@SpringBootApplication` / `SpringApplication`
support. Hardcoding that third-party annotation or inferring all arbitrary launchers
would create false services. These failures are retained, not replaced to improve the score.
The larger Taotao repository also contains ordinary discoverable applications; its
nonempty graph does not prove coverage of its custom launchers.

The Synapse repository exposes a JavaParser record-accessor validation false positive
for a generic type-use annotation. A minimal JDK 21 probe compiled, and the inspected
PUT endpoint and data reach remain present; the diagnostic is retained. See the
[parser review](evidence/parser-review.json). Other external-source parse problems
have not all been independently compiled and are not presumed false positives.
No parser diagnostics were suppressed or dependency version changed to hide them.

Runtime configuration, generated sources, custom handler mappings, dynamic values,
Kotlin and unsupported transports still limit completeness, as described in the
baseline report. No full foreign-project build or runtime integration test was run.

## Evidence and reproduction

- [Machine-readable results](evidence/scan-results.json), [metadata](evidence/metadata.json) and [validated source hashes](evidence/scanner-source-sha256.json)
- [All 80 first-run graphs](evidence/scan-graphs.zip), including empty unsuccessful graphs
- [Both runs' output SHA-256 inventories](evidence/output-sha256.json)
- [Source-to-graph sampling](evidence/source-checks.json), [unit test totals](evidence/unit-tests.json), [fixed reproduction results](evidence/fixed-reproductions.json) and [outputs](evidence/fixed-reproduction-outputs.zip)
- [Publication review](evidence/publication-review.json) and [cleanup record](evidence/cleanup.json)

```powershell
New-Item -ItemType Directory -Force build\audit-tmp | Out-Null
$env:TEMP = (Resolve-Path build\audit-tmp).Path
$env:TMP = $env:TEMP
$env:GRADLE_OPTS = "-Djava.io.tmpdir=$env:TEMP"
.\gradlew.bat --offline test installDist --no-daemon
python docs\audits\github40-2026-10-23\reproduce.py --expect fixed
python docs\audits\boot4-80-2026-10-23\acquire.py
python docs\audits\boot4-80-2026-10-23\run-corpus.py
```

The acquisition/scanning logic above was exercised for this campaign; the retained
public entry points were syntax-checked and checked against the final manifest.
The full campaign was not repeated after cleanup. The runner returns nonzero while
the five unsupported app cases remain. It writes the private local registry under
the ignored `.local-audits/` directory; no registry contents are published.
All task TEMP/TMP and JVM scratch paths stay under the workspace on E:. Global
Windows TEMP/TMP and pre-existing benchmark checkouts are preserved. Selected public
evidence remains in this directory after task-created checkouts and scratch are removed.
