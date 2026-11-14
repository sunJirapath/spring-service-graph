#!/usr/bin/env bash
# Merge-request impact report for one changed repo: scans the fleet once with the repo's base checkout
# and once with its head checkout, then diffs the two graphs. CI-agnostic; action.yml wraps it for GitHub.
#
# Environment:
#   SSG_BASE              base checkout of the changed repo (required)
#   SSG_HEAD              head checkout of the changed repo (required, same folder name as SSG_BASE)
#   SSG_FLEET             other repo checkouts, space separated (optional; gives cross-service impact)
#   SSG_PROFILE           Spring profile (optional)
#   SSG_ALIASES           aliases.yml (optional)
#   SSG_CONFIG_REPO       Spring Cloud Config repo checkout (optional)
#   SSG_SQL_METHODS       in-house wrapper methods that take SQL, e.g. "SqlRunner.query Db.exec" (optional)
#   SSG_OUT               output directory (default ./ssg-impact)
#   SSG_FAIL_ON_BREAKING  "true" to exit 4 if incomplete, else 3 if breaking
#   SSG                   ssg launcher (default: ssg on PATH)
#
# Writes impact.md, breaking and incomplete ("true"/"false"), and base|head scans under $SSG_OUT.
set -euo pipefail

ssg="${SSG:-ssg}"
out="${SSG_OUT:-ssg-impact}"
: "${SSG_BASE:?SSG_BASE is required}" "${SSG_HEAD:?SSG_HEAD is required}"

# ssg names a repo after its folder; different names would make every service look renamed
if [ "$(basename "$SSG_BASE")" != "$(basename "$SSG_HEAD")" ]; then
  echo "ssg-impact: SSG_BASE and SSG_HEAD must have the same folder name ($SSG_BASE vs $SSG_HEAD)" >&2
  exit 2
fi

read -r -d '' -a fleet <<< "${SSG_FLEET:-}" || true # splits on spaces and newlines
opts=()
[ -n "${SSG_PROFILE:-}" ] && opts+=(--profile "$SSG_PROFILE")
[ -n "${SSG_ALIASES:-}" ] && opts+=(--aliases "$SSG_ALIASES")
[ -n "${SSG_CONFIG_REPO:-}" ] && opts+=(--config-repo "$SSG_CONFIG_REPO")
if [ -n "${SSG_SQL_METHODS:-}" ]; then read -r -d '' -a sql <<< "$SSG_SQL_METHODS" || true; opts+=(--sql-methods "${sql[@]}"); fi

mkdir -p "$out"
"$ssg" scan --repos "$SSG_BASE" "${fleet[@]}" "${opts[@]}" --out "$out/base"
"$ssg" scan --repos "$SSG_HEAD" "${fleet[@]}" "${opts[@]}" --out "$out/head"

set +e
"$ssg" diff --base "$out/base" --head "$out/head" --fail-on-breaking > "$out/impact.md"
code=$?
set -e
case "$code" in
  0|3|4) ;;
  *) echo "ssg-impact: ssg diff failed with exit code $code" >&2; exit "$code" ;;
esac
# Exit 4 takes precedence, so a report can be incomplete and also contain proven breaks.
if awk '/^## Breaking changes$/ { section=1; next } /^## / { section=0 }
        section && /^- / { found=1 } END { exit !found }' "$out/impact.md"; then
  echo true > "$out/breaking"
else
  echo false > "$out/breaking"
fi
if [ "$code" = 4 ]; then echo true > "$out/incomplete"; else echo false > "$out/incomplete"; fi

echo "ssg-impact: report in $out/impact.md (breaking: $(cat "$out/breaking"), incomplete: $(cat "$out/incomplete"))"
if [ "$code" != 0 ] && [ "${SSG_FAIL_ON_BREAKING:-false}" = true ]; then exit "$code"; fi
exit 0
