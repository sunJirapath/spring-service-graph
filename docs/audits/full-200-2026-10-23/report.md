# Full 200-repository validation of the final scanner

The final scanner was checked against **all 200 pinned repositories**:
**195 validation passes, five known unsupported launcher inputs, zero unexpected
failures**. All **16 confirmed scanner defects** have fixes and regression coverage.
The library passes **768 tests** and all **16 CLI regression cases**.

This is a field test of `spring-service-graph` against public source snapshots. It does
not claim to audit or execute the applications in those repositories.

| Final-build check | Result |
|---|---:|
| Source scan processes | 400 successful exits across 200 repositories |
| Byte-identical repeated-output checks | 200/200 |
| Identical-input breaking diffs | 200/200 |
| MCP checks on valid nonempty graphs | 195/195 |
| Source evidence locations checked | 231,655 |
| Source spot checks across ten repositories | 11 verified, one documented unsupported SQS operation, zero misses |

The two complete campaigns and the separately retained 25-repository intermediate
run contain **850 completed source scan processes**, covering **200 unique repositories**.
Only the 400 scans in the final-build column establish current-build field coverage.

## Inputs and counting

The [manifest](manifest.json), [inventory](repositories.md) and [CSV](repositories.csv)
freeze the same **200 distinct repository/commit pairs** already used in earlier
campaigns. No moving branch or newly substituted repository is counted. The snapshots
contain **105,648 tracked Java paths beneath `src/main/java` markers**; resource
templates matching that layout remain excluded by the scanner.

| Measured source tier | Java paths per repository | Repositories |
|---|---:|---:|
| Small | 1–49 | 62 |
| Medium | 50–249 | 69 |
| Large | 250–999 | 38 |
| Huge | 1,000+ | 31 |

**170 repositories have pinned Spring Boot 4+ evidence: 85 Maven and 85 Gradle.**
They include the original balanced 80-repository campaign and 90 additional inputs.
The remaining 30 are historical baseline inputs whose Boot versions were not qualified;
their versions remain unspecified. Across all 200, the selected grouping descriptors
are 111 Maven and 89 Gradle; four historical repositories contain both descriptor types.
The corpus is not represented as 200 Boot 4 projects or a 100/100 split.

## Confirmed fixes

The audit now has **16 confirmed scanner defects with fixes and regression coverage**:
the [original eight](../boot4-80-2026-10-23/fixes.md),
[two extraction fixes](../extension-200-2026-10-23/report.md),
[three diagnostic/discovery fixes](../remaining-fixes-2026-10-23/report.md),
and the following three corrections found during this final review.

| Defect | Root cause | Fix | Verification |
|---|---|---|---|
| Composed Feign package settings disappear | Each composed override removed both `value` and `basePackages`, including when an omitted member contributed its empty default. This could erase a different explicit or meta-annotation package. | Replace only the actual target attribute. Feign reads the two independent arrays and unions their packages. | Paired value-only and packages-only controls, independent meta-package regression, and before/after CLI graphs. |
| Non-runtime composed configuration activates clients | Source meta-annotation traversal accepted missing retention, `CLASS` and `SOURCE`, although Spring's runtime and class-file metadata do not expose those annotations. | Reuse the runtime-retention guard already used by composed application discovery when traversing source configuration annotations. | Three negative retention cases, a runtime positive control, and an existing composed-import control corrected to declare runtime retention. |
| Lexical-error evidence points to line 1 | JavaParser's lexical exception has no token range, so the diagnostic mapper discards the line contained in the exception's message. | Prefer a token range; otherwise extract the anchored line field only for `TokenMgrException`, preserving the existing fallback for unavailable positions. | A failed-before/passed-after regression retains both lexical and ordinary parse errors; a CLI fixture moves only its evidence line from 1 to 5. |

The package-setting regression fails on unchanged production code. The retention
regression also fails before its guard; the [failure record](evidence/retention-before.json)
retains the unexpected Feign call. The [lexical regression](evidence/lexical-before.json)
also fails before its correction. The full suite then passes **768 tests, zero failures,
errors or skips**. Production changes in this follow-up are confined to `Discovery.java`
and `JavaIndex.java`; no dependency was added. Existing positive fixtures now accurately
declare runtime retention.

The [Feign registrar source](https://github.com/spring-cloud/spring-cloud-openfeign/blob/v5.0.0/spring-cloud-openfeign-core/src/main/java/org/springframework/cloud/openfeign/FeignClientsRegistrar.java)
independently establishes that both package arrays contribute to scanning. The
[metadata probe](FeignMetadataProbe.java) uses actual Spring Core 7.0.9 to check three
composed override shapes against a minimal annotation with the same independent
attributes. The [retention probe](RetentionMetadataProbe.java) checks reflection,
ASM meta-annotation lookup and ASM merged annotations for four retention choices.
All assertions pass; [package output](evidence/spring-metadata-probe.txt) and
[retention output](evidence/retention-spring-metadata-probe.txt) are retained. These
probes do not run an OpenFeign application or replace the source-corpus checks.

The [seven new CLI cases](evidence/feign-after.json) use the installed final distribution.
Their [before results](evidence/feign-before.json) reproduce two missing calls and
three false activations, with two passing controls. All seven have the correct final
outcome. The [original eight CLI cases](evidence/prior-cli-reproductions.json) still pass.
The [lexical CLI check](evidence/lexical-cli-after.json) retains the real parse error at
line 5, while the [before check](evidence/lexical-cli-before.json) captures the line-1
evidence mismatch. Both graph snapshots are retained as `lexical-*-graph.json`.
Selected graph bytes are retained in the corresponding `*-graphs.zip` archives.

## What the field checks establish

Each repository is checked out at its full source SHA, checked for local modifications,
and qualified against the frozen Java count, tier and available Boot-version evidence.
Only source, resources, build descriptors, SQL and selected API specifications are
acquired. The harness does not invoke any downloaded build script.

Each scanner build runs twice per repository. Checks compare hashes of every generated
file, validate node IDs and edge references, and resolve every graph evidence location
to a regular source file and an in-bounds line. An identical-input breaking diff must
return `No changes.`. For nonempty valid graphs, MCP initialization, tool listing,
`who_calls`, depth-one `impact_of` and `service_card` are checked against the graph.
MCP consistency verifies the query layer; it is not independent proof of extraction.

Every changed graph is compared with the most recent retained graph at the **same
source commit**. Before-phase source review covers all **15 changed graphs**. Changes
include checkout-label normalization, the documented nested WebFlux/store/Feign fixes,
one removed false record diagnostic, and conservative unresolved generic-type messages.
The other 185 graphs match their retained references as JSON. Graph archive hashes and
full per-repository deltas retain the comparison inputs.

The checks detect crashes, nondeterminism, broken evidence and graph regressions.
Focused source review and positive/negative fixtures provide semantic checks. They do
not establish whole-corpus precision/recall or prove that every possible application
dependency is represented.

The [complete phase comparison](evidence/phase-comparison.json) shows **199 final
graphs byte-identical to the prior complete campaign**. The only changed graph corrects
the lexical evidence line from 1 to 155; all its nodes, edges, finding messages and finding
counts are unchanged. All **16 graphs that differ from the older retained references**
have [source explanations](evidence/changed-graph-source-review.json). Fifteen reviews
carry by exact graph-byte identity; the lexical correction is checked directly against
the [pinned public source](evidence/lexical-public-verification.json).

The [12 source spot checks](evidence/source-sample-checks.json) independently recheck
HTTP routes, table ownership, data reach and messaging behavior at exact source pins.
Both scan outputs agree for all ten sampled repositories. The unsupported `SqsTemplate.send`
operation remains explicit. The two Feign corrections are verified with focused fixtures
and actual Spring metadata probes; no changed public-corpus graph demonstrates those
specific cases.

Final graphs contain 638 service nodes, 23,899 endpoints and 106,411 edges. They retain
3,955 findings: 3,448 unresolved values/types, 415 shared-table findings, 26 missing
interfaces, 48 contract drifts, five duplicate-service findings and 13 real parse errors.
A validation pass does not mean an input has no findings. Those input findings are not
automatically classified as scanner defects, and all 3,955 were not individually verified.

## Known unsupported inputs

`shuigedeng/taotao-cloud-sys`, `taotao-cloud-goods`, `taotao-cloud-order`,
`taotao-cloud-member` and `taotao-cloud-message` use external custom launcher annotations
or startup classes whose definitions are absent from these source snapshots. All five
produce deterministic empty graphs. Their scan processes and identical diffs succeed,
but extraction remains failed and MCP is not run. They are recorded as
`expected_unsupported`, never as passing extraction.

The 13 remaining `PARSE_ERROR` findings in `taotao-cloud-project` were inspected at
the pinned source: all are malformed Java (split identifiers, `boolean.TRUE`, incomplete
imports, an invalid enum body or an illegal string escape). They remain visible. The
lexical error in `MediaHandler.java` is at line 155; the final-build source check verifies
that its evidence now points there. The [source classification](evidence/remaining-parse-errors-review.json)
retains the individual paths and minimal excerpts.

Other unresolved source values, dynamic configuration and unsupported integrations
remain findings or documented gaps. Kotlin-only behavior, generated/dependency bytecode,
live traffic, databases, brokers and application startup are not tested. The default
profile is used without an external config repository or aliases.

## Build identity and retained evidence

[Scanner build identities](scanner-builds.json) separate the before and final JARs,
dependency-inclusive distributions and source inventories. The before phase uses
`df7de2f441c92cd640ce1e4777cf725416673331`. The final source is
`84831f02916e3090a031b6e0433927e05f72f67a`, including the three corrections above.
Report and harness changes after that commit do not alter the scanner build inputs.

An intermediate build at `cc66f80483b05052f8df44cfa399d6132ef37ef6` completed 25
repositories before source review identified the lexical evidence mismatch. Its campaign
was stopped for that fix. The [intermediate metadata](evidence/intermediate-metadata.json),
[25 results](evidence/intermediate-results.json) and [graphs](evidence/intermediate-graphs.zip)
are retained separately; the partial run does not count as final-build coverage.

- [Before metadata](evidence/before-metadata.json), [results](evidence/before-results.json), [graphs](evidence/before-graphs.zip), [output hashes](evidence/before-output-sha256.json) and [deltas](evidence/before-deltas.zip)
- [Final metadata](evidence/after-metadata.json), [results](evidence/after-results.json), [graphs](evidence/after-graphs.zip), [output hashes](evidence/after-output-sha256.json) and [deltas](evidence/after-deltas.zip)
- [Phase comparison](evidence/phase-comparison.json), [changed-graph source review](evidence/changed-graph-source-review.json) and [source spot checks](evidence/source-sample-checks.json)
- [Unit-test totals](evidence/unit-tests.json), [scanner source hashes](evidence/scanner-source-sha256.json), [distribution JAR hashes](evidence/scanner-jars-sha256.json)
- [Input review](evidence/input-review.json), [harness self-checks](evidence/harness-selfcheck.json) and [discovery review](evidence/discovery-code-review.json)
- [Toolchain](evidence/toolchain.json), [publication review](evidence/publication-review.json) and [cleanup record](evidence/cleanup.json)

Public summaries exclude process-output tails and normalize workspace paths. Graph
archive entries retain their original bytes as `owner--repository/graph.json`. Historical
`build/full200/...` paths identify scratch locations and resolve through the phase archive
after cleanup. The private local run-history registry is excluded from publication.

All 200 task-created source checkouts and the audit scratch directories were removed
after archiving. The 15 pre-existing `bench/` checkouts, private registry and installed
final scanner are preserved. Raw process logs are excluded from the retained artifacts.

## Reproduction

From the repository root with Java 21, Python 3 and Git:

```powershell
New-Item -ItemType Directory -Force build/full200/tmp | Out-Null
$env:TEMP = "$PWD/build/full200/tmp"
$env:TMP = $env:TEMP
$env:TMPDIR = $env:TEMP
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$env:TEMP"
./gradlew.bat test installDist --no-daemon
python -B docs/audits/full-200-2026-10-23/verify-harness.py
python -B docs/audits/full-200-2026-10-23/reproduce-feign.py --expect fixed
python -B docs/audits/full-200-2026-10-23/reproduce-lexical.py --expect fixed
python -B docs/audits/github40-2026-10-23/reproduce.py --expect fixed
python -B docs/audits/full-200-2026-10-23/campaign.py --phase after
python -B docs/audits/full-200-2026-10-23/package-results.py --run 313f4172f17aaca4 --label after
python -B docs/audits/full-200-2026-10-23/compare-phases.py
```

The campaign refuses a different installed scanner or changed build-input source hash.
Regenerated JARs must match the recorded identities to reuse this campaign's results.
All audit checkouts, fixtures and scanner temporary files are under workspace `build/`
on E:. Global Windows TEMP/TMP settings and pre-existing `bench/` checkouts are preserved.
