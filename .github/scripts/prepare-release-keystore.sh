#!/usr/bin/env bash
set -euo pipefail

# Decode and validate the production signing keystore at the exact path used
# by AndroidApplicationConventionPlugin. Secret values are never printed.
required_secrets=(KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD)
for secret_name in "\${required_secrets[@]}"; do
  secret_value="\${!secret_name:-}"
  if [[ -z "$secret_value" || -z "$(printf '%s' "$secret_value" | tr -d '[:space:]')" ]]; then
    echo "::error::Required repository Actions secret $secret_name is missing or empty."
    exit 1
  fi
done

workspace="\${GITHUB_WORKSPACE:-$PWD}"
keystore_dir="$workspace/AppKeyStore"
keystore_path="$keystore_dir/keystore.jks"
mkdir -p "$keystore_dir"
temporary_keystore="$(mktemp "$keystore_dir/.keystore.XXXXXX")"
temporary_csr=""
cleanup() {
  rm -f "$temporary_keystore"
  if [[ -n "$temporary_csr" ]]; then rm -f "$temporary_csr"; fi
}
trap cleanup EXIT

encoded_keystore="$(printf '%s' "$KEYSTORE_BASE64" | tr -d '[:space:]')"
if ! printf '%s' "$encoded_keystore" | base64 --decode > "$temporary_keystore"; then
  echo "::error::KEYSTORE_BASE64 is not valid Base64."
  exit 1
fi
unset encoded_keystore
if [[ ! -s "$temporary_keystore" ]]; then
  echo "::error::The decoded keystore is empty."
  exit 1
fi

if [[ -n "\${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then
  keytool_bin="$JAVA_HOME/bin/keytool"
else
  keytool_bin="$(command -v keytool || true)"
fi
if [[ -z "$keytool_bin" ]]; then
  echo "::error::keytool was not found; configure JDK 17 before preparing release signing."
  exit 1
fi

# Validate the store password and alias without placing passwords in argv.
if ! "$keytool_bin" -list -keystore "$temporary_keystore" \
  -storepass:env KEYSTORE_PASSWORD -alias "$KEY_ALIAS" >/dev/null 2>&1; then
  echo "::error::The keystore could not be opened with KEYSTORE_PASSWORD or KEY_ALIAS was not found."
  exit 1
fi

# A temporary CSR proves the alias is a private-key entry and KEY_PASSWORD unlocks it.
temporary_csr="$(mktemp "\${RUNNER_TEMP:-/tmp}/estatia-signing-check.XXXXXX.csr")"
if ! "$keytool_bin" -certreq -keystore "$temporary_keystore" \
  -storepass:env KEYSTORE_PASSWORD -alias "$KEY_ALIAS" \
  -keypass:env KEY_PASSWORD -file "$temporary_csr" >/dev/null 2>&1; then
  echo "::error::The configured alias is not a usable private-key entry or KEY_PASSWORD is incorrect."
  exit 1
fi

install -m 600 "$temporary_keystore" "$keystore_path"
if [[ ! -s "$keystore_path" ]]; then
  echo "::error::The release keystore was not installed at AppKeyStore/keystore.jks."
  exit 1
fi
echo "Release keystore decoded and validated at AppKeyStore/keystore.jks."
