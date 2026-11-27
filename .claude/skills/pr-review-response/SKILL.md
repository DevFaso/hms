---
name: pr-review-response
description: "Use before and after pushing ANY PR branch (the self-review gate: local CI gate, push as a draft, a review of every pushed head (Rex, or a reviewer following docs/agents/review-instructions.md), ready only when a round is clean and CI is green, and only by the session working directly with the user), when preparing a feature PR commit message, responding to review comments, addressing Sonar findings, or naming a feature branch. Captures HMS foundation-pass commit style, branch-naming convention, started-vs-completed status discipline, and the lessons from recent reviews."
---

# PR + review-response patterns

The team has settled into a specific commit-message + review-response
shape across PRs #331-#335. Follow it for any new PR — reviewers expect
this format.

## Self-review gate — draft until it passes

A PR gets one structured review before the user sees it as mergeable, and
it is this one. Copilot's review quota has been exhausted since 2026-09-05
and may or may not come back; when it does, its comments are findings for
step 3, not a substitute. The user merges PRs without warning, so the PR is
opened as a **draft** and made ready only at step 6, and only by the session
working directly with the user; a delegated agent hands back a draft
([Coordinator mode](#coordinator-mode--only-the-session-working-with-the-user-readies)).
Draft is a signal, not a lock (#509 was flipped to ready and merged), so
every push must be a state you would let merge, its tasklist bullet
included. Not "CI is green and there are no comments, so it is ready". What
the gate has caught that CI, Sonar and the unit suite did not is under
[Anti-patterns surfaced by self-review](#anti-patterns-surfaced-by-self-review).

0. **If the PR fixes a QA defect (a D-number) or a regression**, the
   `defect-fix-mandate` skill applies in full before step 1: the class is
   hunted across the whole repo, fixed at a choke point, guarded by a
   codebase-wide test, falsified, and re-run on the running app, and the PR
   body carries the **class table** (one row per defect id). A missing or
   thin row is a blocking finding at step 2, and the PR is never readied
   without it. "Fixed on the reported path" is NOT_FIXED.
1. **Before every push** run the
   [Mandatory branch CI gate](#mandatory-branch-ci-gate-run-before-pushing)
   for each side the diff touches. Push. First push:
   `gh pr create --draft --base develop --title "<subject>" --body-file <file>`
   (the tool is non-interactive; without the flags the command fails, and the
   default base is `main`, which is prod). Whoever pushes to a PR marked
   ready runs `gh pr ready --undo <PR#>` first, whoever's PR it is. CI runs
   on the pull-request event, so the runs appear seconds after that event,
   not the push: once `gh pr checks <PR#>` lists them, start
   `gh pr checks <PR#> --watch` in the background (~11 min, longer than a
   foreground tool call; started earlier it exits with "no checks
   reported").
2. Review at the pushed head. Rex reviews every head (`<!-- rex-review -->`):
   read its comment for THIS head. A reviewer agent you spawn follows
   `docs/agents/review-instructions.md`. Run `/code-review <PR#> high`
   (always with the PR number) only when the user asks for it or no other
   review exists for the head (it forks several reviewers; user rule
   2026-11-09). Confirm every finding against the real tree before acting — for code, grep the line, read the
   caller, run the test; for prose or config,
   `git diff origin/develop -- <file>` and check the claim against the file
   it cites. A finding that does not hold is dropped with a one-line reason
   (step 5).
3. Apply what holds in the working tree. A finding that widens the scope
   stays out of the fix: it becomes one bullet under **Standing platform
   debt** in `tasklist.md`, edited now so it lands in the step-4 commit (see
   [Off-scope comments](#off-scope-comments--when-to-defer)).
4. Commit as the self-review variant in
   [Review-response commit pattern](#review-response-commit-pattern), then
   back to step 1 (gate, push, watch) and step 2 reviews the new head. One
   pass through 1–4 is a round. Two rounds is the cap (user rule 2026-11-08).
   From round 3, fix only confirmed regressions, Highs, and anything that
   blocks a save or shows wrong clinical data. Record the rest under
   Standing platform debt, say so in the step-5 comment, and go on to step 6.
   Do not stop to ask (user rule 2026-10-09); a delegated agent hands back.
   The repository is public: a reviewer who finds a weakness that also
   exists on `develop` or `main` writes one line on the PR and sends the
   details privately to the maintainer (review-instructions, class N).
5. When a round yields nothing actionable, post one PR comment in three
   buckets: **taken** (grouped by angle, one line each), **dropped** (finding
   plus the one-line reason it does not hold), **noted, not in this PR** (the
   tasklist bullet). Update the PR body to describe the final shape, not the
   first cut.
6. When the watch finishes, read what arrived with it: Sonar's PR comment
   and issues (they land about ten minutes after the push, so never at step
   2), any review comment (Copilot when it is back), a red check. Each is a
   finding — step 3 — even when every check is green. Then, with the last
   round clean at the current head and every check green: `git status` is clean;
   `git rev-list --count origin/<branch>..origin/develop` is 0, else merge
   develop in locally and go back to step 1 — a conflicted merge re-enters
   after re-verifying `changelog.xml` registration (`MigrationRegistrationTest`)
   and the i18n files by hand, because a resolved conflict in either has
   dropped a migration before; a clean merge or a retry of a flaky check
   runs step 1 and skips the review round; `gh pr diff <PR#> --name-only`
   matches the description; and, right before handing back or readying,
   `gh pr view <PR#> --json headRefOid --jq .headRefOid` equals
   `git rev-parse HEAD`, because the user merges at whatever SHA GitHub
   shows. Then:
   - **Delegated** ([Coordinator mode](#coordinator-mode--only-the-session-working-with-the-user-readies)):
     the PR stays a draft; hand back the PR number, the head SHA, suite
     count, CI status, rounds and tasklist bullets.
   - **The session working directly with the user:** never ready while a
     finding is pending from any source — the user, a PR comment, Sonar, your
     own review of a delegated agent's PR, or a handback's deferred or
     unconfirmed items (#750's must-fix findings came from the coordinator's
     review). Each is taken, dropped with a reason, or deferred as in step 3.
     Then `gh pr ready <PR#>` and report: suite count, CI status, rounds,
     tasklist bullets.

### Coordinator mode — only the session working with the user readies

The readier is the session working directly with the user. If you did not
receive the task from the user directly, you are delegated: you do not
ready, and neither do the agents you hand work to. A coordinator that is
itself a subagent hands up like any other delegated agent.

A delegated agent still does everything its brief asks: at minimum step 1's
gate for each side the diff touches, plus falsification and the CI watch
when the brief asks for them. The review rounds (steps 2–5) and Sonar are
the coordinator's unless the brief delegates them.

- It never runs `gh pr ready`, even after a clean round. The PR stays a
  **draft**; step 6's head check, then hand back.
- A finding the coordinator sends enters at step 3 — confirm it in the
  tree, apply what holds — like Sonar and review comments. Findings can
  arrive while the agent's own round is still running, and they are
  invisible from inside it.

The coordinator reviews at the head the agent reported; if it is the
session working with the user it readies through step 6, otherwise it
hands up.

**Cost:** in one session six PRs were readied by their own agents over
findings the coordinator had sent minutes earlier; #750 was readied on the
exact commit carrying three unaddressed must-fix findings, and #738 was
merged in that state.

## Process lessons — delegated work

2026-09-26 wave — PRs #738 / #749 / #750 / #770.

- **A PR merged one round early gets its fixes on a new branch off develop,
  never on the merged branch** — nothing reviews or merges commits pushed
  there. The user merged #749 with its round still open; the rest of the
  round shipped as #758 off develop.
- **A coordinator instruction that turns a refusal into a silent fallback is
  reverted, not patched.** #770 round 1 applied one:
  `ActingContextArgumentResolver` answered an `X-Hospital-Id` the filter had
  rejected with the caller's primary hospital instead of refusing it. Round 2
  reverted the resolver and its tests to develop, because both consumers
  already refuse the value themselves.
- **A round's scope is bounded before it starts: "fix N, defer M, then hand
  back, or ready if you are the one accountable".** The deferred findings go
  where step 3 sends them. Without the bound the PR does not converge: #738
  grew to +2,302 lines across its rounds and was merged with must-fix
  findings still open.

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

Co-Authored-By: <the model that wrote the commit, e.g. Claude Opus 5.5> <noreply@anthropic.com>
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

Co-Authored-By: <the model that wrote the commit, e.g. Claude Opus 5.5> <noreply@anthropic.com>
```

The self-review variant (the gate above) keeps the shape; the subject,
the source line and the per-finding label change, and the off-scope line
names the tasklist bullets:

```
fix(<scope>): self-review of #N — <what changed, one line>

Review round <k> (<reviewer and marker>).

<File>:<Line> — <angle>:
- <root cause>
- <fix>

Noted, not in this PR: <tasklist.md bullet titles>.

<the tag from the Co-author tag section>
```

## Off-scope comments — when to defer

Sonar and Copilot will sometimes flag files that are **touched** by
your PR but where the offending lines are pre-existing (not in your
diff). Verify with:

```bash
git diff origin/develop -- <file> | head
```

If the offending lines aren't in your diff, **defer**: the fix stays out
of this PR, the finding becomes one bullet under **Standing platform debt**
in `tasklist.md` in the same PR, and the response commit message says so:

> The MLLP/Hl7MessageDispatcher Copilot comments on lazy hospital,
> casing normalization, integration_id length, etc. are noise on this
> PR — verified the diff against `origin/develop` touches X but NOT Y.
> Those issues are real; tracked as a Standing platform debt bullet in
> tasklist.md for a follow-on `fix(hl7)` PR.

Don't silently expand the PR scope — it makes review harder and
inflates the diff against the original review.

A PR comment or body is not a tracker: #563's follow-ups lived only in a
comment until 2026-09-05.

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

Every feature branch MUST pass the portal gates CI runs and the targeted
backend tests below, **before the PR is opened** — not after. The cost of fixing format / coverage
locally is seconds; the cost of fixing them via Copilot review +
re-push cycles is hours of reviewer attention. This is the single
highest-leverage discipline on the project.

Backend message keys live in per-area bundles,
`hospital-core/src/main/resources/i18n/messages-<area>{,_en,_fr,_es}.properties`
(areas in `config/MessageBundles.AREAS`; a key with no clear area goes to
`common`). Add a new key to its area's four files, never to a root
`messages.properties` (it no longer exists). `MessageBundleSplitTest` fails on
a key in two areas or a locale missing a key; a new area needs its four files
AND an entry in `MessageBundles.AREAS`.

**Frontend (`hospital-portal/`):**

```bash
cd hospital-portal
npm run lint             # eslint src/**/*.{ts,html}
npm run format:check     # prettier --check src/**/*.{ts,html,scss,md,json}
npm run i18n:parity      # every EN key present in FR and ES (strict)
npm run i18n:referenced  # every key a template uses exists
npm run i18n:translated  # FR values that are still the English string
npm run i18n:enums       # enum values the API sends with no PORTAL.ENUM key
npm run test:scripts     # the gate scripts own unit tests
npm run build            # AOT: the template type errors lint misses
npm run test:coverage    # Karma, ChromeHeadless, --watch=false, with coverage
npm run coverage:check   # the coverage ratchet CI enforces
```

That is the `lint-build-test` job of `frontend-ci.yml`, in its order. When
a step is added to that job, add it here in the same PR — this recipe
silently fell two gates behind once, and a contributor who runs it, sees
it exit 0 and pushes then fails CI on a gate the recipe promised to cover.
`e2e:a11y` is its own job; run `npm run e2e` locally when a smoke route
changed.

If `format:check` fails, run `npm run format` to auto-fix, then
re-stage. **Do not** push a branch with an unformatted file — the
frontend-ci.yml workflow will fail and you'll waste a Copilot review
cycle on a stylistic flag-down (this happened on
`feat/v1.1-kpi-dashboard-service` — `kpi-cards.component.ts` was
unformatted and tripped Prettier in CI).

**Backend (`hospital-core/`):** run the targeted tests for what you changed,
plus the trigger list in `docs/agents/implementation-brief-template.md`
(Part 2: the `@WebMvcTest` slices for a new filter, interceptor or MVC
configurer; `MigrationRegistrationTest` and `LiquibaseSchemaIT` for a
migration; `EntitySchemaValidationIT` for an entity; the architecture and
tenancy guard tests for a guarded area):

```bash
./gradlew --no-daemon :hospital-core:test --tests '<TargetedTest>' --tests '<Guard>'
```

The full backend suite, with `jacocoTestCoverageVerification`, runs in CI on
the draft PR and is the full-suite gate: wait for it on the pushed head before
handing back or readying. Several agents running the full suite at once
exhausted the build machine on 2026-11-25.

- One Gradle invocation at a time, in the foreground, with `--no-daemon`, so
  no JVM outlives it.
- **Never run `gradlew --stop`, and never kill Java by name**: it stops every
  agent's daemon and build on the machine, not only yours. Stop only the
  processes you started.

`jacocoTestCoverageVerification` enforces ≥ 80 % `INSTRUCTION`
coverage on the application classes — the threshold is hard-coded in
`build.gradle` (`limit { counter = 'INSTRUCTION'; minimum = 0.80 }`)
and the task fails the build if the new code drops the ratio.
Exclusions live in the same file (`**/bootstrap/**`, `**/seed/**`,
`**/config/**`, `**/model/**`, `**/payload/**`, `**/exception/**`,
`**/enums/**`, `**/mapper/**`, …) — when adding a class outside those
packages, you must back it with tests.

Compile success is not enough: run the tests that cover the change (PRs
#338, #341 and #343 relied on `compileJava` and failed CI).

**Quick recipe before every commit on a feature branch:**

```bash
# From repo root:
(cd hospital-portal && npm run lint && npm run format:check   && npm run i18n:parity && npm run i18n:referenced && npm run i18n:translated   && npm run i18n:enums && npm run test:scripts && npm run build   && npm run test:coverage && npm run coverage:check)   && ./gradlew --no-daemon :hospital-core:test --tests '<TargetedTest>'
```

When that command exits 0, commit. When it doesn't, fix and re-run —
do not skip past a failure.

## Older review lessons

The Copilot and Sonar batches of May 2026 are in
`references/review-history-2026-05.md`. Read the matching section when
Sonar or CodeQL flags S2077, S1192, S2229, cognitive complexity or
duplication on your PR. The failure classes reviewers look for today are
in `docs/agents/review-instructions.md`.

## Anti-patterns surfaced by self-review

2026-09-05 batch — PRs #563 / #564.

### Backend — ShedLock cannot lock a primitive-returning method, and the lock must outlive the transaction

`@SchedulerLock` only on `void` or boxed-return methods (`Integer`; null
means "skipped, another run holds the lock"). One locked entry point shared
by the scheduler and the manual trigger, no unlocked twin.
`@EnableSchedulerLock(order = Ordered.LOWEST_PRECEDENCE - 1)` so the lock
advisor wraps the transaction advisor and a lock cannot release before the
stamps commit. A skipped manual run answers 409 with `skipped: true`, never a
fake 0.

`SchedulerLockCoverageTest` enforces the return-type and shared-entry-point
rules; the unlocked twin is the proxy bypass described under
`@Transactional` self-invocation never works (in `references/review-history-2026-05.md`).

**Caught:** #563. Copilot's four comments on that PR overlapped the missing
imaging controller test, the fake-zero response and the wrapper
self-invocation; the lock-advisor ordering came only from the self-review.

### Backend — code that runs after the persistence context is closed passes ids, never reads associations

A `HandlerInterceptor.afterCompletion`, a scheduler tick after its
`@Transactional` method, or any other out-of-session code hands the
`REQUIRES_NEW` audit service ids only; the service reloads what it needs
under its own session. Do not add a second `@EntityGraph` finder for one
caller. Unit tests with mocked repositories cannot see a lazy proxy:
reproduce with a full-stack IT that asserts the audit row exists, or a mock
whose getter throws `LazyInitializationException`.

The call itself is the audit-wiring example in the `phi-encryption-audit`
skill; the same trap under another name is the Lazy-load trap in
`hl7-mllp-integration`.

**Caught:** #564. The first cut (a graph finder, a `Hibernate.isInitialized`
guard, an inner catch, two repository tests) protected a value the audit
service derives from the id anyway; the self-review collapsed it
(+173/−261, net −88 lines).

### Backend — H2 builds foreign keys the migrations never created

An `@ManyToOne` whose target rows are hard-deleted needs
`@NotFound(action = IGNORE)`. Before trusting an FK that the H2 suite
exercises, grep the migrations: `audit_event_logs.assignment_id` is indexed
(V33) but has no FK on Postgres, and `hbm2ddl` had built one on H2.

**Caught:** #564 self-review; the H2 suite could not have shown it.

### Backend — two emitters, one format

A column written by more than one path (`role_name` from the assignment vs
from the JWT) gets one normaliser; check the read-side twin
(`PatientAccessAuditInterceptor`) before adding a format.

**Caught:** #564 self-review — anchored rows had never reached the table
before, so the split had never been visible.

### Tests — an IT that leaves committed rows cleans up after itself

`REQUIRES_NEW` writes (audit rows) survive the test transaction. Clear them
in `@AfterEach` with `deleteAllInBatch()`, not only in `@BeforeEach`: sibling
`BaseIT` classes share the H2 database and delete the rows yours point at.
Passing only by class ordering is not passing.

**Caught:** #564 self-review, four angles independently.

### Backend — no generic helper methods

2026-09-12, the user's rule. A helper that takes `<T>` plus a function to
read it — `reachOf(Collection<T> rows, Function<T, UUID> hospitalIdOf,
UUID acting)` in `CrossHospitalReachRecorder` (#603) is the example — hides
at every call site which type is really being read and what is being
extracted from it; the lambda differs per caller and none of them is
checked by the compiler beyond "returns a UUID". Write instead:

- a method typed on what it actually needs — here the already-mapped ids,
  `reachOf(Collection<UUID> sourceHospitalIds, UUID acting)`, with the caller
  doing `rows.stream().map(r -> hospitalIdOf(r.getHospital())).toList()` in
  the open; or
- a small interface the rows implement (`HospitalScoped { UUID hospitalId(); }`)
  when the same shape recurs across entities; or
- typed overloads when there are two or three concrete types.

Generic *classes* that exist to be generic (`Page<T>`, `Optional<T>`, a
repository) are not the target; a generic *helper method* in application
code is. Spring Data derived finders keep their declared types.

**Caught:** the user, reading #603–#605. The `reachOf` refactor is a
Standing platform debt bullet in `tasklist.md`; new code follows the rule
from now.

## Co-author tag

Every commit Claude authors carries:

```
Co-Authored-By: <the model that wrote the commit, e.g. Claude Opus 5.5> <noreply@anthropic.com>
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
- `feat/v2.0-schema-per-tenant-scripts` — three operator scripts +
  cache-invalidate REST surface for the row-33 cutover, with the
  drain-before-copy + SAFE_REGEX-everywhere lessons codified in the
  follow-up commit (PR #356, 2026-05-17).
- `feat/v1.1-kpi-dashboard-follow-on` — P50 median + sparkline +
  axe-smoke for row 32, with the `T[]`-not-`Array<T>`, aria-label
  i18n, setParameter-constants, and split-aggregation-method
  lessons codified in the follow-up commit (PR #357, 2026-05-17).
- `feat/v1.1-adt-auto-create` — A01 Admission auto-create for
  row 24, with the cross-tenant-by-stamping-receiving-hospital,
  AcuityLevel-enum-values-in-runbook, and PENDING-vs-ACTIVE
  lessons codified in the follow-up commit (PR #358, 2026-05-17).
- `feat/v1.1-adt-auto-create-encounter` — A04 Encounter
  auto-create for row 24, with the SonarQube-duplication-on-new-code
  and Mockito-explicit-stub lessons codified in the round-1 follow-up
  commit (PR A04, 2026-05-17).
