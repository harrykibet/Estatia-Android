#!/usr/bin/env bash
set -euo pipefail

event_name="${1:-}"
base_sha="${2:-}"
head_sha="${3:-}"

docs_only=false
docs_changed=false

# Only classify pull-request diffs and pushes. Merge groups and manually
# dispatched runs deliberately take the full verification path.
case "$event_name" in
  pull_request|push) ;;
  *)
    echo "docs_only=false" >> "$GITHUB_OUTPUT"
    echo "docs_changed=false" >> "$GITHUB_OUTPUT"
    echo "Non-diff event ($event_name): retaining full verification."
    exit 0
    ;;
esac

# Fail safe: missing, zero, or unavailable SHAs must never suppress code gates.
if [[ -z "$base_sha" || -z "$head_sha" || "$base_sha" =~ ^0+$ ]]; then
  echo "docs_only=false" >> "$GITHUB_OUTPUT"
  echo "docs_changed=false" >> "$GITHUB_OUTPUT"
  echo "No usable diff range; retaining full verification."
  exit 0
fi

if ! git cat-file -e "$base_sha^{commit}" 2>/dev/null || ! git cat-file -e "$head_sha^{commit}" 2>/dev/null; then
  echo "docs_only=false" >> "$GITHUB_OUTPUT"
  echo "docs_changed=false" >> "$GITHUB_OUTPUT"
  echo "Diff commits are unavailable; retaining full verification."
  exit 0
fi

mapfile -d '' -t changed_files < <(git diff --name-only --diff-filter=ACMRT -z "$base_sha" "$head_sha")
if [[ ${#changed_files[@]} -eq 0 ]]; then
  echo "docs_only=false" >> "$GITHUB_OUTPUT"
  echo "docs_changed=false" >> "$GITHUB_OUTPUT"
  echo "No changed paths were found; retaining full verification."
  exit 0
fi

docs_only=true
for path in "${changed_files[@]}"; do
  case "$path" in
    *.md|docs/*|LICENSE|LICENSE.*|NOTICE|NOTICE.*|COPYING|.github/ISSUE_TEMPLATE/*|.github/PULL_REQUEST_TEMPLATE.md)
      docs_changed=true
      ;;
    *)
      docs_only=false
      ;;
  esac
done

echo "docs_only=$docs_only" >> "$GITHUB_OUTPUT"
echo "docs_changed=$docs_changed" >> "$GITHUB_OUTPUT"
printf 'Changed paths (%s):\n' "${#changed_files[@]}"
printf '  %s\n' "${changed_files[@]}"
if [[ "$docs_only" == "true" ]]; then
  echo "Documentation-only change: Android build, test, smoke, and release gates will be skipped."
else
  echo "Code or automation change detected: full applicable verification remains enabled."
fi
