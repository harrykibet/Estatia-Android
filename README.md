# Estatia

> Rental infrastructure for emerging markets.

Estatia is a scalable digital platform powering trusted property discovery, landlord tooling, and monetized listing distribution. Built for national scale. Architected for long-term evolution.

---

## The Opportunity

Rental markets in emerging economies remain fragmented, offline-heavy, and trust-constrained. Discovery is inefficient. Visibility is uneven. Monetization is underdeveloped.

Estatia digitizes and structures this ecosystem.

---

## What Estatia Delivers

### Discovery Engine

* Location-aware property search
* Map-based exploration
* Personalized vertical media feed (match-aware prefetching)
* Structured filtering (price, type, amenities)

### Listing Infrastructure

* Multi-stage listing workflow
* Media upload pipeline
* Ownership validation
* Full lifecycle management

### Engagement & Analytics

* Likes, saves, comments
* Owner performance dashboard
* Exposure tracking

### Monetization Layer

* Integrated service fee processing
* Foundation for premium visibility tiers
* Monetization-first architecture

### Trust Framework

* Role-based access control
* Admin moderation pipeline
* Owner verification workflows

---

## Architecture

Estatia is built with strict modular boundaries and production-grade discipline.

![Estatia Module Graph](docs/images/graph/global_module_graph.png)

**Principles**

* Clean Architecture
* Dependency inversion
* Interface-driven design
* Unidirectional data flow
* Immutable state modeling
* Lifecycle-safe concurrency
* Zero business logic in UI
* Role-based Interface Segregation (Config, Security, Analytics)

**Module Structure**

```
:app
:benchmark
:lint
:core:analytics
:core:architecture
:core:canary-violations
:core:common
:core:config
:core:data
:core:database
:core:datastore
:core:datastore-proto
:core:design-system
:core:domain
:core:intelligence
:core:ksp-architecture
:core:localization
:core:model
:core:navigation
:core:network
:core:notifications
:core:player-engine
:core:player-ui
:core:security
:core:testing
:core:testing-architecture
:core:testing-network
:core:testing-player
:core:ui
:feature:home
:feature:auth
:feature:profile
:feature:search
:feature:property
:feature:payments
:feature:market
:feature:chats
:feature:favorites
:feature:comments
:feature:settings
:feature:shared-ui
```

---

## Technology

* Kotlin
* Jetpack Compose (Material 3)
* MVVM
* Hilt
* Coroutines + Flow
* AWS Amplify (Auth, AppSync, S3, Pinpoint, AppConfig)
* Google Maps SDK
* ExoPlayer
* Gradle Convention Plugins + Version Catalogs
* Baseline Profiles + Benchmarking

---

## Scalability

Engineered for:

* High-throughput media feeds
* Optimized Firestore query patterns
* Memory-efficient media handling
* Deterministic state management
* Cross-platform expansion (Android, iOS, Web)

---

## Security

* Server-enforced ownership rules
* Role-based access control
* Defensive validation
* Clear client/server boundaries

---

## Developer Resources

For contributors and engineers seeking deeper technical insights:

* [ARCHITECTURE.md](./docs/ARCHITECTURE.md) — module responsibilities, boundaries, and data flow.
* [ENGINEERING.md](./docs/ENGINEERING.md) — coding standards, testing, performance, and security.
* [CI/CD strategy](./docs/CICD.md) — path-aware verification tiers and operational commands.

## Community and Participation

Please review these policies before contributing or reporting a problem:

* [Code of Conduct](./CODE_OF_CONDUCT.md)
* [Contributing Guide](./CONTRIBUTING.md)
* [Support](./SUPPORT.md)
* [Security Policy](./SECURITY.md)
* [Accessibility Guidelines](./ACCESSIBILITY.md)
* [Issue templates](./.github/ISSUE_TEMPLATE/) and [Pull Request template](./.github/PULL_REQUEST_TEMPLATE.md)

### Verification Contract

Verification is selected by change impact; a full Android build is not the default for every edit.

* Documentation changes receive Markdown, relative-link, code-fence, and issue-form validation.
* Scripts and GitHub automation receive shell/Python syntax and supported configuration parsing.
* Ordinary Kotlin/Android changes use Gradle impact analysis to select module compile, lint, and unit-test tasks.
* Module Gradle files receive project-configuration validation.
* Global build logic and architecture enforcement changes receive the full architecture gate.
* R8 release, UI smoke, Firebase emulator, and Baseline Profile checks run only for their relevant change areas.

Run the global architecture gate locally when changing root build configuration, build logic, architecture enforcement, module boundaries, or shared project policy:

    ./gradlew verifyArchitecture

This gate includes lint, structural invariants, compiler-level architecture contracts, canary checks, and dependency-boundary validation.

---

## Vision

Estatia is building the foundational rental infrastructure layer for emerging markets — enabling scalable, transparent, and monetizable property ecosystems across platforms.

---

## Batch Branch Cleanup

A manually dispatched GitHub Actions workflow is available for deleting explicitly named batches of obsolete branches after review.

1. Open **Actions → Batch Branch Cleanup → Run workflow**.
2. Enter exact branch names in the `branches` input, one per line.
3. Run first with `dry_run` enabled and inspect the workflow summary.
4. Set `dry_run` to false only when you are ready to delete the listed refs.

The workflow protects the default branch, branches configured in `.github/branch-deletion-protected.txt`, branches backing open pull requests, and branches GitHub marks as protected. The reusable implementation is in `.github/scripts/delete-branches.py`.
