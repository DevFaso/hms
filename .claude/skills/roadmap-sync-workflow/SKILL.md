---
name: roadmap-sync-workflow
description: Use when changing roadmap status (not-started / started / completed / blocked / deferred / dropped), adding or removing a row, or syncing roadmap files after a PR merge. Always update all three roadmap files together — CSV is the source of truth, MD is the narrative, XLSX is regenerated.
---

# Roadmap sync workflow

`docs/roadmap.csv`, `docs/roadmap.md`, and `docs/roadmap.xlsx` are
three views of the same data. They MUST stay synchronised.

## The trio

- **`docs/roadmap.csv`** — the **source of truth**, flat
  machine-readable, ten columns:
  `horizon, target, lane, item, deliverable, dependency, effort,
  owner, status, exit_criteria_link`.
- **`docs/roadmap.md`** — narrative view: timeline, dated "update"
  blockquotes, exit criteria, "where the project is today" table.
- **`docs/roadmap.xlsx`** — pre-formatted spreadsheet (bold + frozen
  header, auto-filter, color-coded horizons + statuses). **Do not
  hand-edit** — regenerate via
  `scripts/build-roadmap-xlsx.py` after every CSV change.

## Status vocabulary (exactly six values)

| Value | Meaning |
| --- | --- |
| `not-started` | Default for every new row. Work has not begun. |
| `started` | A branch / PR / heads-down person exists this week. |
| `blocked` | Paused on external dependency or decision. Always pair with a one-line note in the `deliverable` cell explaining what's blocking. |
| `completed` | Shipped. Verifiable on the listed branch / runbook. |
| `deferred` | Still on the roadmap but moved to a later horizon. Row stays in place; only the `horizon` cell changes. |
| `dropped` | Explicitly removed. Keep the row so future readers see the decision; never delete. |

The colour palette in `scripts/build-roadmap-xlsx.py` keys off these
exact strings — **adding a new value without updating both places is a
bug**.

## When a PR ships work for a row

The foundation-pass pattern that's been used on rows 23 / 24 / 26 /
33 / 35 / 37 / 38:

- Flip status from `not-started` → `started` (NOT directly to
  `completed`) when the foundation lands but follow-on work is
  documented.
- Append a description block to the `deliverable` cell using this
  shape:

  > `<original deliverable text>. <Foundation pass / Shipped> on
  > <branch> (PR #N): <2-3 sentence summary of what landed
  > — schema changes, key classes, feature flag, tests count,
  > runbook>. <Follow-on scope explicitly named>.`

- Re-quote the cell (commas in the description force CSV quoting).

Flip `started → completed` only when the row's exit criteria are
satisfied AND there is no remaining roadmap-listed follow-on.

## When updating roadmap.md

For every PR that flips a row's status, add a dated blockquote at the
top of `roadmap.md` (under the "Last updated" line) — same shape as
the existing `2026-05-15 update` and `2026-05-16 update` blockquotes.
The blockquote should:

- Name the row number(s) flipped.
- Link to the merge commit hash on GitHub.
- 5-15 lines summarising what shipped + what's deferred.
- End with no trailing `>` on a blank line (the markdown linter
  flags MD028 otherwise).

When multiple rows ship in the same date window, group them in one
blockquote with sub-bullets per row — see the `2026-05-16 update`
entry for the pattern.

Bump the `Last updated: **YYYY-MM-DD**.` line at the same time.

## Regenerating the xlsx

```bash
python3 -m venv /tmp/xlsx-venv && /tmp/xlsx-venv/bin/pip install -q openpyxl
/tmp/xlsx-venv/bin/python scripts/build-roadmap-xlsx.py
```

On Windows:

```powershell
C:\Python314\python.exe -m venv $env:TEMP\xlsx-venv
& "$env:TEMP\xlsx-venv\Scripts\pip.exe" install -q openpyxl
& "$env:TEMP\xlsx-venv\Scripts\python.exe" scripts\build-roadmap-xlsx.py
```

The script reads `docs/roadmap.csv`, applies the horizon + status
colour palette, freezes the header row, adds an auto-filter, and
writes `docs/roadmap.xlsx`. Expected output: `Wrote docs/roadmap.xlsx
(N rows)`.

## When to NOT touch roadmap files

- **Routine bug fixes that don't change a row status** — no roadmap
  update.
- **Refactors that don't ship a roadmap-listed deliverable** — no
  roadmap update.
- **Sonar / Copilot review-comment commits on an already-`started`
  row** — no roadmap update.

The narrative blockquote is for **status transitions**, not for every
commit on the branch.

## Carrying CSV updates on the feature branch vs separate sync

Two equally-valid patterns:

1. **Feature PR carries its own CSV/MD update** (rows 23, 24 used
   this) — the merge commit transitions the row in the same go.
2. **Separate `chore/roadmap-sync-*` PR** (rows 26 / 33 / 35 / 37 /
   38 used this) — one batched roadmap update after multiple feature
   PRs merge. Cleaner history, easier to revert.

Pick (1) when the PR is single-row and ready to merge atomically.
Pick (2) when multiple PRs are in flight or the CSV change deserves
explicit review.

## Cell text must match the actual implementation

The roadmap cell describing a foundation pass is a contract with
reviewers: it commits to specific test counts, specific HTTP
status codes, specific class names. When the implementation drifts
during PR review (e.g. widening a test's `isIn(401, 404)` to
`isIn(401, 403, 404)`), the cell text MUST track that drift in the
same commit. Otherwise Copilot will flag the inconsistency on
review and the cell becomes misleading reference material for
later picks.

Common drift surfaces:

- **HTTP status sets in IT descriptions.** Cell says "flag-off
  401/404 split"; test accepts 401/403/404. Update the cell to
  "flag-off 401/403/404 split". Caught on row 25 EMPI in PR #349.
- **Test counts.** Cell says "5 new ITs"; reviewer count is 4 + 1
  unit = 5. Both wordings are valid, pick one and stick to it.
- **Class names that change during review.** A rename in
  response to a Copilot finding must propagate into the cell.

When the drift fix lands in a separate "fix(scope): address
Copilot review" commit, the cell update can ride along — re-quote
the CSV cell, regenerate the xlsx, and call out the cell-text
patch in the commit message body.

## Reference files

- `docs/roadmap.csv` — source of truth
- `docs/roadmap.md` — narrative
- `docs/roadmap.xlsx` — generated
- `scripts/build-roadmap-xlsx.py` — the generator
