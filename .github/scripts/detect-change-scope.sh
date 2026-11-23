#!/usr/bin/env bash
# shellcheck disable=SC2034  # scope variables are read through indirect expansion
set -euo pipefail

event_name="${1:-}"
base_sha="${2:-}"
head_sha="${3:-}"
output_file="${GITHUB_OUTPUT:-/dev/null}"

# Output keys are emitted by name from this single list, so adding a scope can never
# silently shift positional arguments in three places. The same-named shell variables
# below hold the values.
scope_keys=(
  docs_only docs_changed tooling_only tooling_changed source_changed gradle_changed
  architecture_changed release_changed smoke_changed unknown_changed
  module_gradle_changed baseline_changed firebase_changed compile_only_allowed
)

emit_scope() {
  local key
  for key in "${scope_keys[@]}"; do
    printf '%s=%s\n' "$key" "${!key}"
  done >> "$output_file"
}

set_full_scope() {
  docs_only=false; docs_changed=false; tooling_only=false; tooling_changed=false
  source_changed=true; gradle_changed=true; architecture_changed=true
  release_changed=true; smoke_changed=true; unknown_changed=true
  module_gradle_changed=true; baseline_changed=true; firebase_changed=true
  compile_only_allowed=false
}

write_full_scope() {
  set_full_scope
  emit_scope
  echo "Unable to classify this event or diff safely; retaining full verification."
}

# Unexpected classifier errors must never produce empty outputs that silently
# skip required verification. Fall back to the conservative full-scope profile.
# If some outputs were already appended before the failure, GitHub Actions keeps
# the LAST value written for a duplicated key, so the full-scope values appended
# here always win.
fail_open() {
  local exit_code=$?
  trap - ERR
  echo "::warning::Change classifier failed with exit code ${exit_code}; retaining full verification."
  write_full_scope
  exit 0
}
trap fail_open ERR

case "$event_name" in
  pull_request|merge_group|push) ;;
  *) write_full_scope; exit 0 ;;
esac

# Shallow checkouts do not contain the base commit. Fetch just the two commits
# (tree diffs do not need history) so workflows can use fetch-depth: 1.
ensure_commit() {
  git cat-file -e "$1^{commit}" 2>/dev/null ||
    git fetch --no-tags --depth=1 --quiet origin "$1" 2>/dev/null || true
}
if [[ -n "$base_sha" && -n "$head_sha" && ! "$base_sha" =~ ^0+$ ]]; then
  ensure_commit "$base_sha"
  ensure_commit "$head_sha"
fi

if [[ -z "$base_sha" || -z "$head_sha" || "$base_sha" =~ ^0+$ ]] ||
   ! git cat-file -e "$base_sha^{commit}" 2>/dev/null ||
   ! git cat-file -e "$head_sha^{commit}" 2>/dev/null; then
  write_full_scope
  exit 0
fi

# Compare against the merge base, not the base branch tip: when main has moved since
# the branch point, a tip-to-head diff contains the inverse of unrelated main changes
# and inflates the scope. A shallow checkout may lack the history needed, so deepen
# once; if no merge base can be found, fall back to the tip (over-classifies, safe).
compare_base="$base_sha"
merge_base="$(git merge-base "$base_sha" "$head_sha" 2>/dev/null || true)"
if [[ -z "$merge_base" && "$(git rev-parse --is-shallow-repository 2>/dev/null)" == "true" ]]; then
  git fetch --no-tags --quiet --deepen=200 origin "$head_sha" "$base_sha" 2>/dev/null || true
  merge_base="$(git merge-base "$base_sha" "$head_sha" 2>/dev/null || true)"
fi
if [[ -n "$merge_base" ]]; then
  compare_base="$merge_base"
else
  echo "::notice::No merge base available; comparing against the base tip (scope may be over-estimated)."
fi

# --no-renames reports a move as delete + add. With rename detection only the new path
# is listed, so a source file moved to docs/ (or between modules) would hide the
# deletion from the owning module and could classify as documentation-only.
diff_file="$(mktemp)"
if ! git diff --name-only --no-renames --diff-filter=ACDMRT -z "$compare_base" "$head_sha" > "$diff_file"; then
  rm -f "$diff_file"
  write_full_scope
  exit 0
fi
mapfile -d '' -t changed_files < "$diff_file"
rm -f "$diff_file"
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

  if [[ "$is_docs" != "true" ]] && [[ ! "$path" =~ ^(app|benchmark|lint)/src/main/.+\.(kt|java)$ ]] && \
     [[ ! "$path" =~ ^(core|feature)/[^/]+/src/main/.+\.(kt|java)$ ]]; then
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
    benchmark/src/*|benchmark/build.gradle.kts|app/build.gradle.kts|app/proguard-rules.pro|app/src/main/AndroidManifest.xml|app/src/main/*EstatiaApplication.kt|app/src/main/*MainActivity.kt|app/src/demoRelease/generated/baselineProfiles/*|app/src/prodRelease/generated/baselineProfiles/*|feature/home/src/main/*HomeScreen.kt|feature/shared-ui/src/main/*PropertyFeedScreen.kt|core/player-engine/src/main/*Player*.kt|core/player-ui/src/main/*Player*.kt|build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt|gradle/libs.versions.toml|.github/scripts/prepare-google-services.sh|.github/scripts/run-gradle-with-diagnostics.sh|.github/scripts/scrub-check-secrets.sh|.github/workflows/baseline-profile-diagnostics.yml)
      baseline_changed=true
      ;;
  esac

  case "$path" in
    app/build.gradle.kts|app/src/main/*EstatiaApplication.kt|app/src/*Firebase*.kt|core/network/src/*firebase/*|core/network/src/*ProdDataSourcesModule.kt|core/network/build.gradle.kts|core/analytics/src/*|core/analytics/build.gradle.kts|build-logic/convention/src/main/kotlin/FirebaseConventionPlugin.kt|gradle/libs.versions.toml|firebase.emulators.json|firestore.emulator.rules|storage.emulator.rules|.github/scripts/prepare-google-services.sh|.github/scripts/prepare-android-emulator.sh|.github/scripts/start-connected-android-emulator.sh|.github/scripts/stop-connected-android-emulator.sh|.github/scripts/run-gradle-with-diagnostics.sh|.github/workflows/firebase-emulator-integration.yml)
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
       [[ "$path" == .github/workflows/pr_check.yml ]] ||
       [[ "$path" == .github/workflows/smoke-ui-diagnostics.yml ]] ||
       [[ "$path" == .github/scripts/run-gradle-with-diagnostics.sh ]] ||
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

emit_scope

printf 'Changed paths (%s):\n' "${#changed_files[@]}"
printf '  %s\n' "${changed_files[@]}"
printf 'Scope (compared against %s):' "${compare_base:0:12}"
for key in "${scope_keys[@]}"; do
  printf ' %s=%s' "$key" "${!key}"
done
printf '\n'
