# Remaining audit bugs: fixed

> Historical build results. The [full 200-repository follow-up](../full-200-2026-10-23/report.md)
> includes further diagnostic and Feign configuration corrections and complete current-build coverage.

All **13 confirmed scanner defects** identified across the audit and this reconciliation
now have fixes and regression coverage. This follow-up closes three remaining gaps;
the earlier [eight fixes](../boot4-80-2026-10-23/fixes.md) and
[two extraction fixes](../extension-200-2026-10-23/report.md) remain intact.

The final library passes **764 tests**, with zero failures, errors or skips. The eight
retained CLI reproductions still pass. Two pinned public repositories were each scanned
twice on the prior, intermediate and final builds: all twelve scan processes, six repeat-output
checks, six identical-input diffs and six MCP checks pass.

## Root cause → fix → verification

| Defect | Root cause and smallest correction | Verification |
|---|---|---|
| Valid record type annotations produce false parse errors | JavaParser compares accessor/component AST types including type-use annotations. For this exact diagnostic at the same record token range, rerun the record validator on an annotation-free clone. Preserve the indexed AST and all diagnostics that still occur. | Three legal record shapes compile with the JDK 21 compiler and index without errors. The actual Synapse diagnostic disappears; original type annotations remain in the AST. |
| Resource archetype templates are parsed as production Java | A nested `src/main/java` marker inside `src/main/resources` passes source selection. Exclude resource layouts before the production source root, retaining the trailing separator at that boundary. | Five Streampipes template diagnostics disappear. Invalid and application-shaped resource fixtures are ignored, including adjacent `resources/src/main/java`; actual production packages named `resources` remain indexed. |
| Source-defined composed application annotations are missed | App discovery checks only direct annotations, although scan configuration already resolves source meta-annotations. Walk source-resolved runtime-retained annotation declarations with cycle detection; use the same detection in discovery, library scan roots and service-name evidence. | Nested composed annotations create the correct service and module scan. Annotation declarations alone, non-runtime retention, cycles and unavailable external annotations do not create services. |

The first two defects are observed on pinned public inputs. The composed-annotation
defect was found during source reconciliation and reproduced with a focused fixture;
it was not demonstrated by the external Taotao annotations.

The [baseline regression record](evidence/baseline-regressions.json) retains **five
expected failures among 37 focused tests** on unchanged production code at
`de7682c676ab08e3db62c7bfa762761f69417df8`. Eight tests were added overall, including
passing controls. The resource test was subsequently extended to check an immediately
adjacent source marker, which failed before its boundary correction. Full verification
then passed all 764 tests. A further negative control reproduced false app detection
for non-runtime composition on the intermediate build; the final build requires
`RetentionPolicy.RUNTIME` on every traversed source-defined annotation. The
[retention regression](evidence/retention-regression.json) and intermediate build
evidence are retained separately.

Regression coverage is in
[JavaIndexDiagnosticsTest.java](../../../src/test/java/io/github/sunjirapath/ssg/scan/JavaIndexDiagnosticsTest.java)
and [DiscoveryEdgeCasesTest.java](../../../src/test/java/io/github/sunjirapath/ssg/scan/DiscoveryEdgeCasesTest.java).
Wrong accessor types, abstract records, illegal instance fields and real syntax errors
remain reported. No parser dependency upgrade or blanket diagnostic suppression was used.

## Public before/after checks

| Repository and pin | Before | After |
|---|---|---|
| `team-project-final/synapse-platform-svc` at `4a530a1017c223d1dfdbaa4d63e909a7183381bf` | One false accessor-type parse error | Zero parse errors; all nodes, edges and other findings unchanged |
| `apache/streampipes` at `c4fd92635236427ed96b3aa7ce9f98bb36a07b64` | Five false parse errors in archetype resource templates | Zero parse errors; all nodes, edges and other findings unchanged |

The Synapse [record component and accessor](https://github.com/team-project-final/synapse-platform-svc/blob/4a530a1017c223d1dfdbaa4d63e909a7183381bf/src/main/java/com/synapse/platform/admin/dto/AdminSettingsUpdateRequest.java#L10)
differ by a type-use annotation. Its `PUT /api/v1/admin/settings` endpoint and
`REACHES` dependency remain present. The Streampipes
[archetype resource](https://github.com/apache/streampipes/blob/c4fd92635236427ed96b3aa7ce9f98bb36a07b64/archetypes/streampipes-archetype-extensions-jvm/src/main/resources/archetype-resources/src/main/java/Init.java#L19)
contains Velocity directives, confirming it is a template rather than a compiled
production source. The other four affected paths contain template filename markers.

On the final build, **1,074 evidence locations** were checked against regular files
and source line bounds. Both repositories have byte-identical repeated output across
all ten generated files per run. Their 358 endpoints and 117 `REACHES` edges are
unchanged. The ten remaining findings are unresolved source values; they are not
silently converted into resolved results. [Graph changes](evidence/graph-changes.json)
retain the six removed diagnostics and the unchanged-node/edge assertions.

## Scope and evidence

The combined audit still covers **200 unique repositories**; these are existing inputs,
so no new repository is counted. **This final scanner build was field-tested on these
two repositories.** Earlier 40-, 80- and 90-repository campaigns retain their original
scanner identities. The complete 200-repository corpus was not rerun on this build.

The final application JAR SHA-256 is
`197e1339f4ee0af3390bd138e86690475b3ffcc3012455ee23be112c94c689c8`;
the dependency-inclusive distribution SHA-256 is
`7358a5a285f341b477b267bb79582ce0ecd44c1e132b8e8d7321d3d2e712090f`.
It was built from the uncommitted fixes and eight new regression tests on top of
`de7682c676ab08e3db62c7bfa762761f69417df8`.
The [source hash inventory](evidence/scanner-source-sha256.json) identifies the build
inputs. The before phase used JAR `c0265ff65ec110a06b75b52c3ebf70f89011e3554202eafe2805fe759acf07b4`.

- [Pinned manifest](manifest.json), [final metadata](evidence/after-metadata.json) and [per-repository results](evidence/after-results.json)
- [Before graphs](evidence/before-graphs.zip), [after graphs](evidence/after-graphs.zip), [before hashes](evidence/before-output-sha256.json) and [after hashes](evidence/after-output-sha256.json)
- [Unit test totals](evidence/unit-tests.json), [earlier CLI reproductions](evidence/prior-cli-reproductions.json), [their graph outputs](evidence/prior-cli-graphs.zip) and [summary](evidence/summary.json)
- [Publication review](evidence/publication-review.json) and [cleanup record](evidence/cleanup.json)

Graph ZIP entries use `owner--repository/graph.json`. Historical `build/final-bugs/...`
paths in results identify the original scratch locations; resolve them through the
corresponding phase archive after cleanup. Raw process logs and the private registry
are excluded. Graph bytes are unchanged; public result summaries omit stdout/stderr
tails and normalize workspace paths.

Reproduction from the repository root requires Java 21, Python 3 and Git:

```powershell
New-Item -ItemType Directory -Force build/final-bugs/tmp | Out-Null
$env:TEMP = "$PWD/build/final-bugs/tmp"
$env:TMP = $env:TEMP
$env:GRADLE_OPTS = "-Djava.io.tmpdir=$env:TEMP"
./gradlew.bat test installDist --no-daemon
python docs/audits/remaining-fixes-2026-10-23/acquire.py
python docs/audits/remaining-fixes-2026-10-23/run-corpus.py
python docs/audits/github40-2026-10-23/reproduce.py --expect fixed
```

## Remaining boundaries

The five historical Taotao applications require annotation and launcher definitions
from external dependencies absent from their snapshots. Source-defined composition
support does not infer those missing definitions. AWS SQS, Kotlin, dynamic runtime
values and custom unrecognized integrations retain their documented limitations.
Other unadjudicated input parse errors are not presumed scanner defects. These are
source-scanner checks; downloaded projects were never built, tested or started.

Task-created checkouts and scratch are removed after retaining evidence. The private
history remains local with source pins and scanner hashes; existing benchmark
checkouts and global Windows TEMP/TMP settings are preserved.
