# Local public-repository test history

Before selecting or downloading repositories for field tests, read `.local-audits/README.md`
and `.local-audits/registry.json` if present. They record completed runs and pinned inputs.
Do not treat a previously scanned commit as tested against a different scanner build.

The registry is private local state. Never stage, force-add, attach, or publish `.local-audits/`.
Public audit reports and their explicitly selected evidence are separate artifacts.

Keep task temporary files under this workspace's `build/` directory. Do not change global
TEMP/TMP or delete pre-existing benchmark checkouts when cleaning up task-created clones.

<!-- initium:start -->
## Initium harness

Start with `python3 tools/harness.py context --query "<task>" --scope tiny|bounded|system` and load only the MCP servers its Budget footer lists. Finish by updating affected `brain/` notes, then `harness.py complete` (write `not run` for anything unrun) and `harness.py check`. Run `harness.py preflight` before any push. On Windows use `python`.
<!-- initium:end -->
