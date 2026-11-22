#!/usr/bin/env python3
"""Run lightweight syntax and structure checks for changed repository tooling."""

from __future__ import annotations

import ast
import os
import json
import subprocess
import sys
import tempfile
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path.cwd().resolve()


def changed_paths(base: str, head: str) -> list[str]:
    if not base or not head or set(base) == {"0"}:
        raise ValueError("A valid base and head commit are required.")
    result = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=ACDMRT", "-z", base, head],
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


def test_change_scope_classifier() -> None:
    """Exercise the path classifier against representative isolated Git diffs."""
    classifier = ROOT / ".github" / "scripts" / "detect-change-scope.sh"
    cases = [
        ("docs", ["README.md"], {
            "docs_only": "true", "docs_changed": "true", "tooling_changed": "false",
            "source_changed": "false", "gradle_changed": "false",
            "architecture_changed": "false", "release_changed": "false",
            "smoke_changed": "false", "unknown_changed": "false",
        }),
        ("script", [".github/scripts/helper.sh"], {
            "docs_only": "false", "tooling_only": "true", "tooling_changed": "true",
            "source_changed": "false", "gradle_changed": "false",
            "architecture_changed": "false", "release_changed": "false",
            "smoke_changed": "false", "unknown_changed": "false",
            "baseline_changed": "false", "firebase_changed": "false",
            "compile_only_allowed": "false",
        }),
        ("module-source", ["core/model/src/main/kotlin/Sample.kt"], {
            "source_changed": "true", "gradle_changed": "false",
            "architecture_changed": "false", "release_changed": "false",
            "smoke_changed": "false", "unknown_changed": "false",
            "baseline_changed": "false", "firebase_changed": "false",
            "compile_only_allowed": "true",
        }),
        ("ui-source", ["feature/home/src/main/java/HomeScreen.kt"], {
            "source_changed": "true", "smoke_changed": "true",
            "architecture_changed": "false", "release_changed": "false",
            "unknown_changed": "false", "baseline_changed": "true",
            "firebase_changed": "false", "compile_only_allowed": "false",
        }),
        ("minor-ui-source", ["feature/profile/src/main/java/ProfileScreen.kt"], {
            "source_changed": "true", "smoke_changed": "true",
            "baseline_changed": "false", "firebase_changed": "false",
            "compile_only_allowed": "true",
        }),
        ("firebase-source", ["core/network/src/main/java/com/example/firebase/Firestore.kt"], {
            "source_changed": "true", "firebase_changed": "true",
            "compile_only_allowed": "false",
        }),
        ("firebase-prod-source", ["app/src/prod/java/com/example/FirebaseProdDataSource.kt"], {
            "source_changed": "true", "firebase_changed": "true",
            "compile_only_allowed": "false",
        }),
        ("firebase-demo-source", ["app/src/demo/java/com/example/FirebaseDemoDataSource.kt"], {
            "source_changed": "true", "firebase_changed": "true",
            "compile_only_allowed": "false",
        }),
        ("module-gradle", ["core/network/build.gradle.kts"], {
            "gradle_changed": "true", "module_gradle_changed": "true",
            "architecture_changed": "false", "release_changed": "false",
            "unknown_changed": "false",
        }),
        ("proguard", ["app/proguard-rules.pro"], {
            "tooling_changed": "true", "release_changed": "true",
            "architecture_changed": "false", "unknown_changed": "false",
        }),
        ("firebase-rules", ["firestore.emulator.rules"], {
            "tooling_changed": "true", "release_changed": "false",
            "architecture_changed": "false", "unknown_changed": "false",
        }),
        ("firebase-json", ["firebase.emulators.json"], {
            "tooling_changed": "true", "release_changed": "false",
            "architecture_changed": "false", "unknown_changed": "false",
        }),
        ("unknown", ["infra/custom.conf"], {
            "unknown_changed": "true", "architecture_changed": "true",
            "release_changed": "true",
        }),
        ("mixed-source-unknown", [
            "core/model/src/main/kotlin/Sample.kt",
            "infra/custom.conf",
        ], {
            "source_changed": "true", "unknown_changed": "true",
            "architecture_changed": "true", "release_changed": "true",
        }),
    ]

    for label, relative_paths, expected in cases:
        with tempfile.TemporaryDirectory(prefix="estatia-change-scope-") as temporary:
            worktree = Path(temporary)
            subprocess.run(["git", "init", "--quiet"], cwd=worktree, check=True)
            subprocess.run(["git", "config", "user.name", "Estatia CI"], cwd=worktree, check=True)
            subprocess.run(
                ["git", "config", "user.email", "ci@example.invalid"],
                cwd=worktree,
                check=True,
            )
            seed = worktree / ".scope-seed"
            seed.write_text("base\n", encoding="utf-8")
            subprocess.run(["git", "add", "-A"], cwd=worktree, check=True)
            subprocess.run(["git", "commit", "--quiet", "-m", "base"], cwd=worktree, check=True)
            base = subprocess.run(
                ["git", "rev-parse", "HEAD"], cwd=worktree, check=True,
                stdout=subprocess.PIPE, text=True,
            ).stdout.strip()

            for relative_path in relative_paths:
                changed_file = worktree / relative_path
                changed_file.parent.mkdir(parents=True, exist_ok=True)
                if relative_path.endswith(".json"):
                    changed_file.write_text('{"emulators": {}}\n', encoding="utf-8")
                elif relative_path.endswith((".sh", ".kt")):
                    changed_file.write_text("// scope test\n", encoding="utf-8")
                else:
                    changed_file.write_text("scope test\n", encoding="utf-8")
            subprocess.run(["git", "add", "-A"], cwd=worktree, check=True)
            subprocess.run(
                ["git", "commit", "--quiet", "-m", label], cwd=worktree, check=True
            )
            head = subprocess.run(
                ["git", "rev-parse", "HEAD"], cwd=worktree, check=True,
                stdout=subprocess.PIPE, text=True,
            ).stdout.strip()

            output_file = worktree / "scope.out"
            environment = os.environ.copy()
            environment["GITHUB_OUTPUT"] = str(output_file)
            execution = subprocess.run(
                ["bash", str(classifier), "pull_request", base, head],
                cwd=worktree,
                env=environment,
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            actual = {}
            for line in output_file.read_text(encoding="utf-8").splitlines():
                key, value = line.split("=", 1)
                actual[key] = value
            mismatches = {
                key: (expected_value, actual.get(key))
                for key, expected_value in expected.items()
                if actual.get(key) != expected_value
            }
            if mismatches:
                raise AssertionError(
                    f"Change-scope case {label} failed: {mismatches}\n{execution.stdout}"
                )

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
        elif relative.endswith((".sh", ".py", ".yml", ".yaml", ".json", ".xml", ".toml")):
            selected.append((path, relative))

    # Shell helpers are a shared automation surface. Whenever tooling validation
    # runs, lint every repository automation script so an unrelated tooling edit
    # cannot leave latent shell warnings in an untouched helper.
    for path in sorted((ROOT / ".github" / "scripts").glob("*.sh")):
        relative = path.relative_to(ROOT).as_posix()
        if path.is_file() and (path, relative) not in selected:
            selected.append((path, relative))

    for path, relative in selected:
        try:
            validate(path, relative)
            print(f"Validated tooling syntax: {relative}")
        except (SyntaxError, ValueError, ET.ParseError, subprocess.CalledProcessError) as error:
            print(f"::error::{relative}: {error}", file=sys.stderr)
            return 1

    try:
        test_change_scope_classifier()
        print("Change-scope classifier self-tests passed (14 representative path categories).")
    except (AssertionError, OSError, subprocess.CalledProcessError) as error:
        print(f"::error::Change-scope classifier self-test failed: {error}", file=sys.stderr)
        return 1

    print(f"Tooling validation passed: {len(selected)} changed tooling/configuration file(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
