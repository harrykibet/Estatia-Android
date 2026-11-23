#!/usr/bin/env bash
# Fail (and delete the offending files) if a secret value appears in files that are
# about to be uploaded as workflow artifacts.
#
# Actions masks secrets in the log UI only. Files written by `tee`, `adb logcat`,
# Gradle reports or instrumentation output contain the raw bytes, and uploaded
# artifacts stay downloadable for their whole retention period.
#
# Usage: scrub-check-secrets.sh --dirs <dir>... -- <ENV_VAR_NAME>...
# Secret values are read from the named environment variables and are never printed
# or placed on any command line.
set -uo pipefail

dirs=()
names=()
mode=""
for argument in "$@"; do
  case "$argument" in
    --dirs) mode="dirs" ;;
    --) mode="names" ;;
    *)
      case "$mode" in
        dirs) dirs+=("$argument") ;;
        names) names+=("$argument") ;;
        *) echo "::error::Usage: $0 --dirs <dir>... -- <ENV_VAR_NAME>..." >&2; exit 64 ;;
      esac
      ;;
  esac
done

if (( ${#dirs[@]} == 0 || ${#names[@]} == 0 )); then
  echo "::error::Usage: $0 --dirs <dir>... -- <ENV_VAR_NAME>..." >&2
  exit 64
fi

existing=()
for dir in "${dirs[@]}"; do
  [[ -e "$dir" ]] && existing+=("$dir")
done
if (( ${#existing[@]} == 0 )); then
  echo "No artifact directories to scrub."
  exit 0
fi

leaks=0
checked=0
for name in "${names[@]}"; do
  value="${!name:-}"
  if (( ${#value} < 4 )); then
    continue
  fi
  checked=$(( checked + 1 ))
  # Raw, base64 and percent-encoded forms are what realistically appear in logs.
  patterns="$(mktemp)"
  {
    printf '%s\n' "$value"
    printf '%s' "$value" | base64 -w0
    printf '\n'
    python3 -c 'import os, sys, urllib.parse; print(urllib.parse.quote(os.environ[sys.argv[1]], safe=""))' "$name"
  } > "$patterns"
  while IFS= read -r file; do
    [[ -n "$file" ]] || continue
    echo "::error::Secret from \$$name was found in artifact file ${file#./}; the file was removed before upload."
    rm -f -- "$file"
    leaks=$(( leaks + 1 ))
  done < <(grep -rlaF -f "$patterns" -- "${existing[@]}" 2>/dev/null)
  rm -f "$patterns"
done

if (( leaks > 0 )); then
  echo "::error::$leaks artifact file(s) contained credential material. Rotate the affected secret(s)."
  exit 1
fi
echo "Artifact scrub passed: $checked secret(s) checked across ${#existing[@]} location(s)."
