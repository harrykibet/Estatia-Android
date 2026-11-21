# benchmark

## Overview
The `benchmark` module is responsible for performance measurement and optimization of the Estatia application. It uses the AndroidX Macrobenchmark library to track critical user journeys and generate performance profiles.

## Responsibilities
- **Startup Benchmarks**: Measures the time from app launch to the first frame being drawn.
- **Scroll Benchmarks**: Measures frame drops and stability during vertical feed scrolling.
- **Baseline Profile Generation**: Generates profiles that are bundled with the app to improve cold start performance and reduce "jank" on first use.

## Key Files
- `StartupBenchmark.kt`: Automates cold, warm, and hot startup tests.
- `ScrollBenchmark.kt`: Simulates user scrolling in the property feed.
- `BaselineProfileGenerator.kt`: Triggers critical paths to record necessary classes and methods for AOT compilation.

## How to Run
Run the production macrobenchmarks against a connected Android device or emulator:
```bash
./gradlew :benchmark:connectedProdBenchmarkAndroidTest
```

The benchmark module targets the app's `prodBenchmark` build variant. The device must be connected through ADB and support AndroidX Macrobenchmark (API 29 or newer). The Nightly workflow provisions a Pixel 6 API 31 emulator, waits for Android boot completion, runs the connected-device tasks, and stops the emulator even if a task fails.

To generate variant-specific Baseline and Startup Profiles for both app environments:
```bash
./gradlew :app:generateDemoReleaseBaselineProfile :app:generateProdReleaseBaselineProfile
```

The app consumer configuration keeps profile output separate by variant and saves generated
rules under the corresponding app source set:

- `app/src/demoRelease/generated/baselineProfiles/`
- `app/src/prodRelease/generated/baselineProfiles/`

`BaselineProfileGenerator.generateStartup` contributes only the authenticated launch-to-Home
path to the Startup Profile. `generateCriticalUserJourneys` captures Home feed scrolling,
Search, Property Details, Market, Chats, Add Property, and Profile for the Baseline Profile
without expanding the Startup Profile. Startup rules are also included in the Baseline Profile.


## Production Baseline Profile account

The Prod generation job signs in through Estatia's real Firebase email/password login before
profile collection. Configure these repository Actions secrets:

- `BASELINE_PROFILE_TEST_EMAIL`
- `BASELINE_PROFILE_TEST_PASSWORD`

Use a dedicated Firebase account. It must have a verified email, a linked/verified phone
number (the login state machine requires a non-empty phone number), a matching Firestore user
document, and at least two active, readable property documents. The critical-journey profile
advances from the first property to the second and returns to the first, so one property is not
enough to validate the feed pager. Contact details are separately gated and may be unavailable
without making the public property itself unavailable. The credentials are passed as
instrumentation arguments only to the Prod job; the Demo job does not receive them. Login is
completed before `BaselineProfileRule.collect`, so it is not included in the Startup Profile.

## Firebase Storage coverage

The Prod Baseline Profile validates live Firebase Authentication and Firestore on the current
startup and critical journeys. It does not require live Storage because those journeys do not
upload media. The separate `Firebase Emulator Integration` workflow verifies authenticated
Firestore queries and Storage upload/download using the Firebase Auth, Firestore, and Storage
Emulators. Emulator success validates SDK integration, not live bucket billing, availability,
or production security rules.
