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
    echo "module_gradle_changed=${11}"
    echo "baseline_changed=${12}"
    echo "firebase_changed=${13}"
    echo "compile_only_allowed=${14}"
  } >> "$output_file"
}

write_full_scope() {
  write_outputs false false false false true true true true true true true true true false
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

mapfile -d '' -t changed_files < <(git diff --name-only --diff-filter=ACDMRT -z "$base_sha" "$head_sha")
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
module_gradle_changed=false
baseline_changed=false
firebase_changed=false
compile_only_files=true

for path in "${changed_files[@]}"; do
  is_docs=false
  is_tooling=false
  is_source=false
  is_gradle=false

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
    .github/scripts/*|.github/workflows/*|.github/actions/*|scripts/*)
      is_tooling=true
      tooling_changed=true
      ;;
  esac

  case "$path" in
    *.sh|*.py|*.yml|*.yaml|*.json|*.xml|*.toml|*.rules|*.pro)
      is_tooling=true
      tooling_changed=true
      ;;
  esac

  if [[ "$is_docs" != "true" ]] && [[ ! "$path" =~ ^(app|benchmark|lint)/src/main/.+\\.(kt|java)$ ]] && \
     [[ ! "$path" =~ ^(core|feature)/[^/]+/src/main/.+\\.(kt|java)$ ]]; then
    compile_only_files=false
  fi

  case "$path" in
    app/src/*|core/*/src/*|feature/*/src/*|benchmark/src/*|lint/src/*)
      is_source=true
      source_changed=true
      ;;
  esac

  case "$path" in
    *.gradle|*.gradle.kts|gradle.properties|gradle/*|buildSrc/*|build-logic/*)
      is_gradle=true
      gradle_changed=true
      ;;
  esac

  [[ "$is_docs" == "true" ]] || docs_only=false
  if [[ "$is_docs" != "true" && "$is_tooling" != "true" ]]; then
    tooling_only=false
  fi

  case "$path" in
    app/build.gradle|app/build.gradle.kts|core/*/build.gradle|core/*/build.gradle.kts|feature/*/build.gradle|feature/*/build.gradle.kts|benchmark/build.gradle|benchmark/build.gradle.kts|lint/build.gradle|lint/build.gradle.kts)
      module_gradle_changed=true
      ;;
  esac

  case "$path" in
    build.gradle|build.gradle.kts|settings.gradle|settings.gradle.kts|gradle.properties|gradle/*|buildSrc/*|build-logic/*|lint/*|core/architecture/*|core/testing-architecture/*|core/ksp-architecture/*|core/canary-violations/*)
      architecture_changed=true
      ;;
  esac

  case "$path" in
    benchmark/src/*|benchmark/build.gradle.kts|app/build.gradle.kts|app/proguard-rules.pro|app/src/main/AndroidManifest.xml|app/src/main/*EstatiaApplication.kt|app/src/main/*MainActivity.kt|app/src/demoRelease/generated/baselineProfiles/*|app/src/prodRelease/generated/baselineProfiles/*|feature/home/src/main/*HomeScreen.kt|feature/shared-ui/src/main/*PropertyFeedScreen.kt|core/player-engine/src/main/*Player*.kt|core/player-ui/src/main/*Player*.kt|build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt|gradle/libs.versions.toml|.github/scripts/prepare-google-services.sh)
      baseline_changed=true
      ;;
  esac

  case "$path" in
    app/build.gradle.kts|app/src/main/*EstatiaApplication.kt|app/src/*Firebase*.kt|app/src/androidTest/*FirebaseEmulatorIntegrationTest.kt|core/network/src/*firebase/*|core/network/src/*ProdDataSourcesModule.kt|core/network/build.gradle.kts|core/analytics/src/*|core/analytics/build.gradle.kts|build-logic/convention/src/main/kotlin/FirebaseConventionPlugin.kt|gradle/libs.versions.toml|firebase.emulators.json|firestore.emulator.rules|storage.emulator.rules|.github/scripts/prepare-google-services.sh|.github/scripts/prepare-android-emulator.sh|.github/scripts/start-connected-android-emulator.sh|.github/scripts/stop-connected-android-emulator.sh)
      firebase_changed=true
      ;;
  esac



  case "$path" in
    app/build.gradle|app/build.gradle.kts|app/proguard-rules.pro|app/*/proguard-rules.pro|app/src/main/AndroidManifest.xml|app/src/demo/AndroidManifest.xml|app/src/prod/AndroidManifest.xml|app/src/demoRelease/AndroidManifest.xml|app/src/prodRelease/AndroidManifest.xml|build.gradle|build.gradle.kts|settings.gradle|settings.gradle.kts|gradle.properties|gradle/libs.versions.toml|buildSrc/*|build-logic/*)
      release_changed=true
      ;;
  esac

  if [[ "$is_docs" != "true" ]] && {
       [[ "$path" =~ ^core/(ui|navigation|design-system|player-ui)/src/main/ ]] ||
       [[ "$path" =~ ^feature/[^/]+/src/main/.*(Screen|Route|NavGraph|Navigation).*\.kt$ ]] ||
       [[ "$path" =~ ^feature/[^/]+/src/main/.*/(ui|navigation)/ ]] ||
       [[ "$path" =~ ^app/src/main/.*(MainActivity|MainApplication|EstatiaApplication|NavGraph|Navigation).*\.kt$ ]] ||
       [[ "$path" == "app/src/main/AndroidManifest.xml" ]] ||
       [[ "$path" =~ /src/androidTest/ ]] ||
     [[ "$path" == .github/actions/smoke-ui-local/* ]] ||
       [[ "$path" == .github/scripts/prepare-android-emulator.sh ]] ||
       [[ "$path" == .github/scripts/start-connected-android-emulator.sh ]] ||
       [[ "$path" == .github/scripts/stop-connected-android-emulator.sh ]]
     }; then
    smoke_changed=true
  fi

  if [[ "$is_docs" != "true" && "$is_tooling" != "true" && "$is_source" != "true" && "$is_gradle" != "true" ]]; then
    unknown_changed=true
    architecture_changed=true
    release_changed=true
  fi
done

compile_only_allowed=false
if [[ "$source_changed" == "true" && "$compile_only_files" == "true" && \
      "$gradle_changed" != "true" && "$architecture_changed" != "true" && \
      "$release_changed" != "true" && \
      "$unknown_changed" != "true" && "$module_gradle_changed" != "true" && \
      "$baseline_changed" != "true" && "$firebase_changed" != "true" ]]; then
  compile_only_allowed=true
fi

write_outputs "$docs_only" "$docs_changed" "$tooling_only" "$tooling_changed" \
  "$source_changed" "$gradle_changed" "$architecture_changed" "$release_changed" "$smoke_changed" "$unknown_changed" "$module_gradle_changed" \
  "$baseline_changed" "$firebase_changed" "$compile_only_allowed"

printf 'Changed paths (%s):\n' "${#changed_files[@]}"
printf '  %s\n' "${changed_files[@]}"
printf 'Scope: docs_only=%s docs_changed=%s tooling_only=%s tooling_changed=%s source_changed=%s gradle_changed=%s architecture_changed=%s release_changed=%s smoke_changed=%s unknown_changed=%s module_gradle_changed=%s baseline_changed=%s firebase_changed=%s compile_only_allowed=%s\n' \
  "$docs_only" "$docs_changed" "$tooling_only" "$tooling_changed" "$source_changed" "$gradle_changed" "$architecture_changed" "$release_changed" "$smoke_changed" "$unknown_changed" "$module_gradle_changed" "$baseline_changed" "$firebase_changed" "$compile_only_allowed"
