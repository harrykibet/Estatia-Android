#!/usr/bin/env bash
set -euo pipefail

event_name="${1:-}"
base_sha="${2:-}"
head_sha="${3:-}"
output_file="${GITHUB_OUTPUT:-/dev/null}"

write_outputs() {
  {
    echo "docs_only=$1"
    echo "docs_changed=$2"
    echo "tooling_only=$3"
    echo "tooling_changed=$4"
    echo "source_changed=$5"
    echo "gradle_changed=$6"
    echo "architecture_changed=$7"
    echo "release_changed=$8"
    echo "smoke_changed=$9"
    echo "unknown_changed=${10}"
  } >> "$output_file"
}

write_full_scope() {
  write_outputs false false false false true true true true true true
  echo "Unable to classify this event or diff safely; retaining full verification."
}

case "$event_name" in
  pull_request|merge_group|push) ;;
  *) write_full_scope; exit 0 ;;
esac

if [[ -z "$base_sha" || -z "$head_sha" || "$base_sha" =~ ^0+$ ]] ||
   ! git cat-file -e "$base_sha^{commit}" 2>/dev/null ||
   ! git cat-file -e "$head_sha^{commit}" 2>/dev/null; then
  write_full_scope
  exit 0
fi

mapfile -d '' -t changed_files < <(git diff --name-only --diff-filter=ACMRT -z "$base_sha" "$head_sha")
if [[ ${#changed_files[@]} -eq 0 ]]; then
  write_full_scope
  echo "No changed paths found; retaining full verification."
  exit 0
fi

docs_only=true
docs_changed=false
tooling_only=true
tooling_changed=false
source_changed=false
gradle_changed=false
architecture_changed=false
release_changed=false
smoke_changed=false
unknown_changed=false

for path in "${changed_files[@]}"; do
  is_docs=false
  is_tooling=false

  case "$path" in
    README.md|CODE_OF_CONDUCT.md|CONTRIBUTING.md|ACCESSIBILITY.md|SUPPORT.md|SECURITY.md|CHANGELOG.md|GOVERNANCE.md|MAINTAINERS.md|AUTHORS.md|CONTRIBUTORS.md|docs/*|LICENSE|LICENSE.*|NOTICE|NOTICE.*|COPYING|.github/ISSUE_TEMPLATE/*|.github/PULL_REQUEST_TEMPLATE.md)
      is_docs=true
      docs_changed=true
      ;;
    *)
      if [[ "$path" =~ ^(app|benchmark|lint|build-logic)/README\.md$ || "$path" =~ ^(core|feature)/[^/]+/README\.md$ ]]; then
        is_docs=true
        docs_changed=true
      fi
      ;;
  esac

  case "$path" in
    .github/scripts/*|.github/workflows/*|.github/actions/*|scripts/*|*.sh|*.py|*.yml|*.yaml|codemagic.yaml|.gitlab-ci.yml)
      is_tooling=true
      tooling_changed=true
      ;;
  esac

  [[ "$is_docs" == "true" ]] || docs_only=false
  if [[ "$is_docs" != "true" && "$is_tooling" != "true" ]]; then
    tooling_only=false
  fi

  case "$path" in
    app/src/*|core/*/src/*|feature/*/src/*|benchmark/src/*|lint/src/*)
      source_changed=true
      ;;
  esac

  case "$path" in
    settings.gradle|settings.gradle.kts|build.gradle|build.gradle.kts|gradle.properties|gradle/*|buildSrc/*|build-logic/*|lint/*|core/architecture/*|core/testing-architecture/*|core/ksp-architecture/*|core/canary-violations/*)
      gradle_changed=true
      ;;
  esac

  case "$path" in
    settings.gradle|settings.gradle.kts|build.gradle|build.gradle.kts|gradle.properties|gradle/*|buildSrc/*|build-logic/*|lint/*|core/architecture/*|core/testing-architecture/*|core/ksp-architecture/*|core/canary-violations/*)
      architecture_changed=true
      ;;
  esac

  case "$path" in
    app/build.gradle|app/build.gradle.kts|app/proguard-rules.pro|app/*/proguard-rules.pro|app/src/*/AndroidManifest.xml|build.gradle|build.gradle.kts|settings.gradle|settings.gradle.kts|gradle.properties|gradle/libs.versions.toml|buildSrc/*|build-logic/*)
      release_changed=true
      ;;
  esac

  if [[ "$is_docs" != "true" ]] && {
       [[ "$path" =~ ^core/(ui|navigation|design-system|player-ui)/src/main/ ]] ||
       [[ "$path" =~ ^feature/[^/]+/src/main/.*(Screen|Route|NavGraph|Navigation).*\\.kt$ ]] ||
       [[ "$path" =~ ^feature/[^/]+/src/main/.*/(ui|navigation)/ ]] ||
       [[ "$path" =~ ^app/src/main/.*(MainActivity|MainApplication|EstatiaApplication|NavGraph|Navigation).*\\.kt$ ]] ||
       [[ "$path" == "app/src/main/AndroidManifest.xml" ]] ||
       [[ "$path" =~ /src/androidTest/ ]] ||
     [[ "$path" == .github/actions/smoke-ui-local/* ]] ||
       [[ "$path" == .github/scripts/prepare-android-emulator.sh ]] ||
       [[ "$path" == .github/scripts/start-connected-android-emulator.sh ]] ||
       [[ "$path" == .github/scripts/stop-connected-android-emulator.sh ]]
     }; then
    smoke_changed=true
  fi

  if [[ "$is_docs" != "true" && "$is_tooling" != "true" && "$source_changed" != "true" && "$gradle_changed" != "true" ]]; then
    unknown_changed=true
    architecture_changed=true
    release_changed=true
  fi
done

write_outputs "$docs_only" "$docs_changed" "$tooling_only" "$tooling_changed" \
  "$source_changed" "$gradle_changed" "$architecture_changed" "$release_changed" "$smoke_changed" "$unknown_changed"

printf 'Changed paths (%s):\n' "${#changed_files[@]}"
printf '  %s\n' "${changed_files[@]}"
printf 'Scope: docs=%s tooling=%s source=%s gradle=%s architecture=%s release=%s smoke=%s unknown=%s\n' \
  "$docs_only" "$tooling_only" "$source_changed" "$gradle_changed" "$architecture_changed" "$release_changed" "$smoke_changed" "$unknown_changed"
