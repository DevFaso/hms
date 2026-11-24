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
It inventories all commits introduced since previous main; GitHub associations
cover merge, squash and rebase PRs, with merge-commit subjects as an independent
completeness check. Added versioned SQL migrations come from that same Git range.
Both the main and included develop commit receive check-run and commit-status
links. Missing or pending evidence stays visibly unknown or pending.

`Sync evidence record` runs on a main push, or manually for a historical sync.
Non-sync main pushes create no record. The test job has a read-only token. The recording job creates one dedicated
record branch and opens a **draft PR against develop** containing only the generated
Markdown file. Its token can write that branch/PR; it cannot change repository
settings. An existing PR is left alone, and an existing branch is never rewritten
or force-pushed. The workflow also retains the generated tree as a 90-day artifact,
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
