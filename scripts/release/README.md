# Sync evidence records

Generate a record for an **existing main sync**, from a full local checkout with
`origin/main` fetched and read-only GitHub CLI access:

```powershell
python scripts/release/sync_record.py --sync-commit 6c434da7e --sync-number 10
python -m unittest discover -s scripts/release -p "test_*.py" -v
```

The generator writes `docs/releases/<UTC-commit-date>-sync-N.md`. It requires the
named develop commit to equal the sync's second parent and verifies that the sync
is an ancestor of `origin/main`. Its first parent is the code rollback point.
It inventories all commits introduced since previous main; a paginated closed-PR
inventory matches actual merge SHAs for merge, squash and rebase PRs, with merge
subjects as an independent completeness check. Missing associations fail visibly.
Added versioned SQL migrations and every other migration-directory change
(including edited/deleted/renamed SQL and changelog registration) are listed separately.
Both the main and included develop commit receive check-run and commit-status
links. Missing or pending evidence stays visibly unknown or pending.

`Sync evidence record` runs after main's `Project Quality Gates` workflow completes,
using that run's exact head SHA, or manually for a historical sync. Non-sync main
runs create no record. It excludes its own workflow's checks and waits up to 15
minutes for other checks/statuses; pending or absent evidence at that deadline
fails publication visibly and retains the incomplete artifact for inspection.
Terminal failures are recorded truthfully, never relabelled green. Rerun after
pending checks finish. GitHub rate-limit failures receive bounded backoff;
authorization refusals do not. Each GitHub call has a 90-second timeout.
The test/selection jobs have read-only tokens. The recording job creates one dedicated
record branch and opens a **draft PR against develop** containing only the generated
Markdown file. Its token can write that branch/PR; it cannot change repository
settings. An open PR's explicitly delimited evidence block may be refreshed by
an ordinary fast-forward commit. Stable identity, PR and migration sections must
match exactly; unrelated branch changes are refused. A pushed branch whose PR
creation failed is recoverable even after evidence changes. Closed/merged PRs
are left alone. Runs for the same canonical full sync SHA are serialized; a
racing external writer fails safely without force-pushing. The workflow retains a 90-day artifact,
including when PR publication fails. The record branch/PR provides durable Git
history; artifact retention alone is not a permanent audit trail.

Nothing changes readiness, merges, deploys or runs rollback commands. The owner
reviews the record like other drafts. Repository Actions policy may prohibit
bot-created PRs; in that case publication fails visibly while retaining the
artifact. Do not weaken that policy automatically. GitHub may suppress automatic
checks on a PR created with its workflow token, so a human reviewer must trigger
any required checks before their merge decision. Sync #10's generated file is
included as the worked example in this PR.

A rollback SHA does **not** reverse database migrations. Operators must use the
reviewed recovery procedure and owner approval for any actual rollback.
