# Support

This document explains where to look for help with Estatia and how to choose the right reporting channel.

## Before asking for help

1. Read the [README](./README.md) for the project overview and setup context.
2. Check the relevant technical guide:
   - [Architecture](./docs/ARCHITECTURE.md)
   - [Engineering](./docs/ENGINEERING.md)
   - [CI/CD](./docs/CICD.md)
3. Search existing GitHub issues and pull requests for the same question or problem.

## Where to ask

- **A reproducible defect:** Open a GitHub issue using the Bug Report form. Include the affected module, build variant, device/API level where relevant, expected behavior, actual behavior, and minimal reproduction steps.
- **A product or engineering improvement:** Use the Feature Request form. Describe the problem, intended outcome, and acceptance criteria rather than prescribing an implementation only.
- **A contribution or code change:** Read [CONTRIBUTING.md](./CONTRIBUTING.md) and submit a pull request.
- **A security vulnerability:** Do not create a public issue. Follow [SECURITY.md](./SECURITY.md) and use GitHub Private Vulnerability Reporting or the security contact listed there.
- **A conduct concern:** Follow the private reporting instructions in [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md).

General usage and implementation questions should first be checked against the documentation and existing discussions in the repository. Where GitHub Discussions are enabled, use the relevant category for questions that are not actionable bug reports or feature requests. Do not create an issue just to request private support or a guaranteed response.

## Information that helps

For technical reports, include only what is necessary to reproduce the issue:

- Relevant module, screen, workflow, or Gradle task.
- App version, commit, build variant, Android version, and device model when applicable.
- Exact steps and observed versus expected behavior.
- Sanitized logs, stack traces, screenshots, or test output when useful.
- Whether the problem is reproducible and whether it is a regression.

Remove authentication material, API keys, personal data, property-owner or tenant details, payment data, and production identifiers before sharing diagnostic information.

## Response expectations

Estatia is maintained through the project's available maintainer capacity. Support is best-effort; no response time or resolution time is guaranteed. Security reports are handled through the process and response expectations documented in [SECURITY.md](./SECURITY.md).
