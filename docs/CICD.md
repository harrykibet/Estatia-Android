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
| Module Gradle configuration | Gradle project configuration check |
| Ordinary Kotlin/Android source | Gradle impact analysis selects module-level lint, unit-test, and compile tasks plus downstream consumers |
| Global build logic and architecture enforcement | Full architecture, lint, and convention-plugin gates |
| Release packaging, app variants, and shrinker rules | Minified Demo and Prod R8 validation |
| UI, navigation, manifest, and instrumented-test changes | Targeted Pixel 2 smoke instrumentation after impacted verification succeeds |
| Firebase adapters, app bootstrap, emulator rules, and Firebase tests | Firebase Auth, Firestore, and Storage emulator round trip |
| Benchmark harness, startup, feed, and player profiling paths | Demo and Prod Baseline Profile generation and packaging |

A routine Kotlin syntax correction should fail in its affected module compile or unit-test task. It does not by itself start release packaging, a device fleet, Firebase emulators, or Baseline Profile generation.

Full-project coverage and broad regression audits remain nightly/manual quality gates; they are not appended to every ordinary PR's impacted task list.

### Tier 2: Main branch (selective post-merge verification)

A push to main uses the same change classification. Ordinary source edits run impacted module tasks; module Gradle edits run configuration validation; global architecture changes run architecture/lint/convention gates. UI smoke and signed release builds run only when UI or packaging paths change. A manual dispatch can still request the full unit suite and four-device fleet.

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
