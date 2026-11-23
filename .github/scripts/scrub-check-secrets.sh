#!/usr/bin/env bash
# Fail (and delete offending files) if a secret value appears in files that are
# about to be uploaded as workflow artifacts. Secret values are read from named
# environment variables and are never printed or passed as command-line arguments.
#
# Usage: scrub-check-secrets.sh --dirs <dir>... -- <ENV_VAR_NAME>...
set -euo pipefail

dirs=()
names=()
mode=""
for argument in "$@"; do
  case "$argument" in
    --dirs)
      [[ "$mode" == "" ]] || { echo "::error::Duplicate or misplaced --dirs argument." >&2; exit 64; }
      mode="dirs"
      ;;
    --)
      [[ "$mode" == "dirs" ]] || { echo "::error::Expected --dirs before --." >&2; exit 64; }
      mode="names"
      ;;
    *)
      case "$mode" in
        dirs) dirs+=("$argument") ;;
        names) names+=("$argument") ;;
        *) echo "::error::Usage: $0 --dirs <dir>... -- <ENV_VAR_NAME>..." >&2; exit 64 ;;
      esac
      ;;
  esac
done

if [[ "$mode" != "names" ]] || (( ${#dirs[@]} == 0 || ${#names[@]} == 0 )); then
  echo "::error::Usage: $0 --dirs <dir>... -- <ENV_VAR_NAME>..." >&2
  exit 64
fi

existing=()
for dir in "${dirs[@]}"; do
  if [[ -e "$dir" && ! -d "$dir" ]]; then
    echo "::error::Artifact scan path is not a directory: $dir" >&2
    exit 65
  fi
  if [[ -d "$dir" ]]; then
    existing+=("$dir")
  fi
done
if (( ${#existing[@]} == 0 )); then
  echo "No artifact directories exist; there are no files to upload from the requested paths."
  exit 0
fi

temporary_files=()
cleanup() {
  local file
  for file in "${temporary_files[@]}"; do
    rm -f -- "$file" || true
  done
}
trap cleanup EXIT

leaks=0
checked=0
for name in "${names[@]}"; do
  if [[ ! "$name" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
    echo "::error::Invalid environment variable name supplied to scrubber." >&2
    exit 64
  fi
  value="${!name:-}"
  [[ -n "$value" ]] || continue
  if (( ${#value} < 4 )); then
    echo "::error::Configured secret variable $name is too short to scan safely." >&2
    exit 65
  fi
  if [[ "$value" == *$'\n'* || "$value" == *$'\r'* ]]; then
    echo "::error::Configured secret variable $name contains a line break and cannot be scanned safely." >&2
    exit 65
  fi

  checked=$(( checked + 1 ))
  patterns="$(mktemp)"
  matches="$(mktemp)"
  temporary_files+=("$patterns" "$matches")

  # Keep all encodings out of argv and Actions logs. The Python process receives
  # the secret through its environment and writes patterns to a mode-600 temp file.
  python3 - "$name" "$patterns" <<'PY'
import base64
import os
import sys
from urllib.parse import quote

value = os.environ[sys.argv[1]]
patterns = {value, base64.b64encode(value.encode("utf-8")).decode("ascii"), quote(value, safe="")}
with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as output:
    for pattern in sorted(patterns):
        if pattern:
            output.write(pattern + "\n")
PY

  # grep exit 1 means no match; exit 2 or higher means the scan itself failed.
  if grep -rlaZF -f "$patterns" -- "${existing[@]}" > "$matches"; then
    scan_status=0
  else
    scan_status=$?
  fi
  if (( scan_status > 1 )); then
    echo "::error::Could not complete credential scan for variable $name; artifact upload must be blocked." >&2
    exit "$scan_status"
  fi

  if (( scan_status == 0 )); then
    while IFS= read -r -d '' file; do
      echo "::error::Credential material from variable $name was found in ${file}; the file was removed before upload."
      rm -f -- "$file"
      leaks=$(( leaks + 1 ))
    done < "$matches"
  fi
done

if (( leaks > 0 )); then
  echo "::error::$leaks artifact file(s) contained credential material. Rotate the affected secret(s)."
  exit 1
fi
echo "Artifact scrub passed: $checked configured secret(s) checked across ${#existing[@]} artifact location(s)."