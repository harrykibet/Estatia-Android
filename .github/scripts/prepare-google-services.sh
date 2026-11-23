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
      destination="app/src/demo/google-services.json"
      expected_package="com.estatia.realestate.apps.demo"
      ;;
    prod)
      secret_name="GOOGLE_SERVICES_JSON_PROD_BASE64"
      encoded="${GOOGLE_SERVICES_JSON_PROD_BASE64:-}"
      destination="app/src/prod/google-services.json"
      expected_package="com.estatia.realestate.apps"
      ;;
    *)
      echo "::error::Unsupported Firebase flavor '$flavor'. Use demo or prod."
      exit 2
      ;;
  esac

  temp_file="$(mktemp)"
  if [[ -z "$encoded" ]]; then
    if [[ "${ALLOW_CI_FIREBASE_CONFIG_FALLBACK:-false}" != "true" ]]; then
      rm -f "$temp_file"
      echo "::error::Required GitHub Actions secret $secret_name is not set."
      exit 1
    fi

    # Dependabot pull_request workflows do not receive repository secrets.
    # Use Firebase's demo-project namespace so CLI/emulator use is isolated
    # from production and cannot silently target a live Firebase project.
    local ci_project_id ci_app_id
    ci_project_id="demo-estatia-ci-${flavor}"
    if [[ "$flavor" == "demo" ]]; then
      ci_app_id="1:123456789012:android:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    else
      ci_app_id="1:123456789012:android:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    fi

    cat > "$temp_file" <<JSON
{
  "project_info": {
    "project_number": "123456789012",
    "project_id": "$ci_project_id",
    "storage_bucket": "$ci_project_id.firebasestorage.app"
  },
  "client": [
    {
      "client_info": {
        "mobilesdk_app_id": "$ci_app_id",
        "android_client_info": {
          "package_name": "$expected_package"
        }
      },
      "oauth_client": [],
      "api_key": [
        {
          "current_key": "AIzaSyDUMMYCIKEY000000000000000000000000000"
        }
      ],
      "services": {
        "appinvite_service": {
          "other_platform_oauth_client": []
        }
      }
    }
  ],
  "configuration_version": "1"
}
JSON
    echo "::notice::Using isolated $ci_project_id Firebase configuration because repository secrets are unavailable."
  elif ! printf '%s' "$encoded" | base64 --decode > "$temp_file"; then
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

  if [[ ! -s "$destination" ]]; then
    echo "::error::Firebase configuration was not created at $destination."
    exit 1
  fi

  echo "Prepared and validated Firebase configuration for the $flavor flavor at $destination."
}

for flavor in "$@"; do
  prepare_flavor "$flavor"
done
