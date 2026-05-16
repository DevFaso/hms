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
- **Repeated association literals / unused helpers** — when touching a
  service with `JpaProxyUtils.safeInit(...)`, promote association names
  that appear 3+ times (for example `"patient"`, `"department"`) to
  private constants near the other local constants, and remove stale
  private helpers in the same pass.

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

## Mandatory branch CI gate (run BEFORE pushing)

Every feature branch MUST pass the same four gates CI runs, **before
the PR is opened** — not after. The cost of fixing format / coverage
locally is seconds; the cost of fixing them via Copilot review +
re-push cycles is hours of reviewer attention. This is the single
highest-leverage discipline on the project.

**Frontend (`hospital-portal/`):**

```bash
cd hospital-portal
npm run format:check   # prettier --check src/**/*.{ts,html,scss,md,json}
npm run lint           # eslint src/**/*.{ts,html}
npm test               # jest unit + spec
```

If `format:check` fails, run `npm run format` to auto-fix, then
re-stage. **Do not** push a branch with an unformatted file — the
frontend-ci.yml workflow will fail and you'll waste a Copilot review
cycle on a stylistic flag-down (this happened on
`feat/v1.1-kpi-dashboard-service` — `kpi-cards.component.ts` was
unformatted and tripped Prettier in CI).

**Backend (`hospital-core/`):**

```bash
./gradlew :hospital-core:test :hospital-core:jacocoTestReport :hospital-core:jacocoTestCoverageVerification
```

`jacocoTestCoverageVerification` enforces ≥ 80 % `INSTRUCTION`
coverage on the application classes — the threshold is hard-coded in
`build.gradle` (`limit { counter = 'INSTRUCTION'; minimum = 0.80 }`)
and the task fails the build if the new code drops the ratio.
Exclusions live in the same file (`**/bootstrap/**`, `**/seed/**`,
`**/config/**`, `**/model/**`, `**/payload/**`, `**/exception/**`,
`**/enums/**`, `**/mapper/**`, …) — when adding a class outside those
packages, you must back it with tests.

If `:hospital-core:test` fails locally, CI will fail the same way —
the recent foundation-pass batch had failing tests in three of the
four feature branches (PRs #338, #341, #343) because contributors
relied on `compileJava` passing rather than the full test task.
**Compile success is not enough; the full test task is the gate.**

**Quick recipe before every commit on a feature branch:**

```bash
# From repo root:
(cd hospital-portal && npm run format:check && npm run lint && npm test) \
  && ./gradlew :hospital-core:test :hospital-core:jacocoTestCoverageVerification
```

When that command exits 0, commit. When it doesn't, fix and re-run —
do not skip past a failure.

## Anti-patterns surfaced by Copilot review (2026-05-16 batch)

These caused real review comments on the four daytime foundation-pass
PRs (#338, #341, #342, #343). Each is captured in the relevant domain
skill — this list is for cross-cutting muscle memory.

- **Cross-branch broken doc links.** A `chore/roadmap-sync` PR cannot
  reference runbooks that live only on a parallel feature branch —
  Copilot will flag the link as broken on the sync PR's diff base.
  Either land the runbooks first (squash-merge the feature branches),
  or open the sync PR against a branch that already includes them.
  See PR #339.
- **Feature flag short-circuit ordering.** Provider methods that
  validate request shape (body, id consistency) before calling the
  feature-gated service will return 422 even when the flag is off —
  contradicting the "flag-off ⇒ 405" contract and breaking the
  flag-off ITs. Always
  `if (!writeService.isEnabled()) throw MethodNotAllowedException`
  at the very top of every gated handler. See PR #343.
- **Global CORS instead of path-scoped.** Adding sandbox / partner
  origins to the global `CorsConfigurationSource` widens cross-origin
  access for every PHI-bearing endpoint, not just the one you
  intended. Register a path-scoped `CorsConfiguration` (e.g. for
  `/cds-services/**` only) when the new origins are endpoint-specific.
  See PR #338.
- **`@Value` defaults vs env override = replace, not append.** When an
  operator sets `APP_SOMETHING=value`, Spring replaces the entire
  default list — it does not extend. Runbook and code comments that
  claim "add" or "extend" are wrong; either rewrite the prose to say
  "replace" or compose defaults in code so additions truly append.
  See PR #338.
- **Tests that only check key presence.**
  `assertThat(json.has("vitals")).isTrue()` passes even if the FHIR
  template lost `{{context.patientId}}` or the `_count=2000` filter.
  When the wire shape IS the contract, assert the values, not just
  the keys. See PR #338.
- **Audit `entityType` capitalization.** `AuditEventLogServiceImpl`
  special-cases `entityType` matching `"PATIENT"` (case-insensitive
  against that literal), so passing `"Patient"` (Pascal case)
  silently disables the patient-resolution path. Use the same literal
  the rest of the codebase uses — `"PATIENT"`. See PR #343.
- **`mrn <> ''` doesn't exclude whitespace.** Partial unique index
  predicates that want to skip blank values must `btrim(mrn) <> ''`
  (or equivalent) — `<> ''` still includes `'   '`. See PR #343.
- **Catch-all RxJS error swallow.**
  `pipe(catchError(() => of({} as T)))` in a frontend service renders
  401 / 403 / 500 as "no data" cards, hiding outages from operators.
  Either let the error propagate to the component or return a typed
  error state. See PR #341.
- **Component reachability mismatch.** Embedding a component inside a
  route guarded by `ROLE_SUPER_ADMIN` while the backing endpoint is
  also exposed to `HOSPITAL_ADMIN / DOCTOR / NURSE / STAFF` makes the
  new UI unreachable for its real users. Place the component on a
  route whose guard matches the backend's `@PreAuthorize`. See PR #341.
- **Date-window cap off-by-one.** For an inclusive `[from, to]` window,
  `from.plusDays(N).isBefore(to)` allows `N+1` days. Use
  `from.plusDays(N - 1).isBefore(to)` if you mean "at most N inclusive
  days". See PR #341.
- **Alert runbook anchors that don't exist.** A `runbook_url` fragment
  like `#hmssyntheticprobefailurerate` must match a heading in the
  linked runbook; otherwise responders land on the top of the page
  instead of on alert-specific guidance. Either add the anchor or drop
  the fragment. See PR #342.
- **Wide-open exporter port.** `9115:9115` (or any `:port:port`
  binding) on a docker-compose service exposes that port on every host
  interface — for the blackbox exporter specifically, that's an SSRF /
  scanning primitive on the LAN. Use `127.0.0.1:9115:9115` for
  local-only ports. See PR #342.
- **Hard-coded credentials in runbooks.** Even local-smoke `username` /
  `password` literals get copied into shared environments. Use env
  placeholders (`$env:HMS_SMOKE_PASSWORD`) instead. See PR #341.

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
