#!/usr/bin/env bash
set -euo pipefail

# Materialize flavor-specific Firebase configuration without exposing the JSON
# in logs. Arguments are one or both of: demo prod.

if [[ "$#" -lt 1 ]]; then
  echo "::error::Specify at least one flavor: demo and/or prod."
  exit 2
fi

prepare_flavor() {
  local flavor="$1"
  local secret_name encoded destination expected_package temp_file

  case "$flavor" in
    demo)
      secret_name="GOOGLE_SERVICES_JSON_DEMO_BASE64"
      encoded="${GOOGLE_SERVICES_JSON_DEMO_BASE64:-}"
      destination="app/src/Demo/google-services.json"
      expected_package="com.estatia.realestate.apps.demo"
      ;;
    prod)
      secret_name="GOOGLE_SERVICES_JSON_PROD_BASE64"
      encoded="${GOOGLE_SERVICES_JSON_PROD_BASE64:-}"
      destination="app/src/Prod/google-services.json"
      expected_package="com.estatia.realestate.apps"
      ;;
    *)
      echo "::error::Unsupported Firebase flavor '$flavor'. Use demo or prod."
      exit 2
      ;;
  esac

  if [[ -z "$encoded" ]]; then
    echo "::error::Required GitHub Actions secret $secret_name is not set."
    exit 1
  fi

  temp_file="$(mktemp)"
  if ! printf '%s' "$encoded" | base64 --decode > "$temp_file"; then
    rm -f "$temp_file"
    echo "::error::$secret_name is not valid base64."
    exit 1
  fi

  # Validate the decoded JSON and ensure it contains the application ID for
  # this flavor. Only validation errors are printed; secret contents are never
  # emitted to the workflow log.
  if ! python3 - "$temp_file" "$expected_package" <<'PY'
import json
import sys

path, expected_package = sys.argv[1], sys.argv[2]
try:
    with open(path, "r", encoding="utf-8") as source:
        payload = json.load(source)
except (OSError, UnicodeDecodeError, json.JSONDecodeError):
    print("Decoded Firebase configuration is not valid JSON.", file=sys.stderr)
    raise SystemExit(1)

project_info = payload.get("project_info") if isinstance(payload, dict) else None
clients = payload.get("client") if isinstance(payload, dict) else None
if not isinstance(project_info, dict) or not isinstance(project_info.get("project_id"), str):
    print("Firebase JSON is missing project_info.project_id.", file=sys.stderr)
    raise SystemExit(1)
if not isinstance(clients, list):
    print("Firebase JSON is missing the client array.", file=sys.stderr)
    raise SystemExit(1)

matches = any(
    isinstance(client, dict)
    and isinstance(client.get("client_info"), dict)
    and isinstance(client["client_info"].get("android_client_info"), dict)
    and client["client_info"]["android_client_info"].get("package_name") == expected_package
    for client in clients
)
if not matches:
    print(
        f"Firebase JSON does not contain an Android client for {expected_package}.",
        file=sys.stderr,
    )
    raise SystemExit(1)
PY
  then
    rm -f "$temp_file"
    echo "::error::$secret_name does not match the expected $flavor application."
    exit 1
  fi

  mkdir -p "$(dirname "$destination")"
  install -m 600 "$temp_file" "$destination"
  rm -f "$temp_file"
  echo "Prepared and validated Firebase configuration for the $flavor flavor."
}

for flavor in "$@"; do
  prepare_flavor "$flavor"
done
