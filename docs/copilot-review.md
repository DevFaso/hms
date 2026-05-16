# Copilot review — `promote/develop-to-uat-2026-05-14` (2026-05-14)

Working notes on the Copilot review of the promote PR. Each comment is
summarised, then marked ✅ (fixed) or ⚠️ (acknowledged with caveat).

> **Round 1** review on PR #322 (`investigate/keycloak-env-sync-audit`)
> was previously documented in this file; replaced by this round 2 since
> the same file is reused per review cycle. The round 1 responses are
> preserved in git history if needed.

---

## ✅ #1 — `--env` arg parsing crashes under `set -u` when value missing (Medium)

**File:** `scripts/keycloak/env-sync-verify.sh` lines 54-66 (Copilot also flagged lines 291, 399 — those are **false positives**, only line 58 had this pattern)

**Copilot:** `shift; ENV_FILTER="$1"` without validating that a value is present. With `set -u`, `--env` at the end of argv (or `--env --json`) crashes with an unbound variable instead of exiting cleanly.

**Resolution:** Fixed. Replaced the bare `shift; ENV_FILTER="$1"` with a defensive block that:

1. Checks `$# -lt 2` (next arg exists) and `"$2" == --*` (next arg isn't another flag).
2. Validates the value is one of `dev | uat | prod` via inner `case`.
3. Prints helpful exit-2 messages instead of letting `set -u` crash.

Lines 291 and 399 were Copilot misattributing the issue — those line numbers correspond to the `prompt_admin_token` function and the output-rendering loop, neither of which does positional shift on `$1`. Verified via `grep -n -- '--env'` returning only the canonical occurrence.

---

## ✅ #2 — A3 failure message has run-on em-dash (Low)

**File:** `scripts/keycloak/env-sync-verify.sh` line 382 (now line 395 after the line-58 expansion)

**Copilot:** `${violations}— delete` renders without spacing — fix to `${violations} — delete`.

**Resolution:** Fixed. Single-space-around-em-dash applied. Cosmetic but improves the table/JSON output readability.

---

## ⚠️ #3 — Personal Gmail in BF presentation (Medium)

**File:** `docs/presentations/medihub-burkina-faso-pitch-2026-05-13.md` slide 29 (Contact)

**Copilot:** Personal Gmail in git history of a public-facing pitch. Suggest replacing with role-based contact OR adding professional alongside.

**Resolution:** Applied Copilot's "add professional alongside" diff (didn't remove the personal). New line added below the Gmail:

```text
✉️ Contact professionnel : via le site projet ou sur demande
```

Net effect: pitch recipients have two contact channels, and future presentations can pivot to the professional-only pattern. The personal Gmail stays per the operator's earlier explicit choice (consented to its inclusion when the slide was authored). Operator can still strip it via a follow-up edit if they reconsider.

---

## ✅ #4 — Pin marp-cli version in .gitignore comment (Low)

**File:** `docs/presentations/.gitignore`

**Copilot:** `@latest` makes regenerations non-reproducible. Pin a specific version.

**Resolution:** Fixed. Changed `@marp-team/marp-cli@latest` → `@marp-team/marp-cli@4.4.0` (current latest at pinning time, verified via `npm view @marp-team/marp-cli version`). Added a comment explicitly instructing future maintainers to bump the pin deliberately rather than back to `@latest`.

---

## Net-net

| Severity | Count | Status |
| --- | --- | --- |
| Medium | 2 | ✅ both fixed (#1, #3) |
| Low | 2 | ✅ both fixed (#2, #4) |

False positives flagged in the round: 2 (Copilot's "this issue also appears at lines 291, 399" claim for #1 — verified via grep that no other `--env` parsing exists in the file).

Files touched in the follow-up commit:

- `scripts/keycloak/env-sync-verify.sh` (#1, #2)
- `docs/presentations/medihub-burkina-faso-pitch-2026-05-13.md` (#3)
- `docs/presentations/.gitignore` (#4)
- This file (audit trail)

No code or runtime behavior changes beyond the script fixes (#1 prevents crash, #2 cosmetic). The other two are documentation-only.
