# Contributing to Estatia

Thank you for considering a contribution to Estatia. Contributions of code, tests, documentation, accessibility improvements, and careful bug reports are welcome.

Before participating, please read the [Code of Conduct](./CODE_OF_CONDUCT.md), [Security Policy](./SECURITY.md), and [Accessibility Guidelines](./ACCESSIBILITY.md).

## 1. Start with the project guidance

Estatia is a modular Kotlin and Jetpack Compose Android application. Please become familiar with:

- [Architecture](./docs/ARCHITECTURE.md) — module responsibilities, boundaries, and data flow.
- [Engineering guidance](./docs/ENGINEERING.md) — coding standards, testing, lifecycle safety, and production-grade class expectations.
- [CI/CD guide](./docs/CICD.md) — the verification pipeline and commands used by automation.

For a substantial change, discuss the proposed approach in a relevant issue before investing in implementation. Check existing issues and pull requests first to avoid duplicate work. Small, self-contained fixes can be submitted directly as a pull request.

## 2. Development environment

Use the Gradle Wrapper supplied by the repository; do not substitute a locally installed Gradle version. The CI configuration currently uses JDK 17 and the Android SDK.

You will generally need:

- Git and a supported JDK (JDK 17 is used in CI).
- Android SDK and an emulator or supported Android device for instrumented tests.
- The repository's Gradle Wrapper and the SDK path configured in `local.properties`.

Some application variants and integration workflows require service configuration. Never commit service configuration containing credentials, signing material, API keys, production data, or other secrets. See [SECURITY.md](./SECURITY.md) and [docs/CICD.md](./docs/CICD.md) for the repository's security and CI configuration guidance.

## 3. Create a focused branch

1. Fork the repository or create a branch from the current `main`.
2. Use a descriptive branch name, for example `fix/feed-state-restoration`, `feat/property-filter`, `docs/accessibility-guidance`, or `test/network-error-handling`.
3. Keep each pull request focused on one logical change.
4. Rebase or merge the latest `main` as appropriate before requesting review.

Do not commit directly to `main`.

## 4. Engineering expectations

Contributions should preserve the project's architecture and conventions:

- Keep presentation, domain logic, and data access separated.
- Respect module boundaries. Feature modules should depend on the intended domain contracts rather than reaching into another feature's implementation.
- Prefer immutable models and explicit state transitions. Expose observable UI state through the established `StateFlow` patterns.
- Keep asynchronous work lifecycle-safe and make cancellation and failure behavior explicit.
- Use constructor-injected dependencies and the project's established Hilt and Gradle convention-plugin patterns.
- Apply the project's semantic architecture annotations and production-grade class requirements where relevant.
- Avoid unrelated refactoring, new dependencies, generated output, or broad formatting changes in a focused bug fix.
- Add or update documentation when behavior, architecture, configuration, or contributor-facing workflows change.
- Follow [ACCESSIBILITY.md](./ACCESSIBILITY.md) for user-interface changes.

If a proposed change needs to alter an architectural boundary or a shared convention, explain the reason and trade-offs in the pull request instead of silently bypassing the existing contract.

## 5. Tests and local verification

Choose verification based on the files and behavior changed. Run the narrowest useful checks while developing, then run the relevant repository gates before opening a pull request.

### Documentation-only changes

Documentation-only changes do not need Android compilation, architecture tests, unit tests, instrumentation, release/R8 builds, benchmarks, or mutation testing. For a docs-only pull request:

- Check Markdown formatting, headings, code fences, and relative links.
- Check external links when practical.
- Verify commands and code examples against the current repository.
- Parse and validate YAML files, including GitHub issue forms, when those files change.
- State clearly when a documentation change describes a proposed or not-yet-implemented behavior.

When a documentation change also modifies executable scripts, GitHub Actions workflows, build configuration, or application source, run the relevant checks for those files as well.

### Scripts and GitHub automation

For shell scripts, Python helpers, GitHub Actions workflows, or automation configuration, run lightweight syntax checks:

    bash -n path/to/script.sh
    python3 -m py_compile path/to/script.py

The PR workflow also runs .github/scripts/validate-tooling.py on changed tooling/configuration files. It checks shell syntax, Python parsing, and supported YAML/JSON/XML/TOML files. ShellCheck warnings are added when ShellCheck is available.

### Ordinary application and module changes

For a Kotlin/Java source fix or a change contained within one module, run the narrowest useful verification. Estatia's Gradle calculateImpact task identifies the owning module and downstream consumers, then selects available compile/lint/unit-test tasks. You can inspect its output with:

    ./gradlew calculateImpact

Run the selected tasks printed as IMPACT_TASKS=.... A syntax error such as a missing brace should be caught by the affected module's compilation or unit-test task; it does not by itself require a full release build or device fleet.

### Gradle configuration and global architecture changes

Module-level Gradle edits receive a Gradle configuration check and impacted module compile/lint/unit-test tasks. Root build configuration and build logic may select the global path. Run the authoritative architecture contract for root build configuration, build logic, architecture enforcement, module boundaries, or other global policy changes:

    ./gradlew verifyArchitecture
Other useful checks for code changes include:

```bash
# Run unit tests across the project
./gradlew test

# Run Android lint
./gradlew lint

# Run the focused PR smoke instrumentation suite (requires the configured device)
./gradlew :app:pixel2Api34ProdDebugAndroidTest
```

For a module-specific change, run that module's relevant unit tests and instrumentation tests. Use `./gradlew tasks --all` to discover the exact task names available for a module or build variant. For performance-sensitive feed or media changes, consider the benchmark tasks documented in [docs/CICD.md](./docs/CICD.md).

Do not claim a test passed unless you ran it and observed a successful result. If a check cannot be run locally because it requires unavailable hardware, credentials, or external services, state that limitation in the pull request and rely on the corresponding CI gate where available.

## 6. Commit and pull request

Use clear commit messages that describe the change. A conventional-style prefix and scope are encouraged, for example:

- `fix(feed): restore pager state after navigation`
- `feat(search): add property type filter`
- `test(network): cover authentication failure`
- `docs: clarify local verification steps`

Before submitting:

- Confirm the diff contains only intended changes.
- Remove build output, local IDE state, credentials, signing files, and personal or production data.
- Add regression tests for behavior changes where practical.
- Update relevant documentation.
- Complete the [pull request template](./.github/PULL_REQUEST_TEMPLATE.md), including test results and accessibility impact.
- Link related issues and use closing keywords when the pull request resolves an issue.

Pull requests are reviewed and checked by the repository's configured automation. Resolve review feedback and investigate failed checks before requesting final review. Passing CI does not replace human review.

## 7. Security and privacy

Never report a suspected vulnerability in a public issue or pull request. Follow the private reporting instructions in [SECURITY.md](./SECURITY.md).

Do not include real user information, property-owner contact details, payment data, production logs, authentication tokens, API keys, or unredacted screenshots in issues, pull requests, tests, or fixtures. Use synthetic test data and redact diagnostic output.

## 8. Licensing

The project is distributed under the [Apache License 2.0](./LICENSE). By intentionally submitting a contribution for inclusion in the project, you agree that the contribution is made available under the terms described in that license, including its contribution provisions.

## 9. Getting help

Read [SUPPORT.md](./SUPPORT.md) for guidance on where to ask questions, report defects, and request improvements.
