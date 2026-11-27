from __future__ import annotations

import contextlib
import importlib.util
import sys
import subprocess
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "audit-orphaned-branches.py"
SPEC = importlib.util.spec_from_file_location("orphan_audit", SCRIPT)
assert SPEC and SPEC.loader
AUDIT = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = AUDIT
SPEC.loader.exec_module(AUDIT)


def git(cwd: Path, *args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=cwd, check=True, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    ).stdout.strip()


def init_repo(path: Path) -> str:
    git(path, "init", "-q", "-b", "main")
    git(path, "config", "user.name", "Estatia CI")
    git(path, "config", "user.email", "ci@example.invalid")
    (path / "seed.txt").write_text("base\n", encoding="utf-8")
    git(path, "add", "-A")
    git(path, "commit", "-q", "-m", "base")
    return git(path, "rev-parse", "HEAD")


def inspect(repo: Path, base: str, head: str, history: list[dict]) -> dict:
    with contextlib.chdir(repo):
        return AUDIT.inspect_branch_history(base, head, history)


def commit_file(repo: Path, name: str, content: str, message: str) -> str:
    (repo / name).write_text(content, encoding="utf-8")
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", message)
    return git(repo, "rev-parse", "HEAD")


def test_branch_already_ancestral_to_main_is_integrated() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp)
        init_repo(repo)
        git(repo, "checkout", "-q", "-b", "feature")
        head = commit_file(repo, "feature.txt", "feature\n", "feature work")
        git(repo, "checkout", "-q", "main")
        git(repo, "merge", "--no-ff", "-q", "feature", "-m", "merge feature")
        result = inspect(repo, "main", head, [])
        assert result["status"] == "INTEGRATED"
        assert result["ahead"] == 0


def test_cherry_picked_patch_is_recognized_as_equivalent() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp)
        init_repo(repo)
        git(repo, "checkout", "-q", "-b", "feature")
        feature = commit_file(repo, "feature.txt", "same change\n", "feature change")
        git(repo, "checkout", "-q", "main")
        commit_file(repo, "unrelated.txt", "main-only change\n", "unrelated main work")
        git(repo, "cherry-pick", feature)
        # Branch tip stays on original commit; main has an equivalent patch with a different SHA.
        result = inspect(repo, "main", feature, [])
        assert result["status"] == "PATCH_EQUIVALENT"
        assert result["unmatched"] == 0
        assert result["equivalent"] == 1


def test_squash_merged_pr_head_is_recognized() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp)
        init_repo(repo)
        git(repo, "checkout", "-q", "-b", "feature")
        feature_head = commit_file(repo, "feature.txt", "squash change\n", "feature change")
        git(repo, "checkout", "-q", "main")
        git(repo, "merge", "--squash", "-q", "feature")
        git(repo, "commit", "-q", "-m", "squash merge PR #7")
        merge_sha = git(repo, "rev-parse", "HEAD")
        history = [{
            "number": 7, "title": "squashed feature", "state": "closed",
            "merged_at": "2026-11-01T10:00:00Z", "closed_at": "2026-11-01T10:00:00Z",
            "head_ref": "feature", "head_sha": feature_head,
            "base_ref": "main", "merge_commit_sha": merge_sha,
            "url": "https://github.com/example/repo/pull/7",
        }]
        result = inspect(repo, "main", feature_head, history)
        assert result["status"] == "MERGED_PR_NO_NEW_WORK"
        assert result["unmatched"] == 0


def test_unique_commit_after_squash_merged_pr_is_flagged() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp)
        init_repo(repo)
        git(repo, "checkout", "-q", "-b", "feature")
        feature_head = commit_file(repo, "feature.txt", "merged feature\n", "feature PR work")
        tail_head = commit_file(repo, "later.txt", "important later work\n", "later unsubmitted work")
        git(repo, "checkout", "-q", "main")
        git(repo, "merge", "--squash", "-q", "feature_head")
        git(repo, "commit", "-q", "-m", "squash merge PR #8")
        merge_sha = git(repo, "rev-parse", "HEAD")
        history = [{
            "number": 8, "title": "squashed feature", "state": "closed",
            "merged_at": "2026-11-02T10:00:00Z", "closed_at": "2026-11-02T10:00:00Z",
            "head_ref": "feature", "head_sha": feature_head,
            "base_ref": "main", "merge_commit_sha": merge_sha,
            "url": "https://github.com/example/repo/pull/8",
        }]
        result = inspect(repo, "main", tail_head, history)
        assert result["status"] == "MERGED_PR_WITH_NEW_WORK"
        assert result["unmatched"] == 1


def test_unmerged_merge_commit_history_requires_review() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp)
        init_repo(repo)
        git(repo, "checkout", "-q", "-b", "side")
        side = commit_file(repo, "side.txt", "side\n", "side work")
        git(repo, "checkout", "-q", "main")
        git(repo, "checkout", "-q", "-b", "feature")
        feature = commit_file(repo, "feature.txt", "feature\n", "feature work")
        git(repo, "merge", "--no-ff", "-q", "side", "-m", "merge side")
        result = inspect(repo, "main", "feature", [])
        assert result["status"] == "REVIEW_REQUIRED"
        assert result["merges"] == 1

def test_open_pr_is_never_marked_orphaned() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp)
        init_repo(repo)
        git(repo, "checkout", "-q", "-b", "feature")
        head = commit_file(repo, "feature.txt", "work\n", "active PR work")
        history = [{
            "number": 9, "title": "open change", "state": "open",
            "merged_at": None, "closed_at": None, "head_ref": "feature",
            "head_sha": head, "base_ref": "main", "merge_commit_sha": None,
            "url": "https://github.com/example/repo/pull/9",
        }]
        result = inspect(repo, "main", head, history)
        assert result["status"] == "ACTIVE_PR"


def test_unmatched_commit_reports_other_branches_that_contain_it() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp)
        init_repo(repo)
        git(repo, "checkout", "-q", "-b", "old-feature")
        old_head = commit_file(repo, "old.txt", "important work\n", "important old branch work")
        git(repo, "checkout", "-q", "-b", "new-feature")
        new_head = commit_file(repo, "new.txt", "follow-up\n", "new branch follow-up")
        # Model the remote-tracking refs populated by the GitHub Actions fetch.
        git(repo, "update-ref", "refs/remotes/origin/old-feature", old_head)
        git(repo, "update-ref", "refs/remotes/origin/new-feature", new_head)
        with contextlib.chdir(repo):
            result = AUDIT.inspect_branch_history("main", old_head, [])
            details = AUDIT.unmatched_commit_details(
                "main", old_head, AUDIT.cherry_lines("main", old_head),
                current_ref="refs/remotes/origin/old-feature",
            )
        assert result["status"] == "REVIEW_REQUIRED"
        assert details[0]["also_reachable_from"] == ["new-feature"]


import unittest


class TestOrphanedBranchAudit(unittest.TestCase):
    def test_integrated_branch(self) -> None:
        test_branch_already_ancestral_to_main_is_integrated()

    def test_patch_equivalence(self) -> None:
        test_cherry_picked_patch_is_recognized_as_equivalent()

    def test_squash_merged_pr(self) -> None:
        test_squash_merged_pr_head_is_recognized()

    def test_unique_tail_after_merged_pr(self) -> None:
        test_unique_commit_after_squash_merged_pr_is_flagged()

    def test_unmerged_merge_history(self) -> None:
        test_unmerged_merge_commit_history_requires_review()

    def test_open_pr(self) -> None:
        test_open_pr_is_never_marked_orphaned()

    def test_carried_forward_commit(self) -> None:
        test_unmatched_commit_reports_other_branches_that_contain_it()


if __name__ == "__main__":
    unittest.main()
