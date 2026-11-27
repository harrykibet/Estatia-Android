"""Unit tests for the batch branch cleanup input parser."""
from __future__ import annotations

import argparse
import importlib.util
import io
import os
import unittest
from contextlib import redirect_stderr
from pathlib import Path
import sys
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "delete-branches.py"
SPEC = importlib.util.spec_from_file_location("delete_branches", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load cleanup script: {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class ParseNamesTests(unittest.TestCase):
    def test_accepts_json_array_from_workflow_input(self) -> None:
        names = MODULE.parse_names([], '["feature/one","fix/two"]')
        self.assertEqual(names, ["feature/one", "fix/two"])

    def test_accepts_newline_delimited_input(self) -> None:
        names = MODULE.parse_names([], "feature/one\nfix/two\n")
        self.assertEqual(names, ["feature/one", "fix/two"])

    def test_deduplicates_json_entries_without_changing_order(self) -> None:
        names = MODULE.parse_names([], '["feature/one","fix/two","feature/one"]')
        self.assertEqual(names, ["feature/one", "fix/two"])

    def test_ignores_blank_lines_and_comments(self) -> None:
        names = MODULE.parse_names([], "feature/one\n\n# keep this out\nfix/two\n")
        self.assertEqual(names, ["feature/one", "fix/two"])

    def test_rejects_non_array_json(self) -> None:
        with self.assertRaisesRegex(ValueError, "JSON branch input must be an array"):
            MODULE.parse_names([], '{"branch":"feature/one"}')

    def test_rejects_non_string_json_entries(self) -> None:
        with self.assertRaisesRegex(ValueError, "Every item"):
            MODULE.parse_names([], '["feature/one", 42]')

    def test_rejects_malformed_json(self) -> None:
        with self.assertRaisesRegex(ValueError, "JSON array"):
            MODULE.parse_names([], '["feature/one",')

    def test_rejects_whitespace_inside_a_branch_name(self) -> None:
        with self.assertRaisesRegex(ValueError, "cannot contain whitespace"):
            MODULE.parse_names([], "feature/one with spaces")



class AllBranchesSafetyTests(unittest.TestCase):
    def test_all_branches_mode_rejects_execute_before_api_access(self) -> None:
        args = argparse.Namespace(
            repo="owner/repo",
            branches=[],
            branch_options=[],
            from_stdin=False,
            protect_file=None,
            protect_branch=[],
            all_branches=True,
            execute=True,
        )
        with patch.dict(os.environ, {"GITHUB_TOKEN": "unit-test-token"}):
            with redirect_stderr(io.StringIO()):
                result = MODULE.run(args)
        self.assertEqual(result, 2)


class BranchComparisonTests(unittest.TestCase):
    def test_compare_ahead_by_encodes_slashes_in_refs(self) -> None:
        class FakeAPI:
            def __init__(self) -> None:
                self.path = ""

            def get(self, path: str) -> dict[str, int]:
                self.path = path
                return {"ahead_by": 3}

        api = FakeAPI()
        ahead_by = MODULE.compare_ahead_by(
            api, "owner/repo", "release/main", "feature/cleanup"
        )
        self.assertEqual(ahead_by, 3)
        self.assertEqual(
            api.path,
            "/repos/owner/repo/compare/release%2Fmain...feature%2Fcleanup",
        )

    def test_compare_rejects_missing_ahead_by_metadata(self) -> None:
        class FakeAPI:
            def get(self, path: str) -> dict[str, str]:
                return {"status": "ahead"}

        with self.assertRaisesRegex(RuntimeError, "invalid ahead_by"):
            MODULE.compare_ahead_by(FakeAPI(), "owner/repo", "main", "feature/test")

    def test_compare_fails_closed_on_api_errors(self) -> None:
        class FakeAPI:
            def get(self, path: str) -> dict[str, int]:
                raise MODULE.APIError(404, "No common history", path)

        with self.assertRaisesRegex(RuntimeError, "refusing deletion"):
            MODULE.compare_ahead_by(FakeAPI(), "owner/repo", "main", "feature/test")


class ExecutionUniqueCommitGuardTests(unittest.TestCase):
    def _run_with_comparison(self, ahead_by: int) -> tuple[int, str, list[str]]:
        class FakeAPI:
            def __init__(self) -> None:
                self.deleted: list[str] = []

            def get(self, path: str) -> dict[str, object]:
                if path == "/repos/owner/repo":
                    return {"default_branch": "main"}
                if path == "/repos/owner/repo/branches/feature%2Fcandidate":
                    return {"protected": False, "commit": {"sha": "candidate-sha"}}
                if path == "/repos/owner/repo/compare/main...feature%2Fcandidate":
                    return {"ahead_by": ahead_by}
                raise AssertionError(f"Unexpected GET path: {path}")

            def pages(self, path: str) -> list[dict[str, object]]:
                self.assert_open_pr_path(path)
                return []

            @staticmethod
            def assert_open_pr_path(path: str) -> None:
                if path != "/repos/owner/repo/pulls?state=open":
                    raise AssertionError(f"Unexpected page path: {path}")

            def delete(self, path: str) -> None:
                self.deleted.append(path)

        api = FakeAPI()
        args = argparse.Namespace(
            repo="owner/repo",
            branches=["feature/candidate"],
            branch_options=[],
            from_stdin=False,
            protect_file=None,
            protect_branch=[],
            all_branches=False,
            execute=True,
        )
        output = io.StringIO()
        with patch.object(MODULE, "GitHub", return_value=api):
            with patch.dict(os.environ, {"GITHUB_TOKEN": "unit-test-token"}):
                with redirect_stdout(output):
                    result = MODULE.run(args)
        return result, output.getvalue(), api.deleted

    def test_execute_blocks_branch_with_unique_commits(self) -> None:
        result, output, deleted = self._run_with_comparison(ahead_by=2)
        self.assertEqual(result, 0)
        self.assertIn("REVIEW_REQUIRED", output)
        self.assertIn("2 commit(s) not present in main", output)
        self.assertEqual(deleted, [])

    def test_execute_deletes_only_when_no_unique_commits_exist(self) -> None:
        result, output, deleted = self._run_with_comparison(ahead_by=0)
        self.assertEqual(result, 0)
        self.assertIn("DELETED", output)
        self.assertEqual(
            deleted,
            ["/repos/owner/repo/git/refs/heads/feature%2Fcandidate"],
        )


if __name__ == "__main__":
    unittest.main()
