# Briefing an implementation agent on e-Keneya HMS

This file has three parts. Part 1 is the brief you fill and send. Part 2 is the standing rules; the agent reads them from this file, so you never retype them and they cannot drift between briefs. Part 3 is two filled examples.

A filled brief is 250 to 450 words. If it is longer, the task is two PRs.

## Before you fill it

- **One brief, one narrow PR.** The PR fixes the defect and guards its class. A wider sweep is its own brief.
- **Write the rules for the cases the first draft will skip.** Review rounds here are spent on what the brief left open. #1336 went seven rounds on "what if the strength cannot be read", and #1352 two on "what counts as a local address". For each rule, state what happens in the cases below, or say "refuse".

  | The task touches | State the rule for |
  | --- | --- |
  | any check or parser | null, blank, unparseable or unknown input; a failed lookup |
  | data of a hospital | a caller with no hospital named; a caller from another hospital; "not yours" answers like "not found" |
  | sessions or the portal's stored state | reload, a second tab as another user, a locked tab, a failed refresh |
  | money | rounding, caps and exclusions, refunds, partial payment, the date the price applies, a reprint |
  | stock | both sides of a movement, units and packs, lots, two people acting at once |
  | clinical alerts or orders | the newest reading wins; nobody to notify; the way through when care is urgent |
  | notifications | delivery fails; nobody is eligible; the cause goes away |

- **Decide product questions before you send.** Research the general rule first, decide, and write the decision into the brief as a rule marked provisional. An agent that meets an open question takes the safest default and reports it; it does not stop to ask.
- **Allocate what collides:** the migration number, the worktree, the branch, and the files other open PRs own.
- **Model.** Opus at medium effort by default. Higher effort or Fable where a miss is costly: money, privacy, security fixes. Sonnet for mechanical work. A fresh agent per task; do not resume one for round after round.
- **After hand-back, you** verify `headRefOid` against the reported head, add the `rex-owned` label, read the review at that head, and make the merge call. The agent never readies or merges.

## Part 1. The brief

Copy from here. Replace every `<…>`. Delete a line that does not apply rather than writing "n/a".

```text
<One sentence: what to change, where, and why it matters. Name the severity and the kind of harm: patient safety, money, privacy, a blocked workflow.>

First read "Part 2. Standing rules" in `docs/agents/implementation-brief-template.md` (in your worktree) and follow it. Where this brief says otherwise, this brief wins.

Workspace: worktree <C:\dev\hms-<slug>>, branch <type/slug>, cut from origin/develop <sha7>. Scratch prefix <slug_>.
Model trailer: Co-Authored-By: <model name> <noreply@anthropic.com>

Evidence (read before coding):
- <docs/qa/dev-qa-run-<date>-r<N>-<area>.md, D<id>, D<id>>
- <C:\dev\hms-qa\scripts\r<N>-<area>\ for the script that reproduces it>
- <the review comment, plan or report this comes from>
- <private context, if any: path. Never quote or paraphrase it in the repo, a commit or the PR.>

What happens today: <the wrong behaviour in two or three sentences, with the file or endpoint if known>.

Required behaviour. Each rule gets a test that fails without the change.
R1. <Given … when … then …, stated so that a test can decide it.>
R2. <The refusal: who is refused, with which message key, in FR and EN.>
R3. <The unknown or unreadable case: what happens then.>
R4. <The surfaces that must show the result: screen, PDF, notification, invoice line.>

Class: <one sentence naming the pattern that lets this defect happen>. Find every instance in hospital-core and hospital-portal. Fix them at one shared point where possible.
Guard: <a test that scans or enumerates every such path and fails when a new one skips the rule. Name an existing guard to extend if there is one.>

Decisions already made: <provisional rules and their source; choices the agent must not reopen>.
Out of scope: <what to leave alone, and where it is tracked>.
Hands off: <paths and open PRs other work owns, e.g. service/billing/** (#1353)>. If the fix needs one of them, stop and report.
Migration: <none | V<number>, and the range you may use if a second is needed>.
Tests to run locally: <the targeted classes or specs; add "the @WebMvcTest slices" or "LiquibaseSchemaIT and EntitySchemaValidationIT (Docker)" when Part 2 calls for them>.
Running app: <the QA script or steps to re-run against dev or a local build | not required>.

Done when: R1–R<n> each have a passing test that was seen to fail without the change, the guard exists, CI is green on the pushed head, and the hand-back is sent. Then stop.
```

## Part 2. Standing rules

Read by every implementation agent. You are a delegated builder: you implement one brief in one worktree, open one draft PR, and hand back. The session that briefed you reviews, readies and merges.

### Workspace and machine load

- Work only in the worktree the brief names. Never modify `C:\dev\hms` or another agent's worktree. Never use `git stash`: all worktrees share one stash list, and another agent's pop takes your edits.
- The build machine ran out of process resources on 2026-11-25 with several agents building at once. Run one Gradle invocation at a time, in the foreground, with `--no-daemon`, so that no JVM outlives it. Run at most one headless browser. Never run `gradlew --stop` and never kill Java by name: other agents' builds die with yours. Stop only processes you started.
- Prefix every scratch file, temporary database and test fixture name you create outside the source tree with the scratch prefix from the brief.
- Run the targeted tests the brief lists, plus what the trigger list below adds. The full backend and portal suites run in CI on every push to the draft PR. That run is the full-suite gate, and you wait for it.

| You changed | Also run locally before pushing |
| --- | --- |
| a `Filter`, `HandlerInterceptor`, `WebMvcConfigurer` or `@ControllerAdvice` | every `@WebMvcTest` slice (`--tests '*ControllerTest'`) |
| a migration | `MigrationRegistrationTest`; with Docker, `LiquibaseSchemaIT` |
| an `@Entity`, a column or a persisted enum | with Docker, `EntitySchemaValidationIT` |
| anything in `hospital-portal` | `npm run lint`, `format:check`, `i18n:parity`, `i18n:referenced`, `i18n:translated`, `i18n:enums`, `build`, and the specs you touched |
| a tenancy, security or guard-tested area | the guard tests in `com.example.hms.architecture` and `security.tenant` that name it |

### Building the fix

- **Fix the class.** Write the class in one sentence, grep both modules for it, and fix every instance at a shared point where one exists. List every hit in the PR body, including those you judged not to be instances, with the reason. Keep to the class: no renames, reformatting or clean-up on the way.
- **Guard the class.** Add or extend a test that scans or enumerates the code base and fails when a new path skips the rule. A unit test of the fixed path is required as well, and it is not the guard.
- **Prove each test.** Revert only your production change, see the test and the guard fail, restore, and keep the first line of the failure for the PR body. Revert exactly what the PR changes: reverting more makes a redundant test look proven.
- **Never weaken a test to get green.** Do not delete, skip or loosen an existing assertion, and do not add an allow-list entry for code that is not actually safe. If an existing test asserted wrong behaviour, change it and say in the PR body which assertion changed and why.
- **Do not break what works.** Every caller of a shared method you change keeps or gains a test. Every change to a working path goes in the PR body under "Behaviour changes" as old → new, why, and who is affected.
- **No invented clinical content.** A threshold, score, dose limit or reference range comes from the code base or from a source the brief cites, and a new clinical rule is registered in `ClinicalRuleRegistry` as provisional. Otherwise leave a marked placeholder and report it.
- **An open product question does not stop you.** Take the safest default (refuse, or warn and record), implement it, and list it in the hand-back under "Decisions taken" with the alternative. Stop only when the brief's stop conditions apply.
- No `<T>` helper methods in `hospital-core`.

### Migrations

- Use only the number the brief allocates. Add the `.sql` file and a `<changeSet>` at the end of `changelog.xml`.
- A file with `DO $$ … $$` or any dollar quoting is registered with `splitStatements="false"`.
- Never edit a `V*.sql` that exists on `develop`, not even a comment: Liquibase checksums the file and the next deploy fails. A correction is a new migration.
- Every column the entity maps is in the migration, including `created_at` and `updated_at` for a `BaseEntity`. H2 builds tables from the entities and hides a missing column until deploy.
- Make every statement work on the rows production already has: a backfill or default before `NOT NULL`, duplicates handled before a unique index.

### Merging develop into your branch

- Never use GitHub's "Update branch" or the web conflict editor. Merge `origin/develop` locally. Never rebase or force-push.
- A merge that reports no conflict has still dropped changeSets and i18n keys here seven times. After any merge, run `python scripts/review/pr_mechanics.py <PR#>` once the merge is pushed, or `node scripts/merge/verify-merge.js <pre-merge HEAD>` before. Fix whatever is not `none`, `0` or `[]`.

### Text, privacy and the public repository

- Every user-visible string has French and English: portal JSON, or the four files of its `messages-<area>` bundle. The Spanish key must exist and may hold the English text. Double every apostrophe in a bundle value.
- Amounts are XOF integers.
- No patient data, phone number, token or secret in logs, fixtures, commits or PR text. Never send a real SMS or e-mail from a test or on dev.
- `DevFaso/hms` is public. Commits, the PR, code comments and test names describe the new behaviour only: no way to exploit anything, no id from a private assessment, no client name or cost.

### Commit, push, pull request

- Branch names: `fix/<scope>-<what>`, `feat/<scope>-<what>`, `sec/<what>`, `chore/…`, `docs/…`.
- Commit subject: `<type>(<scope>): <the behaviour, as a sentence>`, with defect ids in brackets. Example: `fix(pharmacy): check the dispensed product against the prescription (QA D3940, D3942)`. End every commit message with the model trailer from the brief.
- Commit before you report. Uncommitted work is lost when the PR merges.
- Push all your commits together, then open the PR once: `gh pr create --draft --base develop --title "<subject>" --body-file <file>`. The default base is `main`, which is production.
- The PR body has these sections, each present only when it has content: **What changes** (behaviour, per rule), **Class table** (for D-numbered defects, as the `defect-fix-mandate` skill defines it), **Behaviour changes**, **Tests** (with the falsification line), **Migration**, **Decisions taken**, **Remaining**. It ends with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- After every push, check that `gh pr view <n> --json headRefOid -q .headRefOid` equals `git rev-parse HEAD`.
- Never run `gh pr ready`, never merge, never push to `develop` or `main`.

### Review your own diff before the hand-back

Read `git diff origin/develop...HEAD` once more and answer each question. A "no" is fixed before you hand back, or reported under "Not done".

1. Does every new check refuse when its input is null, blank, unparseable or unknown, or when a lookup fails?
2. Does every new or changed query take its hospital from `ActingScopeResolver` or `RoleValidator`, inside the query? Is a caller with no hospital, or from another hospital, answered like "not found"?
3. Does every amount stay an integer, apply caps and exclusions on every path that creates a line, and subtract refunds wherever a total is shown?
4. Is the record of care still savable when an alert, a classification or a notification fails? Does every refusal on a care path have a governed way through?
5. Is every notification sent after commit or in its own transaction, and marked delivered only when someone received it?
6. Is every user action audited with the user and assignment, with ids only passed to code that runs after the request?
7. Does every field you added to a response respect masks on every way out: list, search, export, PDF, SMS, delegated access?
8. Does the portal send every field the server needs and offer only choices the server accepts? Do the route guard and `@PreAuthorize` admit the same roles?
9. Does each test fail without your change? Is any fixture missing the field that triggers the defect?
10. Is the migration registered once, numbered as allocated, with `splitStatements="false"` if it has a `$$`, and is no shipped migration touched?
11. Did you add a filter, interceptor or MVC configurer? Then did the `@WebMvcTest` slices pass?
12. Are French and English complete, and did a merge leave every i18n key and changeSet in place?
13. Would everything in the commits and the PR be safe for a stranger to read?

### Hand-back

Wait for CI on the pushed head with one background `gh pr checks <n> --watch`. Fix a failure your change caused and push once. Do not send progress reports while waiting. Then send exactly this and stop:

```text
PR #<n>, draft, base develop. Head <full sha>, equal to headRefOid: yes.
Rules: R1 <file:line> / <test>. R2 … (one line each; "not done" with the reason if so)
Class: <the sentence>. Instances: <n> fixed, <n> not instances (listed in the PR body). Guard: <test name>.
Falsified: <the first failing line with the change reverted>
Behaviour changes: <old → new, or none>
Migration: <V###, splitStatements=…, which schema ITs ran and where | none>
Local runs: <commands and counts>. CI: green | red: <job and cause>. Sonar: passed | failed: <condition> | pending.
Decisions taken: <question → default taken → alternative>
Not done / remaining: <items, each with where it is recorded>
Worktree clean: yes. Processes I started are stopped: yes.
```

### Stop conditions

Stop and hand back with the first line `BLOCKED: <reason>` when any of these holds:

- the fix needs a file under "Hands off", a migration number you were not given, or a merge of another open PR;
- the same test or gate has failed three times with different fixes;
- a rule in the brief contradicts the code's existing contract, or two rules contradict each other;
- the only way to green is weakening a guard test;
- the change would need a clinical, legal or pricing rule that neither the brief nor the code base supplies and that has no safe default.

Otherwise stop when the "Done when" line of the brief is true. If review findings are sent to you later, confirm each in the tree, fix what holds in one commit, push once, and hand back in the same format.

## Part 3. Two filled examples

Both are real briefs from 2026-11-25 and 2026-11-26, recast in the template. Lines marked † were not in the brief as sent. The † rules are what review found afterwards, over several rounds, which is the reason for the "cases the first draft will skip" table above. The other † lines (class, guard, hands-off, reserved migration number, running app) show what the template now asks for; the briefs as sent left them out or said "take the next free number".

### Example 1. A QA defect (became #1336)

```text
Fix QA defects D3940 (High, patient safety) and D3942 (Medium) in the pharmacy dispense flow, in hospital-core and hospital-portal: the product handed over is never checked against the prescription.

First read "Part 2. Standing rules" in `docs/agents/implementation-brief-template.md` (in your worktree) and follow it. Where this brief says otherwise, this brief wins.

Workspace: worktree C:\dev\hms-d3940, branch fix/dispense-product-match, cut from origin/develop 97e49876a. Scratch prefix d3940_.
Model trailer: Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>

Evidence (read before coding):
- docs/qa/dev-qa-run-2026-11-25-r77-pharmacy.md, D3940, D3942 (D3952 for context)
- C:\dev\hms-qa\scripts\r77-pharmacy\

What happens today: the lot picker preselects a 250 mg lot for a 500 mg order and shows no product or strength. The portal sends no product id, and the server checks the product only when the client names one. With substitution:true and a reason, the API accepts any product, and the allergy re-check looks only at the prescribed drug: a penicillin-allergic patient received amoxicillin in place of ibuprofen.

Required behaviour. Each rule gets a test that fails without the change.
R1. The server always derives the dispensed product from the chosen lots and compares it with the prescribed product. A mismatch without substitution is refused (message in FR and EN).
R2. A substitution is accepted only for the same active ingredient; any other product is refused. This rule is provisional: say so in the PR.
R3.† When a strength cannot be read or compared (missing, free text, mixed units, a combination product), the product does not count as matching: refuse, or require substitution. Compare every component of a combination. Normalise Unicode spaces before parsing.
R4.† When the order states a strength, the lot must have the same strength. Only an order with no strength keeps the older permissive match.
R5. Allergy and interaction checks run against the product actually dispensed, every time.
R6. The fill, price, queue row, hand-over card (D3942), invoice line and prescriber notification show the product actually dispensed, with the substitution flagged.
R7. The lot picker offers only lots of the prescribed product or valid substitutes, labelled with product, strength and expiry, and never preselects a mismatch.

Class: a dispense path that trusts the client to name the product. Find every instance: the portal endpoint, the API, ward supply, and counter sale when it is linked to a prescription.
Guard: a test that fails if any dispense or fill path does not go through the shared product check.

Hands off: the eMAR supply code (D3941 is being fixed in parallel by another agent).†
Migration: none expected. V375 is reserved for this PR if one is needed.†
Tests to run locally: the dispense service and controller tests, the new guard, the portal specs for the lot picker.
Running app: re-run C:\dev\hms-qa\scripts\r77-pharmacy\ for D3940 and D3942 against your local build.†

Done when: R1–R7 each have a passing test that was seen to fail without the change, the guard exists, CI is green on the pushed head, and the hand-back is sent. Then stop.
```

### Example 2. A hardening with private context (became #1352)

```text
Harden startup in hospital-core: in production the shared session store must be configured and validated, so that token revocations and login lockouts are shared by every replica.

First read "Part 2. Standing rules" in `docs/agents/implementation-brief-template.md` (in your worktree) and follow it. Where this brief says otherwise, this brief wins.

Workspace: worktree C:\dev\hms-session-store, branch sec/prod-session-store-required, cut from origin/develop. Scratch prefix sstore_.
Model trailer: Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>

Evidence (read before coding):
- Private context: <path outside the repository, given by the coordinator>. Never quote or paraphrase it in the repo, a commit or the PR. Public text says only: "in production the shared session store is required and validated at startup".
- #1326 added a startup safety check that refuses to boot without a profile or with placeholder secrets. Find it and extend the same mechanism, including its way of detecting production.

What happens today: dev, test and CI set none of the session-store settings and run on in-memory beans, which is intended. In production, a missing or mistyped value (for example "enable", which matches neither @ConditionalOnProperty) silently selects the same in-memory beans instead of failing.

Required behaviour. Each rule gets a test that fails without the change.
R1. In production, startup is refused, with a message naming the variable, when app.redis.token-blacklist.enabled is not exactly true, when app.auth.idle-tracking.enabled is not true, when the Redis host is empty or local, or when the port is not a valid integer.
R2.† "Local" means every loopback form: the whole 127.0.0.0/8 range, every spelling of IPv6 ::1, and any literal the JDK would resolve to loopback (a leading-zero octet such as 0127.0.0.1 is refused as ambiguous). Decide on the parsed address, never on the string.
R3. In every profile, a value for these booleans that is neither true nor false fails startup with a clear message. Prefer a validated @ConfigurationProperties boolean.
R4. Dev, test and CI, which set none of the variables, still start in memory. The existing startup-safety tests pass unchanged.
R5. Optional: at production startup, ping Redis once with a short timeout and fail fast if it is unreachable, but only if this cannot block a healthy start. Otherwise log an ERROR and expose a health indicator.
R6. The environment matrix under docs/ lists the variables as required for production.

Class: a production safety setting that falls back silently when it is missing or mistyped. Scope for this PR: the session-store settings only. List other instances you see under "Remaining" and do not fix them.†
Guard: extend the startup-safety test so that each required production setting has a refusal case.†

Migration: none.
Tests to run locally: the startup guard's unit tests and the existing startup-safety tests.
Running app: not required.

Done when: R1–R6 each have a passing test that was seen to fail without the change, CI is green on the pushed head, and the hand-back is sent. Then stop.
```
