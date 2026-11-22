#!/usr/bin/env python3
"""Fail unless every upstream job succeeded or was intentionally skipped.

Usage: CI_JOB_RESULTS='<toJSON(needs)>' ci-gate.py

A skipped job is acceptable only because the scope classifier gated it off. A
failed, cancelled or missing result (including the classifier job itself) fails
the gate, so required-check configuration only needs to name the gate job.
"""
import json
import os
import sys


def main() -> int:
    jobs = json.loads(os.environ.get("CI_JOB_RESULTS", "{}"))
    if not jobs:
        print("::error::No upstream job results were provided to the CI gate.")
        return 1
    failed = {
        name: details.get("result", "unknown")
        for name, details in jobs.items()
        if details.get("result") not in {"success", "skipped"}
    }
    if failed:
        for name, result in sorted(failed.items()):
            print(f"::error::Required CI job {name} finished with result {result}.")
        return 1
    print("CI gate passed. All executed checks succeeded; skipped jobs were scope-gated.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
