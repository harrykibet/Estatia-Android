#!/usr/bin/env python3
"""Verify signed Demo/Prod release APKs and emit an immutable run manifest."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def count_profile_rules(path: Path) -> int:
    return sum(
        1
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith(("#", "//"))
    )


def locate_apksigner() -> Path:
    sdk = Path(os.environ["ANDROID_HOME"])
    build_tools = sdk / "build-tools"
    versions = sorted(
        (entry for entry in build_tools.iterdir() if entry.is_dir()),
        key=lambda entry: tuple(int(part) for part in entry.name.split(".")),
    )
    if not versions:
        raise SystemExit("No Android SDK build-tools directory was found.")
    apksigner = versions[-1] / "apksigner"
    if not apksigner.is_file():
        raise SystemExit(f"apksigner executable is missing: {apksigner}")
    return apksigner


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--run-id", required=True, type=int)
    parser.add_argument("--run-number", required=True, type=int)
    args = parser.parse_args()

    bundle = args.bundle
    bundle.mkdir(parents=True, exist_ok=True)
    apksigner = locate_apksigner()
    variants: dict[str, dict[str, object]] = {}
    source_profiles: dict[str, dict[str, object]] = {}

    for flavor in ("demo", "prod"):
        apk = (
            Path("app/build/outputs/apk")
            / flavor
            / "release"
            / f"app-{flavor}-release.apk"
        )
        if not apk.is_file() or apk.stat().st_size == 0:
            raise SystemExit(f"Signed {flavor} release APK is missing or empty: {apk}")

        subprocess.run([str(apksigner), "verify", str(apk)], check=True)
        with zipfile.ZipFile(apk) as archive:
            bad_entry = archive.testzip()
            if bad_entry:
                raise SystemExit(f"APK ZIP integrity failed for {apk}: {bad_entry}")
            profile_entry = "assets/dexopt/baseline.prof"
            if profile_entry not in archive.namelist():
                raise SystemExit(
                    f"Packaged binary Baseline Profile is missing from {apk}"
                )
            profile_bytes = archive.getinfo(profile_entry).file_size
            if profile_bytes <= 0:
                raise SystemExit(f"Packaged binary Baseline Profile is empty in {apk}")

        target = bundle / f"estatia-{flavor}-release.apk"
        target.write_bytes(apk.read_bytes())
        variants[f"{flavor}Release"] = {
            "apk": target.name,
            "apk_bytes": target.stat().st_size,
            "apk_sha256": sha256(target),
            "signed": True,
            "packaged_profile": profile_entry,
            "packaged_profile_bytes": profile_bytes,
        }

        for profile_name in ("baseline-prof.txt", "startup-prof.txt"):
            profile = (
                Path("app/src")
                / f"{flavor}Release"
                / "generated/baselineProfiles"
                / profile_name
            )
            if not profile.is_file() or profile.stat().st_size == 0:
                raise SystemExit(f"Source Baseline Profile is missing: {profile}")
            rules = count_profile_rules(profile)
            if rules <= 0:
                raise SystemExit(f"Source Baseline Profile contains no rules: {profile}")
            source_profiles[f"{flavor}Release/{profile_name}"] = {
                "bytes": profile.stat().st_size,
                "sha256": sha256(profile),
                "rules": rules,
            }

    manifest = {
        "schema_version": 1,
        "source_commit": args.source_sha,
        "workflow_run_id": args.run_id,
        "workflow_run_number": args.run_number,
        "binary_kind": "signed, minified release APK",
        "variants": variants,
        "source_profiles": source_profiles,
    }
    (bundle / "release-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(manifest, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
