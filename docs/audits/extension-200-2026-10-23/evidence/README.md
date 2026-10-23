# Evidence index

`before-*` files describe the 90 added repositories using the scanner with the first
eight fixes. `intermediate-*` files retain the first implementation of the two new
fixes. `after-*` files describe the same pins after final method-constraint hardening.
Each phase contains two successful scan processes per repository.

| Artifact | Contents |
|---|---|
| `campaign-summary.json` | Scope, build hashes, phase counts and latest-build coverage |
| `*-metadata.json` | Actual timestamps, scanner identities and aggregate results for each phase |
| `*-scan-results.json` | Per-repository checks, timings, exit codes, integrity, diff and MCP results |
| `*-output-sha256.json` | Independently rechecked inventories for both output trees |
| `*-scan-graphs.zip` | First-run graphs, under `owner--repository/graph.json`; second-run bytes match |
| `graph-changes.json` | Only two graph changes: POSSUM extraction and nine WebFlux path prefixes |
| `baseline-regressions.json`, `final-unit-tests.json` | Three expected pre-fix failures; final 756-test suite totals |
| `intermediate-final-unit-tests.json`, `intermediate-scanner-source-sha256.json` | Retained 755-test intermediate build and its source hashes |
| `fixed-reproductions.json`, `fixed-reproduction-outputs.zip` | All eight earlier fixes rechecked on the final scanner |
| `*-source-checks.json` | Source facts sampled before the new fixes, including the observed route miss |
| `*-postfix-checks.json` | Corresponding final-build source checks and the POSSUM regression |
| `*-intermediate-checks.json` | Independent source checks before final method hardening; final graph bytes are identical for all 90 pins |
| `harness-selfcheck.json` | Input and checkout-boundary checks for both campaign wrappers |
| `scanner-source-sha256.json` | Production/test source and build-file hashes at final build |
| `rejected-candidates.json` | One acquired candidate rejected before any scan was counted |
| `publication-review.json`, `cleanup.json` | Publication review and verified task scratch cleanup |

Historical `build/audit200/...` references identify the original output locations.
After cleanup, resolve graph references using the corresponding phase ZIP and
`owner--repository/graph.json` entry. Source paths are repository-relative; resolve
them against the full source SHA in the manifest or catalog. Raw process logs and
checkouts are not published. Result summaries omit raw stdout/stderr tails; graph
bytes remain unchanged so their hashes can be verified against the inventories.

Graph integrity establishes internal consistency and valid source line locations.
Independent source checks establish the listed sampled facts. Neither proves complete
extraction coverage. Unsupported SQS behavior and unresolved findings remain visible.
