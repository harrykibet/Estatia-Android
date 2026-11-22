#!/usr/bin/env python3
"""Validate changed Markdown links/fences and GitHub issue-form YAML without third-party packages."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path.cwd().resolve()
LINK_RE = re.compile(r"""!?\\[ [^\\]]* \\]\\( \\s* (<[^>]+>|[^)\\s]+) (?:\\s+[^)]*)? \\)""", re.VERBOSE)
FENCE_RE = re.compile(r"^\s*(`{3,}|~{3,})")


def fail(message: str) -> None:
    print(f"::error::{message}", file=sys.stderr)


def changed_paths(base: str, head: str) -> list[str]:
    if not base or not head or set(base) == {"0"}:
        raise ValueError("A valid base and head commit are required for documentation validation.")
    result = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=ACMRT", "-z", base, head],
        check=True,
        stdout=subprocess.PIPE,
    )
    return [item for item in result.stdout.decode("utf-8").split("\0") if item]


def validate_markdown(path: Path) -> list[str]:
    errors: list[str] = []
    content = path.read_text(encoding="utf-8-sig")
    active_fence: tuple[str, int] | None = None
    visible_lines: list[str] = []

    for line_number, line in enumerate(content.splitlines(), start=1):
        match = FENCE_RE.match(line)
        if match:
            fence = match.group(1)
            marker = fence[0]
            length = len(fence)
            if active_fence is None:
                active_fence = (marker, length)
            elif marker == active_fence[0] and length >= active_fence[1]:
                active_fence = None
            continue
        if active_fence is None:
            visible_lines.append(line)

    if active_fence is not None:
        errors.append(f"{path}: unclosed Markdown code fence")

    visible = "\n".join(visible_lines)
    for match in LINK_RE.finditer(visible):
        destination = match.group(1).strip()
        if destination.startswith("<") and destination.endswith(">"):
            destination = destination[1:-1]
        parsed = urlsplit(destination)
        if parsed.scheme or parsed.netloc or not parsed.path:
            continue
        relative_path = unquote(parsed.path)
        target = (path.parent / relative_path).resolve()
        try:
            target.relative_to(ROOT)
        except ValueError:
            errors.append(f"{path}: link escapes repository root: {destination}")
            continue
        if not target.exists():
            errors.append(f"{path}: broken relative link: {destination}")
    return errors


def validate_issue_yaml(paths: list[str]) -> list[str]:
    if not paths:
        return []
    ruby_code = r"""
require "yaml"
ARGV.each do |path|
  document = YAML.safe_load_file(path, aliases: true)
  abort("#{path}: expected a YAML mapping") unless document.is_a?(Hash)
  required = path.end_with?("/config.yml") ? %w[blank_issues_enabled contact_links] : %w[name description body]
  missing = required.reject { |key| document.key?(key) }
  abort("#{path}: missing required top-level keys: #{missing.join(', ')}") unless missing.empty?
  if path.end_with?("/config.yml")
    abort("#{path}: contact_links must be an array") unless document["contact_links"].is_a?(Array)
  else
    abort("#{path}: body must be an array") unless document["body"].is_a?(Array)
  end
end
"""
    try:
        subprocess.run(["ruby", "-e", ruby_code, *paths], check=True)
    except (FileNotFoundError, subprocess.CalledProcessError) as error:
        return [f"GitHub issue-form YAML validation failed: {error}"]
    return []


def main() -> int:
    if len(sys.argv) != 3:
        print("Usage: validate-documentation.py BASE_SHA HEAD_SHA", file=sys.stderr)
        return 2
    try:
        paths = changed_paths(sys.argv[1], sys.argv[2])
    except (ValueError, subprocess.CalledProcessError) as error:
        fail(str(error))
        return 1

    markdown = [p for p in paths if p.lower().endswith(".md") and (ROOT / p).is_file()]
    issue_yaml = [
        p for p in paths
        if p.startswith(".github/ISSUE_TEMPLATE/")
        and p.lower().endswith((".yml", ".yaml"))
        and (ROOT / p).is_file()
    ]

    errors: list[str] = []
    for relative in markdown:
        errors.extend(validate_markdown(ROOT / relative))
    errors.extend(validate_issue_yaml(issue_yaml))

    if errors:
        for error in errors:
            fail(error)
        return 1

    print(f"Documentation validation passed: {len(markdown)} Markdown file(s), {len(issue_yaml)} issue-form YAML file(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
