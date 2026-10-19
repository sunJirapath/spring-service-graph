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
#   SSG_OUT               output directory (default ./ssg-impact)
#   SSG_FAIL_ON_BREAKING  "true" to exit 3 when breaking changes are found
#   SSG                   ssg launcher (default: ssg on PATH)
#
# Writes $SSG_OUT/impact.md, $SSG_OUT/breaking ("true"/"false") and the two scans in $SSG_OUT/base|head.
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

mkdir -p "$out"
"$ssg" scan --repos "$SSG_BASE" "${fleet[@]}" "${opts[@]}" --out "$out/base"
"$ssg" scan --repos "$SSG_HEAD" "${fleet[@]}" "${opts[@]}" --out "$out/head"

set +e
"$ssg" diff --base "$out/base" --head "$out/head" --fail-on-breaking > "$out/impact.md"
code=$?
set -e
case "$code" in
  0) echo false > "$out/breaking" ;;
  3) echo true > "$out/breaking" ;;
  *) echo "ssg-impact: ssg diff failed with exit code $code" >&2; exit "$code" ;;
esac

echo "ssg-impact: report in $out/impact.md (breaking: $(cat "$out/breaking"))"
if [ "$code" = 3 ] && [ "${SSG_FAIL_ON_BREAKING:-false}" = true ]; then exit 3; fi
exit 0
