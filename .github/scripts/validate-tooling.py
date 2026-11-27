#!/usr/bin/env python3
"""Run lightweight syntax and structure checks for changed repository tooling."""

from __future__ import annotations

import ast
import os
import json
import subprocess
import sys
import tempfile
import time
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path.cwd().resolve()


def changed_paths(base: str, head: str, cwd: Path | None = None) -> list[str]:
    if not base or not head or set(base) == {"0"}:
        raise ValueError("A valid base and head commit are required.")
    # Match detect-change-scope.sh: classify only changes introduced since the
    # common ancestor, not unrelated changes that arrived on the base branch later.
    # The optional cwd also lets the self-tests exercise isolated temporary repos.
    repository = cwd or ROOT
    merge_base = subprocess.run(
        ["git", "merge-base", base, head],
        cwd=repository,
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    ).stdout.strip()
    if not merge_base:
        raise ValueError("A merge base could not be found for the supplied commits.")
    result = subprocess.run(
        ["git", "diff", "--name-only", "--no-renames", "--diff-filter=ACDMRT", "-z", merge_base, head],
        cwd=repository,
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

def _run_git(args, cwd) -> str:
    return subprocess.run(
        ["git", *args], cwd=cwd, check=True, stdout=subprocess.PIPE, text=True,
    ).stdout.strip()


def _parse_outputs(output_file: Path) -> dict:
    parsed = {}
    for line in output_file.read_text(encoding="utf-8").splitlines():
        key, value = line.split("=", 1)
        parsed[key] = value  # last value wins, exactly like GitHub Actions
    return parsed


def _assert_full_scope(label: str, actual: dict) -> None:
    expected = {"source_changed": "true", "gradle_changed": "true",
                "architecture_changed": "true", "release_changed": "true",
                "unknown_changed": "true", "baseline_changed": "true",
                "firebase_changed": "true", "docs_only": "false",
                "compile_only_allowed": "false"}
    mismatches = {k: (v, actual.get(k)) for k, v in expected.items() if actual.get(k) != v}
    if mismatches:
        raise AssertionError(f"Fail-open case {label} did not retain full scope: {mismatches}")


def test_change_scope_fail_open() -> None:
    """Prove the classifier fails open and works from shallow checkouts."""
    classifier = ROOT / ".github" / "scripts" / "detect-change-scope.sh"
    with tempfile.TemporaryDirectory(prefix="estatia-scope-open-") as temporary:
        root = Path(temporary)
        origin = root / "origin"
        origin.mkdir()
        _run_git(["init", "--quiet"], origin)
        _run_git(["config", "user.name", "Estatia CI"], origin)
        _run_git(["config", "user.email", "ci@example.invalid"], origin)
        _run_git(["config", "uploadpack.allowAnySHA1InWant", "true"], origin)
        (origin / "seed").write_text("base\n", encoding="utf-8")
        _run_git(["add", "-A"], origin)
        _run_git(["commit", "--quiet", "-m", "base"], origin)
        base = _run_git(["rev-parse", "HEAD"], origin)
        (origin / "README.md").write_text("docs\n", encoding="utf-8")
        _run_git(["add", "-A"], origin)
        _run_git(["commit", "--quiet", "-m", "docs"], origin)
        head = _run_git(["rev-parse", "HEAD"], origin)

        def run(label, cwd, event, base_sha, head_sha, extra_env=None):
            output_file = root / f"{label}.out"
            output_file.write_text("", encoding="utf-8")
            env = os.environ.copy()
            env["GITHUB_OUTPUT"] = str(output_file)
            env.update(extra_env or {})
            subprocess.run(
                ["bash", str(classifier), event, base_sha, head_sha],
                cwd=cwd, env=env, check=True,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            )
            return _parse_outputs(output_file)

        # 1. Unknown event and unknown commits retain full verification.
        _assert_full_scope("unknown-event", run("e", origin, "workflow_run", base, head))
        _assert_full_scope("bad-sha", run("b", origin, "pull_request", "0" * 40 + "1", head))

        # 2. An unexpected runtime error (mktemp failing) must trip the ERR trap.
        fake_bin = root / "bin"
        fake_bin.mkdir()
        fake = fake_bin / "mktemp"
        fake.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
        fake.chmod(0o755)
        _assert_full_scope("runtime-error", run(
            "r", origin, "pull_request", base, head,
            {"PATH": f"{fake_bin}{os.pathsep}{os.environ['PATH']}"},
        ))

        # 3. A depth-1 clone lacks the base commit; the classifier must fetch it.
        shallow = root / "shallow"
        subprocess.run(
            ["git", "clone", "--quiet", "--depth=1", f"file://{origin}", str(shallow)],
            check=True,
        )
        shallow_scope = run("s", shallow, "pull_request", base, head)
        if shallow_scope.get("docs_only") != "true":
            raise AssertionError(f"Shallow-checkout case misclassified a docs change: {shallow_scope}")


def _commit_all(cwd: Path, message: str) -> str:
    _run_git(["add", "-A"], cwd)
    _run_git(["commit", "--quiet", "--allow-empty", "-m", message], cwd)
    return _run_git(["rev-parse", "HEAD"], cwd)


def _init_repo(path: Path) -> None:
    _run_git(["init", "--quiet", "-b", "main"], path)
    _run_git(["config", "user.name", "Estatia CI"], path)
    _run_git(["config", "user.email", "ci@example.invalid"], path)


def _classify(classifier: Path, cwd: Path, base: str, head: str, event: str = "pull_request") -> dict:
    output_file = cwd / "scope.out"
    output_file.write_text("", encoding="utf-8")
    env = os.environ.copy()
    env["GITHUB_OUTPUT"] = str(output_file)
    subprocess.run(
        ["bash", str(classifier), event, base, head],
        cwd=cwd, env=env, check=True,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    return _parse_outputs(output_file)


def test_change_scope_renames_and_diverged_base() -> None:
    """A moved source file must stay visible; main moving on must not inflate scope."""
    classifier = ROOT / ".github" / "scripts" / "detect-change-scope.sh"

    with tempfile.TemporaryDirectory(prefix="estatia-scope-rename-") as temporary:
        repo = Path(temporary)
        _init_repo(repo)
        source = repo / "feature" / "a" / "src" / "main" / "java" / "Foo.kt"
        source.parent.mkdir(parents=True)
        source.write_text("// original source\n" * 30, encoding="utf-8")
        base = _commit_all(repo, "base")

        # Rename detection would list only docs/Foo.md and classify this as docs-only.
        (repo / "docs").mkdir()
        _run_git(["mv", "feature/a/src/main/java/Foo.kt", "docs/Foo.md"], repo)
        head = _commit_all(repo, "move source into docs")
        scope = _classify(classifier, repo, base, head)
        if scope.get("docs_only") != "false" or scope.get("source_changed") != "true":
            raise AssertionError(f"Rename out of source hid the deleted source file: {scope}")

    with tempfile.TemporaryDirectory(prefix="estatia-scope-diverged-") as temporary:
        repo = Path(temporary)
        _init_repo(repo)
        (repo / "seed").write_text("base\n", encoding="utf-8")
        branch_point = _commit_all(repo, "branch point")

        _run_git(["checkout", "--quiet", "-b", "feature"], repo)
        (repo / "README.md").write_text("docs only change\n", encoding="utf-8")
        feature_head = _commit_all(repo, "docs change on the feature branch")

        _run_git(["checkout", "--quiet", "main"], repo)
        unrelated = repo / "core" / "x" / "src" / "main" / "java" / "Unrelated.kt"
        unrelated.parent.mkdir(parents=True)
        unrelated.write_text("// landed on main after the branch point\n", encoding="utf-8")
        main_tip = _commit_all(repo, "unrelated source change on main")
        if main_tip == branch_point:
            raise AssertionError("Test setup failed: main did not advance.")

        scope = _classify(classifier, repo, main_tip, feature_head)
        if scope.get("docs_only") != "true" or scope.get("source_changed") != "false":
            raise AssertionError(
                "A docs-only PR was inflated by unrelated changes that landed on main: " f"{scope}"
            )

        # The tooling validator must classify the exact same diverged range as the
        # shared classifier, rather than including changes introduced on main.
        validation_paths = changed_paths(main_tip, feature_head, cwd=repo)
        if validation_paths != ["README.md"]:
            raise AssertionError(
                "Tooling validation included unrelated changes from main: "
                f"{validation_paths}"
            )


def test_ci_gate() -> None:
    """The aggregate gate passes only on success/skipped and fails closed otherwise."""
    gate = ROOT / ".github" / "scripts" / "ci-gate.py"
    cases = [
        ("all-success", {"a": {"result": "success"}, "b": {"result": "skipped"}}, 0),
        ("failure", {"a": {"result": "success"}, "b": {"result": "failure"}}, 1),
        ("cancelled", {"a": {"result": "cancelled"}}, 1),
        ("missing-result", {"a": {}}, 1),
        ("empty", {}, 1),
    ]
    for label, needs, expected in cases:
        env = os.environ.copy()
        env["CI_JOB_RESULTS"] = json.dumps(needs)
        code = subprocess.run(
            [sys.executable, str(gate)], env=env,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        ).returncode
        if code != expected:
            raise AssertionError(f"CI gate case {label}: expected exit {expected}, got {code}")


def test_diagnostics_wrapper_pipe() -> None:
    """The resource sampler must not hold the output pipe open after Gradle exits."""
    wrapper = ROOT / ".github" / "scripts" / "run-gradle-with-diagnostics.sh"
    with tempfile.TemporaryDirectory(prefix="estatia-wrapper-") as temporary:
        root = Path(temporary)
        fake = root / "gradlew"
        fake.write_text("#!/usr/bin/env bash\necho fake-gradle\nsleep 1\n", encoding="utf-8")
        fake.chmod(0o755)
        env = os.environ.copy()
        env.update({"RUNNER_TEMP": str(root / "runner"), "GRADLE_CI_SAMPLE_SECONDS": "30"})
        env.pop("GITHUB_STEP_SUMMARY", None)
        started = time.monotonic()
        # communicate() waits for EOF on the pipe, exactly like `| tee` does.
        process = subprocess.Popen(
            ["bash", str(wrapper), "pipe-test", "./gradlew", "build"],
            cwd=root, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        )
        output, _ = process.communicate(timeout=120)
        elapsed = time.monotonic() - started
        if process.returncode != 0 or "fake-gradle" not in output:
            raise AssertionError(f"Wrapper did not pass Gradle through: {output}")
        if elapsed > 15:
            raise AssertionError(f"Wrapper held the output pipe open for {elapsed:.1f}s after Gradle exited.")


def test_scrub_check_secrets() -> None:
    """Credential scrubbing removes leaks and fails closed when scanning fails."""
    scrubber = ROOT / ".github" / "scripts" / "scrub-check-secrets.sh"
    secret = "ci-secret-9A%/"
    with tempfile.TemporaryDirectory(prefix="estatia-secret-scrub-") as temporary:
        root = Path(temporary)
        artifacts = root / "artifacts"
        source_profiles = root / "source-profiles"
        artifacts.mkdir()
        source_profiles.mkdir()
        encoded = __import__("base64").b64encode(secret.encode("utf-8")).decode("ascii")
        percent_encoded = __import__("urllib.parse", fromlist=["quote"]).quote(secret, safe="")
        leaked_files = {
            artifacts / "raw.txt": secret,
            artifacts / "base64.txt": encoded,
            source_profiles / "percent-encoded.txt": percent_encoded,
        }
        for path, content in leaked_files.items():
            path.write_text(content, encoding="utf-8")
        clean_file = artifacts / "clean.txt"
        clean_file.write_text("no credential material here\n", encoding="utf-8")

        environment = os.environ.copy()
        environment["SCRUB_TEST_SECRET"] = secret
        command = [
            "bash", str(scrubber), "--dirs", str(artifacts), str(source_profiles),
            "--", "SCRUB_TEST_SECRET",
        ]
        detected = subprocess.run(
            command, env=environment, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True,
        )
        if detected.returncode != 1:
            raise AssertionError(
                f"Secret scrub should fail after deleting detected leaks: {detected.stdout}"
            )
        remaining = [str(path) for path in leaked_files if path.exists()]
        if remaining:
            raise AssertionError(f"Secret-bearing artifact files were not removed: {remaining}")
        if not clean_file.is_file() or secret in detected.stdout:
            raise AssertionError("Scrubber removed clean data or exposed the secret in its output.")

        clean = subprocess.run(
            command, env=environment, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True,
        )
        if clean.returncode != 0 or not clean_file.is_file():
            raise AssertionError(f"Clean artifact scan did not pass: {clean.stdout}")

        # An unreadable scanner must not be interpreted as 'no matches'.
        failing_bin = root / "failing-bin"
        failing_bin.mkdir()
        fake_grep = failing_bin / "grep"
        fake_grep.write_text("#!/usr/bin/env bash\nexit 2\n", encoding="utf-8")
        fake_grep.chmod(0o755)
        failing_artifact = artifacts / "scanner-error.txt"
        failing_artifact.write_text("artifact data\n", encoding="utf-8")
        failing_environment = environment.copy()
        failing_environment["PATH"] = f"{failing_bin}{os.pathsep}{environment['PATH']}"
        failed_scan = subprocess.run(
            command, env=failing_environment, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True,
        )
        if failed_scan.returncode == 0 or not failing_artifact.is_file():
            raise AssertionError(
                "Scanner errors must fail closed and must not permit artifact upload."
            )


def test_batch_branch_cleanup_parser() -> None:
    """Run branch-cleanup parser and read-only-mode safety tests in PR validation."""
    tests = ROOT / ".github" / "scripts" / "tests" / "test_delete_branches.py"
    if not tests.is_file():
        return
    subprocess.run(
        [
            sys.executable,
            "-m",
            "unittest",
            "discover",
            "-s",
            ".github/scripts/tests",
            "-p",
            "test_delete_branches.py",
            "-v",
        ],
        cwd=ROOT,
        check=True,
    )



def test_orphaned_branch_audit() -> None:
    """Exercise ancestry, cherry-pick, squash-merge, and carried-forward detection."""
    tests = ROOT / ".github" / "scripts" / "tests" / "test_audit_orphaned_branches.py"
    if not tests.is_file():
        return
    subprocess.run(
        [
            sys.executable,
            "-m",
            "unittest",
            "discover",
            "-s",
            ".github/scripts/tests",
            "-p",
            "test_audit_orphaned_branches.py",
            "-v",
        ],
        cwd=ROOT,
        check=True,
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
        test_change_scope_fail_open()
        test_change_scope_renames_and_diverged_base()
        print("Change-scope classifier self-tests passed (path categories, fail-open, shallow checkout, renames, diverged base).")
        test_ci_gate()
        print("CI gate self-tests passed.")
        test_diagnostics_wrapper_pipe()
        print("Diagnostics wrapper pipe self-test passed.")
        test_scrub_check_secrets()
        print("Credential scrubber leak-detection and fail-closed self-tests passed.")
        test_batch_branch_cleanup_parser()
        print("Batch branch cleanup parser and read-only-mode safety tests passed.")
        test_orphaned_branch_audit()
        print("Orphaned branch audit ancestry, PR-history, patch-equivalence, and carry-forward tests passed.")
    except (AssertionError, OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print(f"::error::Tooling self-test failed: {error}", file=sys.stderr)
        return 1

    print(f"Tooling validation passed: {len(selected)} changed tooling/configuration file(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
