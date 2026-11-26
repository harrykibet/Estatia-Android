#!/usr/bin/env python3
"""Publish validated nightly evidence to the checked-out main worktree."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET


EXPECTED_PROFILES = {
    (flavor, name)
    for flavor in ("demoRelease", "prodRelease")
    for name in ("baseline-prof.txt", "startup-prof.txt")
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def append_jsonl(
    path: Path,
    rows: list[dict],
    unique_fields: tuple[str, ...],
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    existing: set[tuple[str, ...]] = set()
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            try:
                item = json.loads(line)
            except json.JSONDecodeError as error:
                raise SystemExit(f"Malformed history JSONL {path}: {error}") from error
            existing.add(tuple(str(item.get(field, "")) for field in unique_fields))

    with path.open("a", encoding="utf-8") as stream:
        for row in rows:
            key = tuple(str(row.get(field, "")) for field in unique_fields)
            if key in existing:
                continue
            stream.write(json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n")
            existing.add(key)


def publish_profiles(artifacts: Path, repo: Path, binaries_verified: bool) -> dict:
    root = artifacts / "profiles"
    if not root.is_dir():
        return {"status": "not-produced", "files": 0}
    if not binaries_verified:
        return {
            "status": "held",
            "files": 0,
            "reason": "both signed release APKs and their packaged profiles were not verified",
        }

    found: dict[tuple[str, str], Path] = {}
    for path in root.rglob("*.txt"):
        if path.name not in ("baseline-prof.txt", "startup-prof.txt"):
            continue
        parts = path.relative_to(root).parts
        has_profile_directory = any(
            parts[index:index + 2] == ("generated", "baselineProfiles")
            for index in range(max(0, len(parts) - 1))
        )
        if not has_profile_directory:
            continue
        flavor = next(
            (part for part in parts if part in ("demoRelease", "prodRelease")),
            None,
        )
        if flavor is None:
            continue
        key = (flavor, path.name)
        if key in found and found[key].read_bytes() != path.read_bytes():
            raise SystemExit(f"Conflicting downloaded profile contents for {key}")
        found[key] = path

    if not found:
        return {"status": "not-produced", "files": 0}
    if set(found) != EXPECTED_PROFILES:
        missing = sorted(EXPECTED_PROFILES - set(found))
        raise SystemExit(
            "Incomplete Baseline Profile artifact; refusing partial publication: "
            + ", ".join(f"{flavor}/{name}" for flavor, name in missing)
        )

    published = []
    for (flavor, name), source in sorted(found.items()):
        content = source.read_text(encoding="utf-8")
        rules = [
            line
            for line in content.splitlines()
            if line.strip() and not line.lstrip().startswith(("#", "//"))
        ]
        if not rules:
            raise SystemExit(f"Refusing to publish a profile without rules: {source}")
        target = (
            repo
            / "app/src"
            / flavor
            / "generated/baselineProfiles"
            / name
        )
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        published.append({
            "variant": flavor,
            "file": name,
            "bytes": target.stat().st_size,
            "sha256": sha256(target),
            "rules": len(rules),
        })

    return {
        "status": "published",
        "files": len(published),
        "profiles": published,
    }


def parse_counter(root: ET.Element, name: str, required: bool) -> dict | None:
    counter = next(
        (
            item
            for item in root.findall("counter")
            if item.attrib.get("type") == name
        ),
        None,
    )
    if counter is None:
        if required:
            raise ValueError(f"JaCoCo XML has no {name} counter")
        return None

    try:
        covered = int(counter.attrib["covered"])
        missed = int(counter.attrib["missed"])
    except (KeyError, ValueError) as error:
        raise ValueError(f"Invalid {name} counter: {error}") from error

    total = covered + missed
    if covered < 0 or missed < 0 or (required and total <= 0):
        raise ValueError(
            f"Invalid or empty {name} counter: covered={covered}, missed={missed}"
        )
    return {
        "covered": covered,
        "missed": missed,
        "total": total,
        "percent": round(covered * 100.0 / total, 2) if total else None,
    }


def publish_jacoco(
    artifacts: Path,
    repo: Path,
    run_id: int,
    run_number: int,
    source_sha: str,
) -> dict:
    root = artifacts / "quality"
    xml_files = []
    if root.is_dir():
        for path in root.rglob("*.xml"):
            parts = path.relative_to(root).parts
            try:
                marker = parts.index("build")
            except ValueError:
                continue
            if parts[marker:marker + 3] == ("build", "reports", "jacoco"):
                xml_files.append(path)

    if not xml_files:
        return {"status": "not-produced", "modules": 0}

    evidence = repo / "ci/evidence/nightly"
    latest = evidence / "latest"
    latest.mkdir(parents=True, exist_ok=True)
    stage = latest / f".jacoco-staging-{run_id}"
    shutil.rmtree(stage, ignore_errors=True)
    stage.mkdir(parents=True, exist_ok=True)

    rows = []
    for xml_path in sorted(xml_files):
        relative = xml_path.relative_to(root)
        parts = relative.parts
        marker = parts.index("build")
        module_parts = parts[:marker]
        module = "/".join(module_parts) if module_parts else "root"
        try:
            report = ET.parse(xml_path).getroot()
        except (ET.ParseError, OSError) as error:
            raise ValueError(f"Invalid JaCoCo XML {xml_path}: {error}") from error
        if report.tag != "report":
            raise ValueError(
                f"Unexpected JaCoCo XML root in {xml_path}: {report.tag}"
            )

        line = parse_counter(report, "LINE", required=True)
        branch = parse_counter(report, "BRANCH", required=False)
        report_dir = xml_path.parent
        target = stage / module / report_dir.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(report_dir, target, dirs_exist_ok=True)
        rows.append({
            "workflow_run_id": run_id,
            "run_number": run_number,
            "source_commit": source_sha,
            "module": module,
            "report": str(
                Path("ci/evidence/nightly/latest/jacoco")
                / module
                / report_dir.name
            ),
            "line": line,
            "branch": branch,
        })

    published = latest / "jacoco"
    backup = latest / f".jacoco-previous-{run_id}"
    shutil.rmtree(backup, ignore_errors=True)
    if published.exists():
        published.rename(backup)
    try:
        stage.rename(published)
    except Exception:
        if backup.exists() and not published.exists():
            backup.rename(published)
        raise
    shutil.rmtree(backup, ignore_errors=True)

    append_jsonl(
        evidence / "coverage-history.jsonl",
        rows,
        ("workflow_run_id", "module"),
    )
    (latest / "coverage-summary.json").write_text(
        json.dumps({
            "workflow_run_id": run_id,
            "workflow_run_number": run_number,
            "source_commit": source_sha,
            "modules": rows,
        }, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return {"status": "published", "modules": len(rows)}


def publish_benchmarks(
    artifacts: Path,
    repo: Path,
    run_id: int,
    run_number: int,
    source_sha: str,
) -> dict:
    root = artifacts / "benchmarks"
    files = sorted(root.rglob("benchmarkData.json")) if root.is_dir() else []
    if not files:
        return {"status": "not-produced", "files": 0}

    evidence = repo / "ci/evidence/nightly"
    latest = evidence / "latest"
    latest.mkdir(parents=True, exist_ok=True)
    stage = latest / f".benchmarks-staging-{run_id}"
    shutil.rmtree(stage, ignore_errors=True)
    stage.mkdir(parents=True, exist_ok=True)
    rows = []

    for index, path in enumerate(files):
        relative = path.relative_to(root)
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ValueError(f"Invalid Macrobenchmark JSON {path}: {error}") from error
        destination = stage / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
        for benchmark_index, benchmark in enumerate(payload.get("benchmarks", [])):
            rows.append({
                "workflow_run_id": run_id,
                "run_number": run_number,
                "source_commit": source_sha,
                "source_file": relative.as_posix(),
                "benchmark_index": f"{index}:{benchmark_index}",
                "benchmark": benchmark,
            })

    published = latest / "benchmarks"
    backup = latest / f".benchmarks-previous-{run_id}"
    shutil.rmtree(backup, ignore_errors=True)
    if published.exists():
        published.rename(backup)
    try:
        stage.rename(published)
    except Exception:
        if backup.exists() and not published.exists():
            backup.rename(published)
        raise
    shutil.rmtree(backup, ignore_errors=True)

    append_jsonl(
        evidence / "benchmark-history.jsonl",
        rows,
        ("workflow_run_id", "source_file", "benchmark_index"),
    )
    return {
        "status": "published",
        "files": len(files),
        "measurements": len(rows),
    }


def verify_binary_artifact(artifacts: Path) -> dict:
    root = artifacts / "binaries"
    manifests = sorted(root.rglob("release-manifest.json")) if root.is_dir() else []
    if not manifests:
        return {"status": "not-produced"}
    if len(manifests) != 1:
        raise ValueError(
            f"Expected one release manifest in binary artifact, found {len(manifests)}"
        )

    manifest_path = manifests[0]
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Invalid release manifest: {error}") from error

    variants = manifest.get("variants", {})
    expected = {"demoRelease", "prodRelease"}
    if set(variants) != expected:
        raise ValueError("Release manifest must contain Demo and Prod variants only.")

    for flavor in sorted(expected):
        item = variants[flavor]
        if not isinstance(item, dict):
            raise ValueError(f"Release manifest entry is invalid for {flavor}.")
        name = item.get("apk")
        if not isinstance(name, str) or Path(name).name != name:
            raise ValueError(f"Unsafe APK path in release manifest for {flavor}.")
        apk = manifest_path.parent / name
        if not apk.is_file() or apk.stat().st_size != item.get("apk_bytes"):
            raise ValueError(f"Release APK is missing or has unexpected size: {apk}")
        if sha256(apk) != item.get("apk_sha256"):
            raise ValueError(f"Release APK SHA-256 does not match manifest: {apk}")
        if item.get("signed") is not True:
            raise ValueError(f"Release APK is not marked signed: {apk}")

    return {
        "status": "verified-artifact",
        "variants": sorted(expected),
        "binary_kind": manifest.get("binary_kind"),
    }


def attempt(label: str, operation, *args) -> dict:
    try:
        return operation(*args)
    except (SystemExit, Exception) as error:
        message = str(error)
        print(f"::warning::Could not publish {label}: {message}")
        return {"status": "invalid-or-unavailable", "error": message}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifacts-root", required=True, type=Path)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--run-id", required=True, type=int)
    parser.add_argument("--run-number", required=True, type=int)
    args = parser.parse_args()

    repo = Path.cwd()
    evidence = repo / "ci/evidence/nightly"
    latest = evidence / "latest"
    latest.mkdir(parents=True, exist_ok=True)
    artifacts = args.artifacts_root

    binaries = attempt("release binaries", verify_binary_artifact, artifacts)
    profiles = attempt(
        "Baseline Profiles",
        publish_profiles,
        artifacts,
        repo,
        binaries.get("status") == "verified-artifact",
    )
    jacoco = attempt(
        "JaCoCo reports",
        publish_jacoco,
        artifacts,
        repo,
        args.run_id,
        args.run_number,
        args.source_sha,
    )
    benchmarks = attempt(
        "Macrobenchmark measurements",
        publish_benchmarks,
        artifacts,
        repo,
        args.run_id,
        args.run_number,
        args.source_sha,
    )

    # A malformed report can leave a staging directory behind. Never commit
    # staging/backup directories as durable evidence or discard the old snapshot.
    for staging_name, published_name, backup_name in (
        (
            f".jacoco-staging-{args.run_id}",
            "jacoco",
            f".jacoco-previous-{args.run_id}",
        ),
        (
            f".benchmarks-staging-{args.run_id}",
            "benchmarks",
            f".benchmarks-previous-{args.run_id}",
        ),
    ):
        staging = latest / staging_name
        backup = latest / backup_name
        published = latest / published_name
        if staging.exists():
            shutil.rmtree(staging, ignore_errors=True)
        if backup.exists():
            if not published.exists():
                backup.rename(published)
            else:
                shutil.rmtree(backup, ignore_errors=True)

    try:
        producer_results = json.loads(os.environ.get("NIGHTLY_RESULTS", "{}"))
    except json.JSONDecodeError as error:
        producer_results = {"parse_error": str(error)}

    run_url = (
        f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/"
        f"{os.environ.get('GITHUB_REPOSITORY', '')}/actions/runs/{args.run_id}"
    )
    manifest = {
        "schema_version": 1,
        "workflow_run_id": args.run_id,
        "workflow_run_number": args.run_number,
        "workflow_run_url": run_url,
        "source_commit": args.source_sha,
        "producer_results": producer_results,
        "published": {
            "baseline_profiles": profiles,
            "jacoco": jacoco,
            "macrobenchmarks": benchmarks,
            "release_binaries": binaries,
        },
    }
    (latest / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    summary = [
        "# Nightly evidence publication",
        "",
        f"- Run: {args.run_number} ({run_url})",
        f"- Source commit: {args.source_sha}",
        f"- Baseline Profiles: {profiles.get('status')}",
        f"- JaCoCo reports: {jacoco.get('status')} ({jacoco.get('modules', 0)} modules)",
        f"- Macrobenchmarks: {benchmarks.get('status')} ({benchmarks.get('files', 0)} files)",
        f"- Release APKs: {binaries.get('status')}",
        "",
        "Release APKs remain run-scoped artifacts; only source profiles and compact evidence are committed to main.",
    ]
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with Path(summary_path).open("a", encoding="utf-8") as stream:
            stream.write("\n".join(summary) + "\n")
    print("\n".join(summary))


if __name__ == "__main__":
    main()
