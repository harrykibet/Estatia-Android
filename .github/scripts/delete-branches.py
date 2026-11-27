#!/usr/bin/env python3
"""Safely inventory remote branches or delete explicitly selected GitHub branches.

Dry-run is the default. Use --execute only after reviewing an explicit preview.
Input may be positional, repeated --branch values, a JSON array, or newline-delimited stdin.
Use --all-branches for a forced read-only inventory of every remote branch.
Blank lines and lines beginning with # are ignored; wildcards are unsupported.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

API = "https://api.github.com"
RETRYABLE = {429, 500, 502, 503, 504}


class APIError(RuntimeError):
    def __init__(self, status: int, message: str, path: str) -> None:
        super().__init__(f"GitHub API {status} for {path}: {message}")
        self.status = status


class GitHub:
    def __init__(self, token: str) -> None:
        self.token = token

    def request(self, method: str, path: str) -> Any:
        headers = {
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {self.token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "estatia-batch-branch-cleanup",
        }
        for attempt in range(4):
            request = Request(API + path, headers=headers, method=method)
            try:
                with urlopen(request, timeout=30) as response:
                    raw = response.read()
                    return json.loads(raw.decode("utf-8")) if raw else None
            except HTTPError as error:
                raw = error.read()
                try:
                    payload = json.loads(raw.decode("utf-8")) if raw else {}
                except json.JSONDecodeError:
                    payload = {"message": raw.decode("utf-8", errors="replace")}
                if error.code in RETRYABLE and attempt < 3:
                    retry = error.headers.get("Retry-After", "")
                    delay = int(retry) if retry.isdigit() else 2 ** attempt
                    time.sleep(min(delay, 20))
                    continue
                message = payload.get("message", str(error)) if isinstance(payload, dict) else str(error)
                raise APIError(error.code, str(message), path) from error
            except URLError as error:
                if attempt < 3:
                    time.sleep(2 ** attempt)
                    continue
                raise RuntimeError(f"Network error calling {path}: {error}") from error
        raise RuntimeError(f"Retries exhausted for {method} {path}")

    def get(self, path: str) -> Any:
        return self.request("GET", path)

    def delete(self, path: str) -> None:
        self.request("DELETE", path)

    def pages(self, path: str) -> list[dict[str, Any]]:
        items: list[dict[str, Any]] = []
        page = 1
        while True:
            separator = "&" if "?" in path else "?"
            result = self.get(f"{path}{separator}per_page=100&page={page}")
            if not isinstance(result, list):
                raise RuntimeError(f"Expected an array response from {path}")
            items.extend(item for item in result if isinstance(item, dict))
            if len(result) < 100:
                return items
            page += 1


@dataclass
class Outcome:
    branch: str
    status: str
    detail: str


def validate_branch(name: str) -> None:
    result = subprocess.run(
        ["git", "check-ref-format", "--branch", name],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    if result.returncode:
        raise ValueError(f"Invalid Git branch name: {name!r}")


def parse_names(values: list[str], stdin_text: str | None) -> list[str]:
    raw = list(values)
    if stdin_text is not None:
        submitted = stdin_text.strip()
        if submitted:
            if submitted.startswith(("[", "{")):
                try:
                    parsed = json.loads(submitted)
                except json.JSONDecodeError as error:
                    raise ValueError(
                        "Branch input must be a JSON array of strings or one branch name per line."
                    ) from error
                if not isinstance(parsed, list):
                    raise ValueError("JSON branch input must be an array of strings.")
                if not all(isinstance(item, str) for item in parsed):
                    raise ValueError("Every item in the JSON branch array must be a string.")
                raw.extend(parsed)
            else:
                raw.extend(submitted.splitlines())
    names: list[str] = []
    seen: set[str] = set()
    for value in raw:
        name = value.strip()
        if not name or name.startswith("#"):
            continue
        if any(char.isspace() for char in name):
            raise ValueError(f"Branch names cannot contain whitespace: {value!r}")
        validate_branch(name)
        if name not in seen:
            seen.add(name)
            names.append(name)
    return names


def list_remote_branches(api: GitHub, repo: str) -> list[str]:
    """Return every remote branch name, failing closed on an incomplete response."""
    records = api.pages(f"/repos/{repo}/branches")
    names: list[str] = []
    for record in records:
        name = record.get("name")
        if not isinstance(name, str) or not name:
            raise RuntimeError("GitHub returned a branch record without a valid name.")
        names.append(name)
    if not names:
        raise RuntimeError("GitHub returned no branches; refusing to produce an empty inventory.")
    return parse_names(names, None)


def read_protected(path: str | None) -> set[str]:
    if not path:
        return set()
    file_path = Path(path)
    if not file_path.is_file():
        raise FileNotFoundError(f"Protected-branches file not found: {file_path}")
    protected: set[str] = set()
    for line in file_path.read_text(encoding="utf-8").splitlines():
        value = line.strip()
        if value and not value.startswith("#"):
            validate_branch(value)
            protected.add(value)
    return protected


def write_summary(
    repo: str,
    dry_run: bool,
    outcomes: list[Outcome],
    *,
    full_inventory: bool = False,
) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        return
    if full_inventory:
        title = "Full remote branch inventory (forced dry run)"
    else:
        title = "Branch deletion dry run" if dry_run else "Branch deletion results"
    lines = [
        f"## {title}", "", f"Repository: {repo}", "",
        "| Branch | Result | Details |", "|---|---|---|",
    ]
    for item in outcomes:
        detail = item.detail.replace("|", "\\|").replace("\n", " ")
        lines.append(f"| {item.branch} | **{item.status}** | {detail} |")
    lines += [
        "",
        f"Requested: **{len(outcomes)}** · "
        f"Deleted: **{sum(x.status == 'DELETED' for x in outcomes)}** · "
        f"Would delete: **{sum(x.status == 'WOULD_DELETE' for x in outcomes)}** · "
        f"Review required: **{sum(x.status == 'REVIEW_REQUIRED' for x in outcomes)}** · "
        f"Skipped: **{sum(x.status == 'SKIPPED' for x in outcomes)}** · "
        f"Failed: **{sum(x.status == 'FAILED' for x in outcomes)}**",
        "",
    ]
    with open(summary_path, "a", encoding="utf-8") as file:
        file.write("\n".join(lines))


def run(args: argparse.Namespace) -> int:
    repo = args.repo or os.environ.get("GITHUB_REPOSITORY")
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not repo or repo.count("/") != 1:
        print("ERROR: Set GITHUB_REPOSITORY or pass --repo owner/name.", file=sys.stderr)
        return 2
    if not token:
        print("ERROR: GITHUB_TOKEN is required (contents:read for inventory; contents:write for --execute; pull-requests:read).", file=sys.stderr)
        return 2
    try:
        supplied_names = parse_names(
            args.branches + args.branch_options,
            sys.stdin.read() if args.from_stdin else None,
        )
        protected = read_protected(args.protect_file) | set(args.protect_branch)
        for name in args.protect_branch:
            validate_branch(name)
        if args.all_branches and supplied_names:
            raise ValueError("--all-branches cannot be combined with explicit branch names.")
        if args.all_branches and args.execute:
            raise ValueError("--all-branches is permanently dry-run; --execute is not allowed.")
        if not args.all_branches and not supplied_names:
            raise ValueError("No branch names supplied. Supply branches or use --all-branches.")
    except (ValueError, FileNotFoundError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2

    api = GitHub(token)
    try:
        repository = api.get(f"/repos/{repo}")
        default_branch = repository.get("default_branch")
        if not default_branch:
            raise RuntimeError("GitHub did not return the default branch.")
        open_prs = api.pages(f"/repos/{repo}/pulls?state=open")
        open_heads = {
            pr["head"]["ref"] for pr in open_prs
            if isinstance(pr.get("head"), dict) and pr["head"].get("ref")
        }
        names = list_remote_branches(api, repo) if args.all_branches else supplied_names
    except Exception as error:
        print(f"ERROR: Unable to load safety metadata; no branches deleted: {error}", file=sys.stderr)
        return 2

    dry_run = args.all_branches or not args.execute
    print(f"Repository: {repo}\nDefault branch: {default_branch}")
    if args.all_branches:
        print("Mode: FULL REMOTE BRANCH INVENTORY (FORCED DRY RUN)")
        print(f"Remote branches discovered: {len(names)}")
    else:
        print(f"Mode: {'DRY RUN' if dry_run else 'EXECUTE'}")
    print(f"Open PR head branches protected: {len(open_heads)}\n")

    outcomes: list[Outcome] = []
    for branch in names:
        if branch == default_branch:
            outcome = Outcome(branch, "SKIPPED", "Repository default branch.")
        elif branch in protected:
            outcome = Outcome(branch, "SKIPPED", "Protected by repository policy.")
        elif branch in open_heads:
            outcome = Outcome(branch, "SKIPPED", "Head branch of an open pull request.")
        else:
            # Encode slashes as %2F because GitHub treats a branch as one path parameter.
            encoded = quote(branch, safe="")
            try:
                data = api.get(f"/repos/{repo}/branches/{encoded}")
                if data.get("protected") is True:
                    outcome = Outcome(branch, "SKIPPED", "GitHub reports this branch as protected.")
                elif dry_run and args.all_branches:
                    outcome = Outcome(
                        branch,
                        "REVIEW_REQUIRED",
                        "Policy checks passed; inspect commit history and unique work before explicitly selecting this branch for deletion.",
                    )
                elif dry_run:
                    outcome = Outcome(branch, "WOULD_DELETE", "Safety checks passed; no ref changed.")
                else:
                    api.delete(f"/repos/{repo}/git/refs/heads/{encoded}")
                    outcome = Outcome(branch, "DELETED", "GitHub accepted the ref deletion.")
            except APIError as error:
                if error.status == 404:
                    outcome = Outcome(branch, "SKIPPED", "Branch does not exist or was already deleted.")
                else:
                    outcome = Outcome(branch, "FAILED", str(error))
            except Exception as error:
                outcome = Outcome(branch, "FAILED", str(error))
        outcomes.append(outcome)
        print(f"{outcome.status:12} {branch} - {outcome.detail}")

    write_summary(repo, dry_run, outcomes, full_inventory=args.all_branches)
    deleted = sum(x.status == "DELETED" for x in outcomes)
    preview = sum(x.status == "WOULD_DELETE" for x in outcomes)
    review_required = sum(x.status == "REVIEW_REQUIRED" for x in outcomes)
    skipped = sum(x.status == "SKIPPED" for x in outcomes)
    failed = sum(x.status == "FAILED" for x in outcomes)
    print(
        f"\nSummary: requested={len(outcomes)}, deleted={deleted}, "
        f"would_delete={preview}, review_required={review_required}, "
        f"skipped={skipped}, failed={failed}"
    )
    return 1 if failed else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("branches", nargs="*", help="Exact branch names to delete.")
    parser.add_argument("--branch", dest="branch_options", action="append", default=[],
                        help="Add an exact branch name; may be repeated.")
    parser.add_argument("--protect-branch", dest="protect_branch", action="append", default=[],
                        help="Additional protected branch; may be repeated.")
    parser.add_argument("--from-stdin", action="store_true", help="Read a JSON array or newline-delimited branch names from stdin.")
    parser.add_argument(
        "--all-branches",
        action="store_true",
        help="Enumerate every remote branch and produce a forced dry-run inventory; cannot be combined with branch names or --execute.",
    )
    parser.add_argument("--repo", help="Repository owner/name; defaults to GITHUB_REPOSITORY.")
    parser.add_argument("--protect-file", help="File of additional protected branch names.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--execute", action="store_true", help="Delete refs; without this option the script is a dry run.")
    mode.add_argument("--dry-run", action="store_true", help="Explicitly request a dry run (the default).")
    return run(parser.parse_args())


if __name__ == "__main__":
    raise SystemExit(main())
