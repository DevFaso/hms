---
name: defect-fix-mandate
description: "MANDATORY for any agent fixing a QA defect (docs/qa/dev-qa-run-*.md, D-numbered) or a review finding in HMS. The user's standing order (2026-10-27): fix the CLASS, not the instance; prove it with a codebase-wide guard; prove it on the running app; a PR without the class table is not a fix. Load before touching code for any D-numbered defect, and when reviewing such a PR."
---

# Defect-fix mandate — "never see the same issue on the next round"

Nine QA rounds re-found the same **classes** of bug on screens nobody had
tested: patient reschedule fixed (D717) but the receptionist's reschedule
discarded every edit (D923); registration link-match fixed (D725) but
same-name+DOB with another phone still duplicated (D924); 25 reports of
hard-coded English, 22 of raw enum codes, FEFO passing in round 10 and
failing in round 16 (D964). Each fix was real and each re-test passed, and
the user was still right: *"I feel like we are not really fixing them."*

A defect is a **symptom of a class**. The deliverable is the class gone from
the whole codebase, with a test that keeps it gone. Everything below is
checkable by a reviewer from the PR alone. If the PR cannot show it, the
defect is **not fixed**, whatever the local test says.

## The five obligations (every D-numbered defect, no exceptions)

1. **Name the class, then hunt it.** Before editing, write one sentence:
   *"Class: &lt;what pattern lets this happen&gt;"* (e.g. "an update handler that
   maps only some DTO fields onto the entity", "a server message built from
   an English literal", "a query keyed on the caller's home hospital instead
   of the request's effective hospital", "a dialog without role/aria-modal/
   Escape"). Then grep the **entire** repo — hospital-core *and*
   hospital-portal, main *and* test — for that pattern. Every hit is either
   fixed in the same commit or listed as "not an instance, because …".
   One screen, one endpoint, one message is never the whole fix.
2. **Fix at the choke point.** Prefer one shared implementation the whole
   codebase must go through (a shared matcher, formatter, picker, guard,
   message resolver, idempotency key) over N parallel patches. If N patches
   are unavoidable, say why in the PR body.
3. **Guard the class.** Add a test that scans or enumerates the whole
   codebase and fails when the class is reintroduced anywhere: a source-scan
   test, a parameterised test over every implementation/handler/template,
   an ArchUnit-style rule, or an extension of an existing guard
   (`npm run i18n:*`, the tenancy guard, `SchedulerLockCoverageTest`,
   `MigrationRegistrationTest`, the r16 guards in PR #970). A unit test of
   the one fixed path is required too, but it is **not** the guard. If a
   true guard is impossible, the PR body says why and what partial guard
   shipped. Known violations you could not fix in this PR go on the guard's
   explicit allowlist, each entry with its defect id — never a silent
   exclusion.
4. **Falsify exactly the change.** Revert only the production change (not the
   test) and watch the unit test *and* the guard fail; put the failing
   output's first line in the commit or PR body. A test that passes with the
   fix reverted is not a test.
5. **Prove it on the running app.** Re-run the QA evidence for the defect
   (the script/steps in `C:\dev\hms-qa\scripts\<round>\…` or an equivalent
   integration test that drives the real endpoint/screen) against your
   build, and the **sibling paths** the hunt in step 1 found. Local unit
   tests alone do not close a defect ([[verify-on-running-app-before-ready]]).

## Obligation zero — never break what already works

A fix that breaks a working path is worse than the defect: the user loses
a feature they had, and the next round finds it as a "new" defect. So:

- **The whole suite, every time.** Before every push: the full hospital-core
  suite (not just the touched module) and the full portal suite, plus
  `EntitySchemaValidationIT` whenever an entity changed. A red test that
  "looks unrelated" is a regression until proven otherwise; never skip,
  delete or weaken an existing test to get green. If an existing test
  must change, the PR body says which assertion changed and why the old
  behaviour was wrong.
- **Behaviour changes are declared.** Any change in what a working path
  does (a response shape, a status transition, a default, a message key,
  a permission, a sort order, a rounding) is listed in the PR body under
  **Behaviour changes** as `old → new, why, who is affected`. Undeclared
  behaviour change found in review is a blocking finding.
- **Choke-point fixes are proven on every caller.** When step 2 routes N
  paths through one shared implementation, each caller keeps or gains a
  test; the PR body lists the callers. A shared helper that silently
  changes a caller's behaviour is the most common way a fix breaks
  something that worked.
- **Re-run the positive paths.** The round's report has a **What worked**
  list per area. Before hand-back, re-run the ones your files touch (the
  same `C:\dev\hms-qa\scripts\<round>\<area>` scripts) and say so in the
  PR body. The combined-branch re-test re-runs **every** area's What-worked
  list from this and the earlier rounds, and any item that passed before
  and fails now is a blocking regression, whoever caused it.
- **Scope discipline.** The hunt in step 1 widens the fix to the class, not
  to "while I'm here" refactors. Renames, reformatting, dependency bumps
  and opportunistic cleanups go in their own PR.

## The class table — required in the PR body

One row per defect id. A reviewer rejects the PR if a row is missing or a
cell says "n/a" without a reason.

| Defect | Class (one sentence) | Instances found (file:line) | Fixed in | Guard test | Falsified (what failed) | Re-run evidence |
|---|---|---|---|---|---|---|

"Instances found" lists **every** hit of the hunt, including the ones judged
not to be instances, with the reason. A single-instance row for a class
that obviously recurs (English literals, raw enums, missing dialog
semantics, missing tenant scope) is a red flag the reviewer must challenge.

## Scope rules

- **A broken workflow the UI already offers is a defect, not a feature.**
  A menu entry, button, dialog or promise in a message that leads nowhere
  (D987 "Doublons patients", D961 certificate button, D974 discount) is
  fixed by making it work or by removing the dead promise — never deferred
  as "new feature" while the UI keeps advertising it.
- **Siblings are in scope by default.** If the same class exists in another
  branch's files, make the smallest fix there and flag it in the hand-back;
  do not leave it for "the next round". The file-ownership split exists to
  avoid merge conflicts, not to leave half a class unfixed.
- **Regressions are class failures.** A defect that was reported FIXED in an
  earlier round (grep `docs/qa/`) and reappears means the earlier fix had no
  guard. Add the guard now and say so in the row.
- **No clinical fabrication.** Thresholds, scores, instrument text and
  reference ranges come only from the codebase or a source the user
  supplied; a fix never invents them.

## For reviewers and re-testers

- `/code-review` and the self-review gate in `pr-review-response` treat a
  missing or thin class table as a **blocking** finding.
- A re-test never replays only the defect list: it re-runs the full area
  walk (the round's `scripts/<round>/<area>` pack and the earlier packs) and
  every sibling path named in the class table. A round is clean only when
  the whole day works, not when the listed ids pass.
- The verdict vocabulary is FIXED (class gone, guard present, evidence
  re-run), NOT_FIXED, or BLOCKED. "Fixed on the reported path" is NOT_FIXED.

## Why this is enforced, not advised

- One combined PR per QA wave ([[one-combined-pr-per-qa-wave]]) is only
  cheaper than N PRs if the wave does not come back. Every re-found class
  costs a full re-test cycle (setup, seven testers, consolidate, fix, deploy).
- The user does not merge "mostly fixed". A draft PR is readied only when
  every row of the class table is complete and the round-trip evidence is
  attached ([[always-review-prs]], [[fix-logic-security-findings-in-pr]]).
