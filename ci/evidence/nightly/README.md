# Nightly evidence

The scheduled/manual nightly workflow publishes a bounded, main-branch evidence snapshot after all producer jobs finish.

## Committed to main

- Four validated Demo and Prod Baseline/Startup Profile text files in their configured source-set directories.
- Latest available JaCoCo XML/HTML reports under latest/jacoco/.
- latest/coverage-summary.json and append-only coverage-history.jsonl.
- Latest available Macrobenchmark JSON files under latest/benchmarks/ and append-only benchmark-history.jsonl.
- latest/manifest.json, recording producer outcomes and the evidence categories updated by each run.

A failed quality gate does not block report publication. Missing report artifacts do not erase the previous latest snapshot. Profiles are replaced only when all four expected files contain rules and both signed release APKs pass signature and packaged-profile validation.

## Run-scoped artifacts

- Signed Demo and Prod release APKs plus their SHA-256 manifest: 30 days.
- Generated Baseline Profile artifact: 30 days.
- Quality, benchmark, baseline instrumentation and security reports: 14 days.
- Gradle resource diagnostics: 5 days.

The signed APKs are not committed to Git history. R8 mappings, Firebase configuration, keystores, raw instrumentation logcats and Gradle intermediates are not committed or uploaded in plaintext. Keep production R8 mappings in a private symbol/release store.
