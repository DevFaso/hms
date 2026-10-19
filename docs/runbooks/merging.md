# Merging PRs quickly

Two things used to make every merge cost 30-40 minutes: every PR appended to
the same four backend message bundles (so any two PRs conflicted), and CI ran
the whole ~10,000-test backend suite in one job.

## What is in place

- **Message bundles split by area.** Keys live in
  `hospital-core/src/main/resources/i18n/messages-<area>{,_en,_fr,_es}.properties`
  (areas: `config/MessageBundles.AREAS`). Two PRs only conflict when they add
  keys to the same area. `MessageBundleSplitTest` guards the split.
- **Sharded backend CI.** `backend-ci.yml` runs the suite in 3 parallel shards
  (`-PtestShardCount/-PtestShardIndex`, a deterministic hash partition of the
  test classes), each with 2 parallel Gradle test forks. The `build-and-test`
  job merges the shards' JaCoCo exec files and enforces the same 80% gate.
- **Path filters.** `project-quality.yml` skips the backend suite on a PR that
  touches only `hospital-portal/` or docs, and the portal job on a PR that
  touches nothing under `hospital-portal/` or `.github/`. `Require All Jobs
  Pass` treats a filtered-out job as passing and anything else that is not
  `success` as a failure. Pushes to main/develop and merge-queue runs always run
  everything. The SonarCloud analysis is NOT filtered: it runs on every PR (a
  portal- or docs-only PR is analysed without a JaCoCo report, which cannot
  fail the new-code coverage condition because such a PR adds no Java lines).

## Enabling the GitHub merge queue (repository admin)

The workflows already listen for `merge_group` (`project-quality.yml`,
`codeql.yml`), so the queue can run checks as soon as it is switched on.

1. **Settings → Rules → Rulesets** → open the ruleset targeting `develop`
   (today: "has rules"), or **New branch ruleset** targeting `develop`.
2. Enable **Require status checks to pass** and add the check
   **`Require All Jobs Pass`** (it aggregates backend, frontend, agent lint and
   YAML lint). Optionally add `Analyze (java-kotlin)` from CodeQL. These checks
   must also report on `merge_group` events, which they do.
3. Enable **Require merge queue**. Suggested settings: merge method *Merge
   commit* (the repo's convention), build concurrency 3-5, *Only merge
   non-failing pull requests*, minimum group size 1, wait time 5 minutes.
4. Save. From then on use **Merge when ready** on a PR instead of **Merge**:
   GitHub builds `develop` + the queued PRs on a temporary
   `gh-readonly-queue/develop/...` branch, runs the required checks there, and
   merges only if they pass. No more "update branch, wait for CI, merge".

Notes:

- Do not add a required check that the queue cannot produce (a job that only
  runs on `pull_request`), or every queued PR waits until it times out.
- The Sonar job runs on `merge_group` but posts nothing (it analyses only
  `push` and `pull_request`); keep it out of the required checks.
- Promoting develop to main remains a manual, user-directed merge (it is a
  production deploy); do not put `main` behind the queue without deciding that
  explicitly.
