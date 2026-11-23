# Estatia CI/CD Strategy

This document outlines the tiered CI/CD architecture for the Estatia Android project. Our pipeline is designed based on **Google's Modern Android Development (MAD)** best practices to balance developer velocity, hardware validation, and production-grade stability.

---

## 🏗️ The Multi-Platform Foundation

Estatia maintains identical CI/CD logic across three platforms to prevent vendor lock-in and ensure consistency for all contributors:
- **GitHub Actions**: Primary automation for PRs and Releases.
- **GitLab CI**: Secondary runner support for enterprise mirroring.
- **Codemagic**: Dedicated mobile CI for high-performance builds and real-device workflows.

All platforms utilize **Gradle Managed Devices (GMD)** to ensure that tests run on identical virtual hardware environments, eliminating "it works on my machine" issues.

---

## 🚦 The Three-Tiered Pipeline

We use a "funnel" approach where expensive operations are deferred to later stages.

### Tier 1: Pull-request feedback (change-impact aware)

Every pull request is classified before expensive jobs start. Classification is fail-safe: an unknown event, unavailable diff range, or unrecognized path retains conservative verification.

| Change category | Verification |
|---|---|
| Documentation and community files | Markdown fences and relative-link validation; issue-form YAML parsing where applicable |
| Scripts and GitHub automation | Shell syntax, Python parsing, YAML parsing, and supported JSON/XML/TOML parsing; ShellCheck warnings when available |
| Module Gradle configuration | Gradle project configuration check plus impacted module verification tasks |
| Ordinary Kotlin/Android source | Gradle impact analysis selects module-level lint, unit-test, and compile tasks plus downstream consumers |
| Global build logic and architecture enforcement | Full architecture, lint, and convention-plugin gates |
| Release packaging, app variants, and shrinker rules | Minified Demo and Prod R8 validation |
| UI, navigation, manifest, and instrumented-test changes | Targeted Pixel 2 smoke instrumentation after impacted verification succeeds |
| Firebase adapters, app bootstrap, emulator rules, and Firebase tests | Firebase Auth, Firestore, and Storage emulator round trip |
| Benchmark harness, startup, feed, and player profiling paths | Demo and Prod Baseline Profile generation and packaging |

A routine Kotlin syntax correction should fail in its affected module compile or unit-test task. It does not by itself start release packaging, a device fleet, Firebase emulators, or Baseline Profile generation.

Full-project coverage and broad regression audits remain nightly/manual quality gates; they are not appended to every ordinary PR's impacted task list.

### CI resource budgets and telemetry

The standard public ubuntu-24.04 hosted runner provides 4 vCPUs and 16 GB RAM. Resource artifacts collected on 22 November 2026 showed connected ProdDebug instrumentation reaching approximately 3.0 GiB of available memory while Gradle used about 6.5 GiB RSS, QEMU about 3.3 GiB and the Kotlin compiler daemon about 0.8 GiB. Architecture/lint reached about 3.6 GiB of available memory. These successful comparison-job snapshots show limited headroom and memory pressure, but do not prove that memory pressure caused the failed runner shutdowns; the cgroup OOM-kill counters in those comparison jobs were zero.

Emulator-heavy jobs use a controlled experiment profile: one Gradle worker, a 4.5 GiB Gradle heap, a 1.5 GiB Kotlin daemon heap, and disabled Gradle/Kotlin project parallelism. This leaves more CPU and memory headroom for QEMU and, in Firebase integration, Auth/Firestore/Storage emulator processes. Every Gradle invocation in these jobs uses the shared diagnostics wrapper so preflight commands and the final instrumentation command do not create incompatible Gradle daemons with different heap arguments.

The diagnostics wrapper records detailed snapshots in RUNNER_TEMP/gradle-ci-diagnostics for artifact upload and emits a compact [ci-resource] line every 30 seconds. Live telemetry includes available memory, cgroup current/peak usage and OOM counters when exposed, memory/CPU/I/O pressure stall information, load average and the largest resident processes. The live Actions log is essential when the runner shuts down before if: always() cleanup and artifact-upload steps can run.

The Android emulator guest memory and device profile remain unchanged during this first experiment to preserve Baseline Profile device characteristics. Evaluate peak RSS, PSI, build duration and task outcomes before adjusting those device settings or changing the resource budgets again.

### Pull-request trigger layers

The PR workflow uses the pull-request lifecycle as a cost boundary rather than choosing only one trigger:

| PR state/event | Checks |
|---|---|
| Draft opened or draft commit pushed | Change classification; documentation and tooling validation when relevant; Gradle configuration validation when relevant; the fast architecture regression suite for architecture changes; and impacted module verification |
| Draft marked ready for review | The same fast and impact-aware checks, plus the relevant heavy tier: full architecture/lint/convention validation, minified R8 validation, Pixel 2 smoke instrumentation, Firebase emulator integration, and Baseline Profile generation |
| New commit pushed after review readiness | Re-run the applicable fast and heavy checks against the new head; concurrency cancels superseded PR runs |
| Manual dispatch / scheduled security scan | Explicit exhaustive or scheduled coverage, independent of draft state |

Baseline Profile generation takes 30-45 minutes and its output is not committed on a PR, so on a PR it runs only when the PR is opened, reopened or marked ready for review (never as a draft). Later pushes skip it unless the PR carries the `ci:profile` label. It always runs for profiling-related changes on pushes to `main`. Heavy jobs use `!cancelled()` so a cancelled run does not keep consuming runner minutes, while gate jobs use `always()` so they fail closed. Label changes no longer re-trigger PR Check; the `ci:compile-only` label takes effect on the next push. Third-party actions are pinned to commit SHAs (tag kept as a comment for Dependabot), and the prod benchmark credentials are read from the environment and scrubbed from artifacts before upload.

The heavy workflows remain change-scoped. For example, a ready PR does not run Baseline Profile generation unless profiling-related paths changed, and OWASP Dependency-Check runs only when dependency-related paths changed. The dependency scan is skipped while a PR is draft and runs when it becomes ready for review. Each workflow has a stable gate job so an intentionally skipped, classifier-approved heavy job is distinguishable from a missing or failed check.

The stable PR status checks intended for branch protection are `CI Gate`, `Baseline Profile Gate`, `Firebase Integration Gate`, and `Dependency Security Gate`. Configure these as required checks on `main`; the per-workflow gates aggregate the jobs within their own workflow.

### Pre-merge freshness for this repository

The workflows retain `merge_group` triggers for portability, but this repository is owned by a personal account. GitHub currently makes merge queues available only to public repositories owned by organizations, or qualifying private repositories owned by organizations on GitHub Enterprise Cloud. Therefore, the `merge_group` path is not an active pre-merge layer for this repository.

Use the strict branch-freshness alternative on `main`: require branches to be up to date before merging. When `main` advances, update the PR branch and wait for the PR checks to pass on that updated head before merging. The `main_check` workflow is an additional post-merge safety net for the checks it defines; it is not a replacement for checking an up-to-date PR before merge.

### Tier 2: Main branch (selective post-merge verification)

A push to main uses the same change classification. Ordinary source edits run impacted module tasks; module Gradle edits run configuration validation and impacted module tasks; global architecture changes run architecture/lint/convention gates and the full unit-test suite. UI smoke and signed release builds run only when UI or packaging paths change. A manual dispatch can still request the full unit suite and four-device fleet.

### Tier 3: Nightly and manual exhaustive verification

Nightly and manual workflows retain the broad regression, chaos, coverage, benchmark, security, baseline-profile, and architecture audits. This keeps expensive fleet-level verification available without making every small PR pay for it.

## 📱 Supported Hardware Fleet (GMD)

The project defines a standardized fleet in `build-logic` used by all CI runners:

| Device Name | Type | API Level | Purpose |
| :--- | :--- | :--- | :--- |
| `pixel2Api34` | Small Phone | 34 | Speed / Legacy Layouts |
| `pixel6Api31` | Modern Phone | 31 | Performance / Android 12+ |
| `mediumPhoneApi33` | Standard | 33 | Common Density Validation |
| `pixelTabletApi34` | Tablet | 34 | Large Screen / Adaptive UI |

---

## 🛠️ Operational Commands

Developers can run any CI job locally using these commands:

```bash
# Run the Architecture Gate
./gradlew verifyArchitecture

# Run the PR Smoke Suite
./gradlew :app:pixel2Api34ProdDebugAndroidTest

# Run the Full Fleet Test (Main Tier)
./gradlew :app:allDevicesProdDebugAndroidTest

# Generate Baseline Profiles (Nightly Tier)
./gradlew :app:generateBaselineProfile

# Run Performance Benchmarks
./gradlew :benchmark:pixel6Api31ProdDebugAndroidTest

# Run Binary Purity Audit
./gradlew auditReleaseSymbols
```

---

## 🔒 Security & Secrets
### GitHub Actions repository secrets

The current GitHub Actions workflows consume these repository secrets:

- `GOOGLE_SERVICES_JSON_DEMO_BASE64`: Base64-encoded Firebase configuration for the demo application ID.
- `GOOGLE_SERVICES_JSON_PROD_BASE64`: Base64-encoded Firebase configuration for the production application ID.
- `KEYSTORE_BASE64`: Base64-encoded production signing keystore. It is decoded at runtime to the ignored path `AppKeyStore/keystore.jks`.
- `KEYSTORE_PASSWORD`: Plain-text keystore integrity password.
- `KEY_ALIAS`: Plain-text private-key alias inside the keystore.
- `KEY_PASSWORD`: Plain-text password that unlocks the aliased private key.
- `NVD_API_KEY`: NVD API key used by the dependency vulnerability scans.

The nightly Baseline Profile job uses the automatically provided `GITHUB_TOKEN` with job-scoped `contents: write`, `pull-requests: write`, and `issues: write` permissions. No separate `CI_PUSH_TOKEN` is required by that job.

The current GitHub workflows do not reference `SONAR_TOKEN` or `FIREBASE_TOKEN`; retain them only if another connected CI platform or deployment process still uses them.
