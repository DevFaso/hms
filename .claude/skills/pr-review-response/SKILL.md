---
name: pr-review-response
description: Use when preparing a feature PR commit message, responding to Copilot AI review comments, addressing Sonar code-smell findings, or naming a feature branch. Captures HMS's foundation-pass commit style, branch-naming convention, "started vs completed" status discipline, and the specific lessons learned from recent reviews on PRs #331-#335.
---

# PR + review-response patterns

The team has settled into a specific commit-message + review-response
shape across PRs #331-#335. Follow it for any new PR — reviewers expect
this format.

## Branch naming

- `feat/v<X.Y>-<kebab-slug>` — new feature ranging across one roadmap row.
  Examples: `feat/v1.1-oru-r01-lab-persistence`,
  `feat/v2.0-hipaa-posture`.
- `fix/<scope>-<short-description>` — bug fix. Example:
  `fix/keycloak-railway-healthcheck-path`.
- `chore/<scope>-<description>` — non-feature, non-fix work (docs sync,
  dependency bumps, repo hygiene). Example:
  `chore/roadmap-sync-post-overnight`.
- `docs/<scope>-<description>` — pure documentation. Example:
  `docs/sonar-pr-queue-refresh`.

Always prefix the branch with the kind (`feat/` etc.) so the
filter-by-prefix workflow on the develop branch protections triggers
correctly.

## Foundation-pass vs completion

A feature PR almost always flips its roadmap row from `not-started → started`,
**not** `→ completed`. The "completed" transition happens later, in a
separate PR or batched roadmap-sync, once exit criteria are met (soak
run, auditor sign-off, etc.).

The roadmap CSV cell pattern (see the `roadmap-sync-workflow` skill):

> `<original deliverable>. Foundation pass shipped on <branch>
> (PR #N): <schema> + <key classes> + <feature flag> + <test count>.
> <Follow-on scope explicitly named>.`

## Commit message structure

A feature commit follows this shape:

```
<type>(<scope>): <one-line summary mentioning the roadmap row>

<2-3 sentence "what this is + what was deferred"> paragraph.

Schema (V##, strictly additive):
- <columns + indexes + constraints>

Code:
- <key classes + interfaces + their entry points>

Tests:
- <test class names + what they cover>

Documentation:
- <runbook + gap-doc updates>

Roadmap row <N> stays "started" until <follow-on>.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

Sections are optional when there's no content for them (a pure-docs
PR has no Schema / Tests sections). Keep the body wrapped to ~72
columns for readability in `git log` output.

## Review-response commit pattern

When addressing Copilot or Sonar review comments, the commit is its
own `fix(<scope>):` on the feature branch, structured by severity:

```
fix(<scope>): address Copilot review on PR #N (<row name>)

<short blurb naming the source of the review — Copilot AI / Sonar
Cloud / etc.>

<File1>:<Line> — <severity>:
- <One-sentence root cause>
- <One-sentence fix description>

<File2>:<Line> — <severity>:
- ...

<Off-scope comments that don't apply to this PR — explain why>.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

Common review-comment categories the team has seen:

- **Liquibase changelog missing** — V## SQL file added without
  `changelog.xml` registration. See the `liquibase-migration` skill.
- **Lazy-load on `Hospital.organization`** — dereferencing it outside
  the allowlist transaction throws `LazyInitializationException`. See
  the `hl7-mllp-integration` skill.
- **`integration_id` column overflow** — 120-char limit on
  `clinical.integration_message_event.integration_id`. Truncate at
  the synth point.
- **Encryption-claim overstatement** — the PHI inventory has been
  caught marking columns "encrypted" that lack `@Convert`. Always
  audit against `EncryptedStringConverter` usages.
- **Backlog count mismatch** — P0/P1 list lengths vs the header count.
- **Documented runbook doesn't exist** — referenced file not in repo.
  Mark `*(Pending — P0/P1, target …)*`.
- **AssertJ idiom** — `.isEqualTo(0L)` → `.isZero()`,
  `.isEqualTo(true)` → `.isTrue()`, etc.
- **HashMap initial capacity** — prefer `HashMap.newHashMap(n)` over
  `new HashMap<>(n)` (Java 19+).

## Off-scope comments — when to defer

Sonar and Copilot will sometimes flag files that are **touched** by
your PR but where the offending lines are pre-existing (not in your
diff). Verify with:

```bash
git diff origin/develop -- <file> | head
```

If the offending lines aren't in your diff, **defer to a separate
`fix(<scope>)` PR** and call it out explicitly in the response commit
message:

> The MLLP/Hl7MessageDispatcher Copilot comments on lazy hospital,
> casing normalization, integration_id length, etc. are noise on this
> PR — verified the diff against `origin/develop` touches X but NOT Y.
> Those issues are real and should be addressed in a follow-on
> `fix(hl7)` PR; tracked separately.

Don't silently expand the PR scope — it makes review harder and
inflates the diff against the original review.

## PR title vs commit subject

PR title matches the most representative commit subject (usually the
foundation commit). When there are multiple commits, the PR title
should:

- Lead with `<type>(<scope>):`
- Name the roadmap row
- Stay under ~72 chars

The PR body **should not duplicate the commit message** — GitHub renders
the commit messages right below. Use the body for:

- Reviewer-relevant rollup ("3 files changed, focus on X.java")
- Test plan checklist (manual smoke steps)
- Risk callouts
- Roadmap row reference + status transition statement

## Pre-commit hook

`.claude/settings.json` registers a pre-commit hook that runs
`hospital-portal` lint + format-check on **every** `git commit`. If
the hook fails:

1. **Fix the underlying issue** — usually `npm run format` cleans it.
2. Re-stage + re-commit. The hook re-runs against the new content.
3. **Never** `git commit --no-verify`. The hook exists because we got
   burned on inconsistent formatting in the past.

## Co-author tag

Every commit Claude authors carries:

```
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

This is the team's contribution-attribution convention. The model
version in the tag reflects the actual model used.

## Reference branches (recent exemplars)

- `feat/v1.1-oru-r01-lab-persistence` — full foundation-pass shape.
- `feat/v1.1-adt-admission-encounter-sync` — foundation-pass + Copilot
  fix commit + a CI-failure repair commit.
- `feat/v2.0-hipaa-posture` — docs-only foundation pass + Copilot fix.
- `feat/v1.1-fhir-write-api` — Patient PUT + conditional POST
  foundation; Encounter + Observation explicitly deferred in the
  cell text (row 20).
- `feat/v1.1-cds-hooks-public-discovery` — sandbox CORS allowlist +
  prefetch templates + five-case discovery IT (row 27).
- `feat/v2.0-synthetic-monitoring` — Blackbox-exporter + alert rules
  + runbook; multi-geo rollout explicitly deferred in the cell text
  (row 43).
- `feat/v1.1-kpi-dashboard-service` — KpiDashboardService + Controller
  + Angular sub-component shell; materialized-view conversion
  explicitly deferred (row 32).
- `chore/roadmap-sync-post-overnight` — batched roadmap sync after
  multiple merges.
- `chore/skills-update-post-4-picks` — repo-wide skills file refresh
  after four feature foundation passes (this branch's commit).
