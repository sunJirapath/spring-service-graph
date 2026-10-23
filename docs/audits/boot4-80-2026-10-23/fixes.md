# Audit fixes

The original eight findings and their baseline reproductions are retained in the
[40-repository audit](../github40-2026-10-23/report.md). This change fixes all eight;
the original evidence remains a record of the old scanner's behavior.

| Finding | Root cause | Change | Regression coverage |
|---|---|---|---|
| Generated application HTTP APIs disappear | Framework exclusion covered every `org.springframework.*` interface | Restrict the exclusion to framework packages so application interfaces reach OpenAPI fallback | PetClinic-style missing interface, import control, retained CLI fixture |
| Elasticsearch index has the wrong name | Mongo annotation attributes were used for both stores | Read Elasticsearch `indexName`; preserve Mongo collection overrides | Explicit index name and store assertions |
| Unannotated Mongo domain is lost | Repository mapping required an annotated entity | Infer Mongo's conventional collection; propagate concrete entity parameters through source repository bases | Direct Mongo repository and reordered custom generic base |
| Coordinated API retirement trips CI | Diff checked surviving caller services without checking surviving calls | Require a matching head call to the removed provider/method/path; accept replacement handlers | Removed call, still-present call, changed method/path/target, renamed template, CLI exit code |
| Rabbit payload becomes a topic | Argument count conflated metadata and exchange overloads | Recognize identifiable message post-processor/correlation arguments; leave ambiguous/default destinations unresolved | Post-processor, correlation, default destination and ordinary exchange overloads |
| Root URL query becomes host text | Authority parsing stopped only at a slash | End authority at slash/query/fragment; retain root path, unknown values and IPv6 literals | Root query/fragment, IPv6, unresolved placeholders and local endpoint link |
| Enabled Feign clients in dependency modules disappear | Component roots omitted Feign-specific registration | Load eligible declared clients using Feign package/class roots separately from component roots | Maven/Gradle layouts, explicit clients, marker classes, composed aliases and unscanned/imported configuration |
| Duplicate-name evidence points to directories | Finding evidence used the module path | Use the name's config entry or owning launcher declaration, retaining both sources | Explicit and inferred names, actual regular files and positive lines |

The Feign review also caught alias-group and activation mistakes before the final
scanner build: `EnableFeignClients.value` and `basePackages` replace each other,
annotation declarations do not register beans themselves, and an unscanned
configuration does not activate a package scan.

Three related edge cases were fixed while validating these changes:

- OpenAPI server paths that already include the servlet context no longer repeat it.
- Config search paths stay within the selected config root, including normalized
  traversal, glob candidates and existing filesystem links. This preserves the
  scanner's root boundary; no exploit or credential access was established.
- Different checkouts with the same basename now fail with an explicit error.
  Evidence uses the checkout basename as its repository key, so previously one
  root silently overwrote the other. Use distinct checkout directory names.

Tests live in [AuditFixesTest.java](../../../src/test/java/io/github/sunjirapath/ssg/AuditFixesTest.java),
[LinkTest.java](../../../src/test/java/io/github/sunjirapath/ssg/link/LinkTest.java),
and the existing diff/CLI tests. Existing positive breaking-change tests now retain
an actual head call; the prior expectations encoded the false positive.

To verify the eight retained CLI cases against the installed fixed scanner:

```powershell
.\gradlew.bat --offline test installDist --no-daemon
python docs\audits\github40-2026-10-23\reproduce.py --expect fixed
```

`--expect baseline` is reserved for the old scanner distribution. Original wrong
results are archived separately and are not rewritten as passing fixed results.
