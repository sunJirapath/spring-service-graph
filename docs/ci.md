# Merge-request impact in CI

`action.yml` in this repo is a GitHub composite action. In a pull request on one service repo, it does three things:

1. It scans the fleet twice: once with the PR's base checkout of that repo, once with its head checkout. The other fleet repos are the same in both scans.
2. It writes `ssg diff` into the job summary and into one PR comment, which is updated on every push.
3. It can fail the job when the report lists **breaking changes**:
   - a removed endpoint or service that something still calls
   - a topic that lost its last producer but is still consumed
   - new contract drift

The gate also fails on **incomplete analysis** (exit 4), taking precedence over proven breaking changes (exit 3). Unresolved findings/edges, parse or config failures, missing generated contracts, duplicate services, unsupported main Kotlin sources, inputs with no discovered app, and confidence loss prevent a green gate. Existing uncertainty in either input also blocks it. Missing generated contracts produce unverified calls rather than proven drift. Without the gate, these reports remain advisory.

Use fresh base/head scans from the same scanner and fleet/profile/config inputs. Legacy snapshots may lack completeness diagnostics; snapshot freshness is not checked. The gate covers observed static evidence within the [known limits](../README.md#known-limits), not all runtime behavior.

The action builds ssg from source at the commit you pin, so it needs no release.
It configures the Gradle user-home cache with `gradle/actions/setup-gradle`; it still builds on every invocation. A cold runner needs network access for Temurin, the Gradle distribution and Maven Central dependencies. Prepare runner proxy settings and permitted downloads/caches before running the action; cache hits do not guarantee offline operation. No cold-run elapsed-time guarantee is established.

## GitHub Actions

Add `.github/workflows/ssg-impact.yml` to each service repo:

```yaml
name: Service graph impact
on: pull_request

permissions:
  contents: read
  pull-requests: write # to post the comment

jobs:
  impact:
    runs-on: ubuntu-latest
    steps:
      # base and head must end in the same folder name: ssg names repos after their folder
      - uses: actions/checkout@v7
        with:
          ref: ${{ github.event.pull_request.base.sha }}
          path: ssg/base/${{ github.event.repository.name }}
      - uses: actions/checkout@v7
        with:
          path: ssg/head/${{ github.event.repository.name }}

      # the rest of the fleet (private repos need a token that can read them;
      # FLEET_READ_TOKEN is unavailable to normal fork PR workflows)
      - uses: actions/checkout@v7
        with:
          repository: my-org/gateway
          path: ssg/fleet/gateway
          token: ${{ secrets.FLEET_READ_TOKEN }}
      - uses: actions/checkout@v7
        with:
          repository: my-org/notification
          path: ssg/fleet/notification
          token: ${{ secrets.FLEET_READ_TOKEN }}

      - uses: sunJirapath/spring-service-graph@<commit-sha> # pin a commit; there are no release tags yet
        with:
          base-path: ssg/base/${{ github.event.repository.name }}
          head-path: ssg/head/${{ github.event.repository.name }}
          fleet: |
            ssg/fleet/gateway
            ssg/fleet/notification
          fail-on-breaking: 'true'
          # optional: profile, aliases, config-repo (paths relative to the workspace)
```

### Inputs

| Input | Default | Meaning |
|---|---|---|
| `base-path` | required | Base checkout of the changed repo. |
| `head-path` | required | Head checkout. It must have the same folder name as `base-path`. |
| `fleet` | empty | Other repo checkouts, one literal path per line; spaces are preserved and empty lines ignored. Without them you only see impact inside the changed repo. |
| `profile` | `default` | Spring profile. |
| `aliases` | empty | `aliases.yml` for hosts that are not service names. |
| `config-repo` | empty | Spring Cloud Config repo checkout. |
| `fail-on-breaking` | `false` | Fail on incomplete analysis (exit 4), otherwise breaking changes (exit 3). The comment is posted first. |
| `comment` | `true` | Post or update the PR comment. The report is always written to the job summary. |
| `github-token` | `github.token` | Token for the comment. |

### Outputs

- `breaking`: `"true"` or `"false"`.
- `incomplete`: `"true"` or `"false"`; when true the gate exits 4, even if `breaking` is also true.
- `report`: the path of the Markdown report.

### Notes

- By default `actions/checkout` checks out the PR's merge commit as the head, which is what will land once the PR merges.
- **Fork PRs:** normal fork `pull_request` workflows do not receive `FLEET_READ_TOKEN`; the example's private-fleet checkouts fail before scanning, so no impact summary or gate result is produced. Public fleet repositories can be checked out with `github.token` instead. The summary and gate require successful checkouts and action steps; use `comment: 'false'` with a read-only token to avoid the comment step. A reduced fleet does not establish impact on omitted private services. Do not switch to `pull_request_target` to expose secrets to untrusted PR code.
- **Long reports:** comments longer than GitHub's 65536-character limit are truncated. The job summary keeps the full report.
- **Comment failures:** missing `gh`, failed comment lookup and failed writes produce warnings. The final gate still runs after a failed comment step when the scan produced an incomplete/breaking result.
- **Testing:** this repo's CI runs the action against its own test fleet (job `impact-action`).

## Other CI systems

`scripts/ssg-impact.sh` has the same logic without any GitHub dependency.

1. Build ssg with `./gradlew installDist`.
2. Check out base and head into folders with the same name.
3. Run the script:

```bash
SSG=build/install/ssg/bin/ssg SSG_BASE=base/orders SSG_HEAD=head/orders SSG_FLEET=$'fleet/gateway\nfleet/Order Service' SSG_FAIL_ON_BREAKING=true bash scripts/ssg-impact.sh
```

It writes `ssg-impact/impact.md`, `ssg-impact/breaking` and `ssg-impact/incomplete` (the latter two contain `true` or `false`). With the gate on, it exits 4 for incomplete analysis, otherwise 3 for breaking changes. With the gate off, either outcome writes the report and exits 0; scanner/infrastructure errors still fail the script. Posting the report as a merge-request note, for example with GitLab's API, is up to the pipeline.

`SSG_FLEET` and the action's `fleet` input now use one path per line, including a single path with spaces. Migrate old space-separated lists to newlines. Each line is passed as one argument without shell quote parsing or variable expansion; ssg still expands trailing repository globs as documented in the CLI. CRLF line endings are accepted. Scanner errors use exit 1 for I/O and 2 for invalid input; preserve all other failures too.
