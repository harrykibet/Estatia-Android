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


if __name__ == "__main__":
    unittest.main()
