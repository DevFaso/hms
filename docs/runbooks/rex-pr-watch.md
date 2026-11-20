# Rex brief: PR watch loop (e-Keneya, DevFaso/hms)

Written 2026-11-20 by Claude, for Rex/Codex. Run this every **10 minutes** while open PRs exist against `develop`.

## Each pass

For every open PR whose base is `develop`:

1. **Review.** Do this when the head SHA changed since your last review. Post ONE comment per head, marked `<!-- rex-review -->`. List only findings in these categories:
   - correctness;
   - security, tenancy or privacy;
   - money integrity;
   - a regression.

   Give a file:line and a one-line fix for each. No style-only findings.
2. **Ownership check.** Act on CI and conflicts **only** if the PR has the label `rex-owned`. Without the label, a Claude builder is still working on it: comment only, and never push.
3. **Red CI (rex-owned only).**
   - Fetch the failing tests (`gh pr checks`, the job logs).
   - Fix them for real in the PR branch.
   - **Never weaken, delete or skip a guard test.** Register an entry in a guard's allow-list only when the code is already safe, and write the true reason.
   - Re-run only `--failed` jobs for an infra flake, at most once.
4. **Conflict with develop (rex-owned only).**
   - Merge `origin/develop` into the PR branch **locally**. Never use GitHub's "Update branch".
   - Resolve append-only files with the scripts below, then verify. Code conflicts are resolved by hand, keeping both sides' intent.
5. **After any push:**
   - confirm `gh pr view <n> --json headRefOid` equals your HEAD;
   - comment what you changed, marked `<!-- rex-fix -->`.

## Never

- Never merge, mark ready, close, or push to `develop` or `main`.
- Never use "Update branch".
- Never use `git stash`: worktrees share it.
- Never edit an applied migration (`V*.sql` already on develop). A new migration needs a number Claude gives you in the PR body.
- Never touch Railway, prod, the dev database (read-only only), secrets, or SMS.
- Never print tokens or passwords.

## Merge tools

The scripts live in `scripts/merge/` (run them from the repository root, during the merge):

- `i18n-3way.js <pre-merge HEAD>`: the portal en/fr/es JSON, key by key, with exact formatting.
- `merge-3way-files.js <pre-merge HEAD> <files>`: `changelog.xml` (develop plus the PR's own changeSets), backend `.properties` (develop plus the PR's keys), and enum lists (both sides).
- `verify-merge.js <pre-merge HEAD>`: must report `missing 0 extra 0` for each locale, and `dups 0` for the changelog. Also parse `changelog.xml` with an XML parser.

## Why this matters

- A clean text merge has silently dropped i18n keys and changeSets here before, so check by count.
- Guard tests encode past defects; weakening one re-opens a bug class.
