#!/usr/bin/env python3
"""Print failed instrumentation test cases from AGP JUnit XML into the job log.

Gradle only says "There were failing tests" and points at an HTML report that is
only reachable through an artifact. This surfaces the failing test names and the
first lines of each failure message directly in the log, so a failed nightly run
can be diagnosed without downloading anything. Values of repository secrets are
masked by GitHub in the log; the full reports still go through the secret scrub
before they are uploaded.

Usage: summarize-instrumentation-failures.py <results-dir>...
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MAX_LINES_PER_FAILURE = 12


def main(argv: list[str]) -> int:
    files = sorted(
        path for root in argv for path in Path(root).rglob("*.xml")
        if path.is_file() and path.name.startswith("TEST-")
    )
    if not files:
        print("No instrumentation JUnit XML files found; nothing to summarize.")
        return 0

    total = failed = 0
    for path in files:
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError as error:
            print(f"::warning::Could not parse {path.name}: {error}")
            continue
        for case in suite.iter("testcase"):
            total += 1
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is None:
                continue
            failed += 1
            name = f"{case.get('classname', '?')}.{case.get('name', '?')}"
            message = (problem.get("message") or "").strip()
            detail = (problem.text or "").strip().splitlines()
            print(f"::group::FAILED {name}")
            if message:
                print(message)
            for line in detail[:MAX_LINES_PER_FAILURE]:
                print(line)
            print("::endgroup::")
            print(f"::error title=Instrumentation test failed::{name}: {message[:200]}")
    print(f"Instrumentation results: {failed} failed of {total} test case(s) in {len(files)} file(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
