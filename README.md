# Estatia batch branch cleanup

This is a manually dispatched GitHub Actions workflow for deleting an explicitly supplied batch of remote branches.

## Files

- `.github/scripts/delete-branches.py` — reusable cleanup script.
- `.github/workflows/delete-branches.yml` — `workflow_dispatch` workflow.
- `.github/branch-deletion-protected.txt` — branches intentionally retained for repository automation.

## Use

1. Open **Actions → Batch Branch Cleanup → Run workflow** after this PR has been merged.
2. Paste exact branch names into `branches`, one per line. Blank lines and lines beginning with `#` are ignored.
3. Keep `dry_run` enabled for the first run. Inspect the workflow summary and logs.
4. Run again with `dry_run` disabled to perform the deletions.

Example input:

```text
# Merged PR branches
ci/old-nightly-hardening
fix/obsolete-auth-flow
chore/old-generated-files
```

## Safety behavior

- Dry-run is the default. The script only deletes when explicitly invoked with `--execute`.
- The repository default branch is always protected.
- Branches in `.github/branch-deletion-protected.txt` are protected.
- Branches currently used as the head of an open PR are protected automatically.
- Branches for which GitHub reports `protected: true` are protected automatically.
- The workflow can only run from the repository default branch.
- The script processes only exact names supplied. There is no wildcard, prefix, or delete-all behavior.
- Missing branches are reported as skipped. Individual API failures are reported and the batch continues; the job fails if any deletion fails.
- The script fails closed if repository or open-PR safety metadata cannot be retrieved.

The workflow token needs `contents: write` to delete refs and `pull-requests: read` to protect branches backing open PRs. Permissions are scoped to this workflow.

## Run locally

The script requires Python 3, Git, a GitHub token, and network access. Set `GITHUB_REPOSITORY=owner/name` and `GITHUB_TOKEN`, then run:

```bash
printf '%s\n' 'branch/one' 'branch/two' | \
  python3 .github/scripts/delete-branches.py \
    --from-stdin \
    --protect-file .github/branch-deletion-protected.txt
```

That invocation is a dry run. Append `--execute` only when you intend to delete the refs.
