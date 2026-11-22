#!/usr/bin/env python3
"""Run lightweight syntax and structure checks for changed repository tooling."""

from __future__ import annotations

import ast
import json
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path.cwd().resolve()


def changed_paths(base: str, head: str) -> list[str]:
    if not base or not head or set(base) == {"0"}:
        raise ValueError("A valid base and head commit are required.")
    result = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=ACMRT", "-z", base, head],
        check=True,
        stdout=subprocess.PIPE,
    )
    return [item for item in result.stdout.decode("utf-8").split("\0") if item]


def validate(path: Path, relative: str) -> None:
    suffix = path.suffix.lower()
    if suffix == ".sh":
        subprocess.run(["bash", "-n", str(path)], check=True)
        shellcheck = subprocess.run(
            ["bash", "-lc", "command -v shellcheck >/dev/null 2>&1"]
        ).returncode == 0
        if shellcheck:
            subprocess.run(["shellcheck", "--severity=warning", str(path)], check=True)
        else:
            print(f"::notice::{relative}: ShellCheck unavailable; bash syntax check passed.")
    elif suffix == ".py":
        ast.parse(path.read_text(encoding="utf-8"), filename=relative)
    elif suffix in {".yml", ".yaml"}:
        subprocess.run(
            ["ruby", "-e", 'require "yaml"; ARGV.each { |file| YAML.parse_file(file) }', str(path)],
            check=True,
        )
    elif suffix == ".json":
        json.loads(path.read_text(encoding="utf-8"))
    elif suffix == ".xml":
        ET.parse(path)
    elif suffix == ".toml":
        tomllib.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    if len(sys.argv) != 3:
        print("Usage: validate-tooling.py BASE_SHA HEAD_SHA", file=sys.stderr)
        return 2
    try:
        paths = changed_paths(sys.argv[1], sys.argv[2])
    except (ValueError, subprocess.CalledProcessError) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1

    selected = []
    for relative in paths:
        path = ROOT / relative
        if not path.is_file():
            continue
        if relative.startswith((".github/scripts/", ".github/workflows/", ".github/actions/", "scripts/")):
            selected.append((path, relative))
        elif relative.endswith((".sh", ".py", ".yml", ".yaml")):
            selected.append((path, relative))

    for path, relative in selected:
        try:
            validate(path, relative)
            print(f"Validated tooling syntax: {relative}")
        except (SyntaxError, ValueError, ET.ParseError, subprocess.CalledProcessError) as error:
            print(f"::error::{relative}: {error}", file=sys.stderr)
            return 1

    print(f"Tooling validation passed: {len(selected)} changed tooling/configuration file(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
