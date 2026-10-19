# Merge-request impact in CI

`action.yml` in this repo is a GitHub composite action. In a pull request on one service repo, it does three things:

1. It scans the fleet twice: once with the PR's base checkout of that repo, once with its head checkout. The other fleet repos are the same in both scans.
2. It writes `ssg diff` into the job summary and into one PR comment, which is updated on every push.
3. It can fail the job when the report lists **breaking changes**:
   - a removed endpoint or service that something still calls
   - a topic that lost its last producer but is still consumed
   - new contract drift

The action builds ssg from source at the commit you pin, so it needs no release.

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

      # the rest of the fleet (private repos need a token that can read them)
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
| `fleet` | empty | Other repo checkouts, separated by spaces or newlines. Without them you only see impact inside the changed repo. |
| `profile` | `default` | Spring profile. |
| `aliases` | empty | `aliases.yml` for hosts that are not service names. |
| `config-repo` | empty | Spring Cloud Config repo checkout. |
| `fail-on-breaking` | `false` | Fail the job (exit 3) when there are breaking changes. The comment is posted first. |
| `comment` | `true` | Post or update the PR comment. The report is always written to the job summary. |
| `github-token` | `github.token` | Token for the comment. |

### Outputs

- `breaking`: `"true"` or `"false"`.
- `report`: the path of the Markdown report.

### Notes

- By default `actions/checkout` checks out the PR's merge commit as the head, which is what will land once the PR merges.
- **Fork PRs:** fork pull requests get a read-only token, so the comment step only logs a warning. The job summary and the gate still work.
- **Long reports:** comments longer than GitHub's 65536-character limit are truncated. The job summary keeps the full report.
- **Testing:** this repo's CI runs the action against its own test fleet (job `impact-action`).

## Other CI systems

`scripts/ssg-impact.sh` has the same logic without any GitHub dependency.

1. Build ssg with `./gradlew installDist`.
2. Check out base and head into folders with the same name.
3. Run the script:

```bash
SSG=build/install/ssg/bin/ssg SSG_BASE=base/orders SSG_HEAD=head/orders SSG_FLEET="fleet/gateway fleet/billing" SSG_FAIL_ON_BREAKING=true bash scripts/ssg-impact.sh
```

It writes `ssg-impact/impact.md` and `ssg-impact/breaking`, and exits 3 on breaking changes when the gate is on. Posting the report as a merge-request note, for example with GitLab's API, is up to the pipeline.
