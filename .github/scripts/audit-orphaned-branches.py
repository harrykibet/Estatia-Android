#!/usr/bin/env python3
"""Read-only audit for branch work not accounted for by the default branch.

The audit combines branch topology, stable patch-ID equivalence, and historical
pull-request metadata. It never deletes branches, moves refs, or writes to GitHub.
A successful run means the inventory completed, not that every branch is safe
for deletion.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

API = "https://api.github.com"
RETRYABLE = {429, 500, 502, 503, 504}


class APIError(RuntimeError):
    """GitHub API request failed."""


class GitHub:
    def __init__(self, token: str) -> None:
        self.token = token

    def get(self, path: str) -> Any:
        headers = {
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {self.token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "estatia-orphaned-branch-audit",
        }
        for attempt in range(4):
            request = Request(API + path, headers=headers, method="GET")
            try:
                with urlopen(request, timeout=40) as response:
                    raw = response.read()
                    return json.loads(raw.decode("utf-8")) if raw else None
            except HTTPError as error:
                raw = error.read()
                try:
                    payload = json.loads(raw.decode("utf-8")) if raw else {}
                except json.JSONDecodeError:
                    payload = {"message": raw.decode("utf-8", errors="replace")}
                if error.code in RETRYABLE and attempt < 3:
                    retry_after = error.headers.get("Retry-After", "")
                    delay = int(retry_after) if retry_after.isdigit() else 2 ** attempt
                    time.sleep(min(delay, 20))
                    continue
                message = payload.get("message", str(error)) if isinstance(payload, dict) else str(payload)
                raise APIError(f"GitHub API {error.code} for {path}: {message}") from error
            except URLError as error:
                if attempt < 3:
                    time.sleep(2 ** attempt)
                    continue
                raise APIError(f"Network error calling {path}: {error}") from error
        raise APIError(f"Retries exhausted for {path}")

    def pages(self, path: str) -> list[dict[str, Any]]:
        records: list[dict[str, Any]] = []
        page = 1
        while True:
            separator = "&" if "?" in path else "?"
            result = self.get(f"{path}{separator}per_page=100&page={page}")
            if not isinstance(result, list):
                raise APIError(f"Expected an array response from {path}, page {page}")
            records.extend(item for item in result if isinstance(item, dict))
            if len(result) < 100:
                return records
            page += 1


@dataclass
class BranchResult:
    branch: str
    status: str
    protected: bool
    last_commit_at: str
    age_days: int
    stale: bool
    topology_ahead: int
    unmatched_patches: int
    patch_equivalent_commits: int
    merge_commits: int
    related_prs: list[dict[str, Any]]
    unmatched_commits: list[dict[str, Any]]
    detail: str
    last_commit: str
    commit_sha: str


def git(args: list[str], *, cwd: Path | None = None, check: bool = True) -> str:
    process = subprocess.run(
        ["git", *args], cwd=cwd, check=False, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=True, timeout=120,
    )
    if check and process.returncode:
        message = process.stderr.strip() or process.stdout.strip()
        raise RuntimeError(f"git {' '.join(args)} failed ({process.returncode}): {message}")
    return process.stdout.strip()


def is_ancestor(ancestor: str, descendant: str) -> bool:
    process = subprocess.run(
        ["git", "merge-base", "--is-ancestor", ancestor, descendant],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False, timeout=60,
    )
    if process.returncode == 0:
        return True
    if process.returncode == 1:
        return False
    raise RuntimeError(
        f"git merge-base could not verify ancestry for {ancestor!r} and {descendant!r} "
        f"(exit {process.returncode}); history may be incomplete."
    )


def cherry_lines(base_ref: str, head_ref: str, limit_ref: str | None = None) -> list[str]:
    args = ["cherry", "-v", base_ref, head_ref]
    if limit_ref:
        args.append(limit_ref)
    output = git(args)
    return [line for line in output.splitlines() if line and line[0] in "+-"]


def count_merges(base_ref: str, head_ref: str, limit_ref: str | None = None) -> int:
    revision = f"{limit_ref}..{head_ref}" if limit_ref else f"{base_ref}..{head_ref}"
    output = git(["rev-list", "--merges", "--count", revision])
    try:
        return int(output)
    except ValueError as error:
        raise RuntimeError(f"Unexpected merge-count output for {revision}: {output!r}") from error


def unmatched_commit_details(
    base_ref: str,
    head_ref: str,
    cherry: list[str],
    limit_ref: str | None = None,
    current_ref: str | None = None,
) -> list[dict[str, Any]]:
    """Return concrete unmatched/merge commit evidence for the audit report."""
    shas = [line.split()[1] for line in cherry if line.startswith("+") and len(line.split()) >= 2]
    revision = f"{limit_ref}..{head_ref}" if limit_ref else f"{base_ref}..{head_ref}"
    merge_output = git(["log", "--merges", "--format=%H%x09%cs%x09%s", revision])
    merge_rows = [line for line in merge_output.splitlines() if line]
    records: dict[str, dict[str, Any]] = {}
    for sha in shas:
        output = git(["show", "-s", "--format=%H%x09%cs%x09%s", sha])
        parts = output.split("\t", 2)
        if len(parts) == 3:
            containing = git(["branch", "--remotes", "--contains", parts[0], "--format=%(refname)"])
            carried_by = sorted(
                ref.removeprefix("refs/remotes/origin/")
                for ref in containing.splitlines()
                if ref.startswith("refs/remotes/origin/") and ref != current_ref
            )
            records[parts[0]] = {
                "sha": parts[0], "date": parts[1], "subject": parts[2],
                "merge": False, "also_reachable_from": carried_by,
            }
    for line in merge_rows:
        parts = line.split("\t", 2)
        if len(parts) == 3:
            containing = git(["branch", "--remotes", "--contains", parts[0], "--format=%(refname)"])
            carried_by = sorted(
                ref.removeprefix("refs/remotes/origin/")
                for ref in containing.splitlines()
                if ref.startswith("refs/remotes/origin/") and ref != current_ref
            )
            records[parts[0]] = {
                "sha": parts[0], "date": parts[1], "subject": parts[2],
                "merge": True, "also_reachable_from": carried_by,
            }
    return sorted(records.values(), key=lambda item: (item["date"], item["sha"]))


def count_commits(base_ref: str, head_ref: str) -> int:
    output = git(["rev-list", "--count", f"{base_ref}..{head_ref}"])
    try:
        return int(output)
    except ValueError as error:
        raise RuntimeError(f"Unexpected commit-count output: {output!r}") from error


def related_pr(pr: dict[str, Any]) -> dict[str, Any]:
    head = pr.get("head") if isinstance(pr.get("head"), dict) else {}
    base = pr.get("base") if isinstance(pr.get("base"), dict) else {}
    return {
        "number": pr.get("number"),
        "title": pr.get("title", ""),
        "state": pr.get("state", "unknown"),
        "merged_at": pr.get("merged_at"),
        "closed_at": pr.get("closed_at"),
        "head_ref": head.get("ref"),
        "head_sha": head.get("sha"),
        "base_ref": base.get("ref"),
        "merge_commit_sha": pr.get("merge_commit_sha"),
        "url": pr.get("html_url", ""),
    }


def pr_history_for_branch(prs: list[dict[str, Any]], repo: str, branch: str) -> list[dict[str, Any]]:
    history: list[dict[str, Any]] = []
    for pr in prs:
        head = pr.get("head") if isinstance(pr.get("head"), dict) else {}
        head_repo = head.get("repo") if isinstance(head.get("repo"), dict) else {}
        if head.get("ref") == branch and head_repo.get("full_name") == repo:
            history.append(related_pr(pr))
    return sorted(history, key=lambda item: (item.get("merged_at") or item.get("closed_at") or "", item.get("number") or 0), reverse=True)


def inspect_branch_history(base_ref: str, head_ref: str, history: list[dict[str, Any]]) -> dict[str, Any]:
    """Classify branch history using ancestry, cherry patch IDs, and merged PR heads."""
    ahead = count_commits(base_ref, head_ref)
    if ahead == 0:
        return {
            "status": "INTEGRATED", "detail": "Branch tip is already reachable from the default branch.",
            "ahead": 0, "unmatched": 0, "equivalent": 0, "merges": 0, "unmatched_commits": [],
        }

    open_prs = [pr for pr in history if pr.get("state") == "open"]
    if open_prs:
        cherry = cherry_lines(base_ref, head_ref)
        unmatched = sum(line.startswith("+") for line in cherry)
        equivalent = sum(line.startswith("-") for line in cherry)
        merges = count_merges(base_ref, head_ref)
        refs = ", ".join(f"#{pr['number']}" for pr in open_prs)
        return {
            "status": "ACTIVE_PR", "detail": f"Open PR history found ({refs}); branch retained for active review.",
            "ahead": ahead, "unmatched": unmatched, "equivalent": equivalent, "merges": merges,
            "unmatched_commits": unmatched_commit_details(base_ref, head_ref, cherry, current_ref=head_ref),
        }

    # A squash-merged PR does not make its original commits ancestors of main.
    # Its merge commit must be on main and its recorded PR head must be an
    # ancestor of this branch before we use it as the boundary for later work.
    proven_merged: list[dict[str, Any]] = []
    for pr in history:
        head_sha = pr.get("head_sha")
        merge_sha = pr.get("merge_commit_sha")
        if not pr.get("merged_at") or not head_sha or not merge_sha:
            continue
        if is_ancestor(merge_sha, base_ref) and is_ancestor(head_sha, head_ref):
            proven_merged.append(pr)

    if proven_merged:
        # Pick the most recent merged PR head that is in the current branch's
        # ancestry. Only commits after that reviewed/merged snapshot are new work.
        pr = proven_merged[0]
        head_sha = pr["head_sha"]
        tail = cherry_lines(base_ref, head_ref, head_sha)
        unmatched = sum(line.startswith("+") for line in tail)
        equivalent = sum(line.startswith("-") for line in tail)
        merges = count_merges(base_ref, head_ref, head_sha)
        if unmatched == 0 and merges == 0:
            status = "MERGED_PR_NO_NEW_WORK"
            detail = (
                f"PR #{pr['number']} was merged and its merge commit is on the default branch; "
                "no unmatched patch or merge commits remain after that PR head."
            )
        else:
            status = "MERGED_PR_WITH_NEW_WORK"
            detail = (
                f"PR #{pr['number']} was merged, but the branch has {unmatched} unmatched patch(es) "
                f"and {merges} merge commit(s) after the merged PR head. Manual review required."
            )
        return {
            "status": status, "detail": detail, "ahead": ahead,
            "unmatched": unmatched, "equivalent": equivalent, "merges": merges,
            "unmatched_commits": unmatched_commit_details(base_ref, head_ref, tail, head_sha, head_ref),
        }

    cherry = cherry_lines(base_ref, head_ref)
    unmatched = sum(line.startswith("+") for line in cherry)
    equivalent = sum(line.startswith("-") for line in cherry)
    merges = count_merges(base_ref, head_ref)
    non_merge_commits = int(git(["rev-list", "--no-merges", "--count", f"{base_ref}..{head_ref}"]))

    # git cherry normally emits one line per non-merge commit. Treat an
    # unexpected mismatch as an incomplete comparison rather than as clean.
    if len(cherry) != non_merge_commits:
        return {
            "status": "REVIEW_REQUIRED", "detail": (
                "Patch-equivalence output did not account for every non-merge commit; "
                "the audit cannot safely classify this branch."
            ), "ahead": ahead, "unmatched": unmatched, "equivalent": equivalent, "merges": merges,
            "unmatched_commits": unmatched_commit_details(base_ref, head_ref, cherry, current_ref=head_ref),
        }
    if unmatched == 0 and merges == 0:
        return {
            "status": "PATCH_EQUIVALENT", "detail": (
                f"All {equivalent} non-merge commit patches are already represented on the default branch, "
                "although the branch tip is not an ancestor (for example, rebase/cherry-pick history)."
            ), "ahead": ahead, "unmatched": 0, "equivalent": equivalent, "merges": 0,
            "unmatched_commits": [],
        }

    closed_unmerged = [pr for pr in history if pr.get("state") == "closed" and not pr.get("merged_at")]
    pr_note = " A closed, unmerged PR is associated with this branch." if closed_unmerged else ""
    return {
        "status": "REVIEW_REQUIRED", "detail": (
            f"{unmatched} unmatched patch(es) and {merges} merge commit(s) remain outside the default branch. "
            f"Inspect the commits and associated PR history before considering branch cleanup.{pr_note}"
        ), "ahead": ahead, "unmatched": unmatched, "equivalent": equivalent, "merges": merges,
        "unmatched_commits": unmatched_commit_details(base_ref, head_ref, cherry, current_ref=head_ref),
    }


def parse_iso_datetime(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def list_branches(api: GitHub, repo: str) -> list[dict[str, Any]]:
    branches = api.pages(f"/repos/{repo}/branches")
    if not branches:
        raise APIError("GitHub returned an empty branch list; refusing to publish an empty audit.")
    for branch in branches:
        if not isinstance(branch.get("name"), str) or not isinstance(branch.get("commit"), dict):
            raise APIError("GitHub returned a branch record without its name/commit metadata.")
    return branches


def list_pull_requests(api: GitHub, repo: str) -> list[dict[str, Any]]:
    # `state=all` is essential: closed and merged PRs explain branches that
    # remain ahead by SHA after squash merges.
    return api.pages(f"/repos/{repo}/pulls?state=all&sort=updated&direction=desc")


def render_report(repo: str, default_branch: str, stale_days: int, results: list[BranchResult], generated_at: str) -> str:
    counts: dict[str, int] = {}
    for result in results:
        counts[result.status] = counts.get(result.status, 0) + 1
    stale = [result for result in results if result.stale]
    review = [result for result in results if result.status in {"REVIEW_REQUIRED", "MERGED_PR_WITH_NEW_WORK"}]
    lines = [
        "# Orphaned branch audit", "", f"- Repository: `{repo}`", f"- Default branch: `{default_branch}`",
        f"- Generated: `{generated_at}`", f"- Stale threshold: `{stale_days}` days`, "",
        "## Summary", "", f"- Branches audited: **{len(results)}**", f"- Stale branches: **{len(stale)}**",
        f"- Branches requiring manual review: **{len(review)}**", "",
        "| Status | Count |", "|---|---:|",
    ]
    for status, count in sorted(counts.items()):
        lines.append(f"| `{status}` | {count} |")
    lines += [
        "", "## Branch findings", "",
        "| Branch | Age | Stale | Protected | Status | Ahead | Unmatched patches | Unmatched commit(s) | PR history | Details |",
        "|---|---:|:---:|:---:|---|---:|---:|---|---|---|",
    ]
    for item in sorted(results, key=lambda value: (value.status not in {"REVIEW_REQUIRED", "MERGED_PR_WITH_NEW_WORK"}, not value.stale, -value.age_days, value.branch)):
        prs = ", ".join(f"[#{pr['number']}]({pr['url']})" for pr in item.related_prs[:4] if pr.get("number") and pr.get("url")) or "—"
        detail = item.detail.replace("|", "\\|").replace("\n", " ")
        commits = "; ".join(
            f"`{commit['sha'][:10]}` {commit['subject']}"
            + (" (merge)" if commit["merge"] else "")
            + (f" [also in: {', '.join(commit['also_reachable_from'])}]" if commit["also_reachable_from"] else "")
            for commit in item.unmatched_commits
        ) or "—"
        lines.append(
            f"| `{item.branch}` | {item.age_days}d | {'yes' if item.stale else 'no'} | "
            f"{'yes' if item.protected else 'no'} | **{item.status}** | {item.topology_ahead} "
            f"| {item.unmatched_patches} | {commits} | {prs} | {detail} |"
        )
    lines += [
        "", "## Interpretation and safety", "",
        "- `INTEGRATED`: the branch tip is already reachable from the default branch.",
        "- `PATCH_EQUIVALENT`: every non-merge commit patch is represented on the default branch, but the branch tip is not an ancestor.",
        "- `MERGED_PR_NO_NEW_WORK`: a recorded merged PR head is in this branch's history, its merge commit is on the default branch, and no unmatched work follows it.",
        "- `ACTIVE_PR`: an open PR still uses this branch.",
        "- `REVIEW_REQUIRED` / `MERGED_PR_WITH_NEW_WORK`: unique patches, merge history, or incomplete proof remain.",
        "", "**This workflow is read-only. It does not delete branches or move refs.** Patch equivalence is evidence, not a deletion authorization; inspect the branch diff and PR before cleanup.",
        "", "The audit fails rather than silently classifying branches when GitHub inventory, PR pagination, or Git history cannot be fetched completely.", "",
    ]
    return "\n".join(lines)


def write_summary(markdown: str) -> None:
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as summary:
            summary.write(markdown + "\n")


def run(args: argparse.Namespace) -> int:
    repo = args.repo or os.environ.get("GITHUB_REPOSITORY")
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not repo or repo.count("/") != 1:
        print("ERROR: Set GITHUB_REPOSITORY or pass --repo owner/name.", file=sys.stderr)
        return 2
    if not token:
        print("ERROR: GITHUB_TOKEN is required (contents:read and pull-requests:read).", file=sys.stderr)
        return 2
    if args.stale_days < 0:
        print("ERROR: --stale-days cannot be negative.", file=sys.stderr)
        return 2

    try:
        api = GitHub(token)
        repository = api.get(f"/repos/{repo}")
        default_branch = repository.get("default_branch") if isinstance(repository, dict) else None
        if not default_branch:
            raise APIError("Repository metadata did not include default_branch.")
        branch_records = list_branches(api, repo)
        default_record = next((item for item in branch_records if item.get("name") == default_branch), None)
        if not default_record or not isinstance(default_record.get("commit"), dict):
            raise APIError("The default branch was absent from the branch inventory.")
        prs = list_pull_requests(api, repo)

        # Fetch every advertised branch tip explicitly. A missing or moved tip
        # is a hard audit error; stale local refs must never produce a false clean.
        git(["fetch", "--no-tags", "origin", "+refs/heads/*:refs/remotes/origin/*"])
        base_ref = f"refs/remotes/origin/{default_branch}"
        base_sha = git(["rev-parse", "--verify", f"{base_ref}^{{commit}}"])
        if default_record["commit"].get("sha") != base_sha:
            raise RuntimeError("The default branch moved while the inventory was being fetched; rerun for a consistent snapshot.")
        generated_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
        now = datetime.now(timezone.utc)
        results: list[BranchResult] = []

        for record in branch_records:
            name = record["name"]
            if name == default_branch:
                continue
            advertised_sha = record["commit"].get("sha")
            head_ref = f"refs/remotes/origin/{name}"
            local_sha = git(["rev-parse", "--verify", f"{head_ref}^{{commit}}"], check=False)
            if not advertised_sha or not local_sha or local_sha != advertised_sha:
                raise RuntimeError(
                    f"Branch {name!r} changed or was unavailable during the audit "
                    f"(API={advertised_sha!r}, fetched={local_sha!r}); rerun for a consistent snapshot."
                )

            timestamp = git(["show", "-s", "--format=%cI", head_ref])
            subject = git(["show", "-s", "--format=%s", head_ref])
            commit_time = parse_iso_datetime(timestamp)
            age_days = max(0, (now - commit_time).days)
            history = pr_history_for_branch(prs, repo, name)
            analysis = inspect_branch_history(base_ref, head_ref, history)
            results.append(BranchResult(
                branch=name,
                status=analysis["status"],
                protected=bool(record.get("protected", False)),
                last_commit_at=timestamp,
                age_days=age_days,
                stale=age_days >= args.stale_days,
                topology_ahead=analysis["ahead"],
                unmatched_patches=analysis["unmatched"],
                patch_equivalent_commits=analysis["equivalent"],
                merge_commits=analysis["merges"],
                related_prs=history,
                unmatched_commits=analysis["unmatched_commits"],
                detail=analysis["detail"],
                last_commit=subject,
                commit_sha=advertised_sha,
            ))

        if not results:
            print("No non-default branches were returned; refusing to treat that as a successful empty audit.", file=sys.stderr)
            return 2

        markdown = render_report(repo, default_branch, args.stale_days, results, generated_at)
        output_dir = Path(args.output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)
        (output_dir / "orphaned-branch-audit.md").write_text(markdown, encoding="utf-8")
        payload = {
            "repository": repo,
            "default_branch": default_branch,
            "default_branch_sha": base_sha,
            "generated_at": generated_at,
            "stale_threshold_days": args.stale_days,
            "branches_audited": len(results),
            "results": [asdict(item) for item in results],
        }
        (output_dir / "orphaned-branch-audit.json").write_text(
            json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        write_summary(markdown)
        print(markdown)
        print(f"\nReports written to {output_dir.resolve()}")
        return 0
    except (APIError, OSError, RuntimeError, subprocess.TimeoutExpired, ValueError) as error:
        print(f"::error::Orphaned-branch audit incomplete: {error}", file=sys.stderr)
        print("No branch refs were changed. Resolve the error and rerun the audit.", file=sys.stderr)
        return 2


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", help="Repository in owner/name form (defaults to GITHUB_REPOSITORY).")
    parser.add_argument("--stale-days", type=int, default=14, help="Age in days at which a branch is labelled stale (default: 14).")
    parser.add_argument("--output-dir", default="orphaned-branch-audit", help="Directory for Markdown and JSON reports.")
    return parser


if __name__ == "__main__":
    raise SystemExit(run(build_parser().parse_args()))
