# SonarCloud PR Queue — Status

> Tracks what has landed, what's deferred, and what needs manual
> action in the SonarCloud UI for the remediation effort kicked off
> against the snapshot in [docs/copilot-review.md](./copilot-review.md).
>
> **How-to-fix** lives in [docs/SonarQubeInstructions.md](./SonarQubeInstructions.md) —
> the playbook organised by pattern. This file is the **state**
> (per-PR landed/deferred + reasoning) so a reviewer doesn't have
> to re-derive the picture from the playbook + git log.
>
> Last refreshed: 2026-05-10.

## Snapshot

| | Start of campaign | Current |
|---|---|---|
| Vulnerabilities | 4 | **0** ✅ |
| Critical code smells closed | 0 | **~47** |
| False positives identified (need won't-fix in UI) | 0 | **5** |
| Open PRs on branches | 0 | **8** |

## Landed (8 PRs)

All branched off `develop@db504cbc`. Each PR's body documents its
verification (`./gradlew :hospital-core:test
:hospital-core:jacocoTestReport :hospital-core:jacocoTestCoverageVerification`
green, 5179/5179 backend tests, JaCoCo 80% gate passing).

| # | Branch | Commit | Pattern | Findings closed | Notes |
|---|---|---|---|---|---|
| 1 | `chore/sonar-pin-workflow-actions` | `406446cb` | 1a + 1b + 2 (S6321 + S6396) | 4 vulns + SHA-pinned all third-party actions | Closes every Vulnerability-class finding in the snapshot |
| 3 | `chore/sonar-securityconfig-path-constants` | `786e6433` | 5 (S1192) | 4 Critical — path-literal duplications in SecurityConfig | 13 inline literals → 3 new constants + 1 existing |
| 4 | `chore/sonar-role-literal-constants` | `169e0184` | 5 | ~22 Critical — role-string literals across EncounterController + ChatMessageServiceImpl + UserServiceImpl | Reuses existing `SecurityConstants` + adds `ROLE_PREFIX` |
| 5 | `chore/sonar-i18n-message-keys` | `50188246` | 5 | 6 Critical — i18n message keys + a non-i18n error prefix across 6 service impls | 25 inline literals → 6 constants |
| 6 | `chore/sonar-status-enum-literals` | `758e387e` | 5 | 6 Critical — DoctorWorklist / ClinicalDashboard / ResultReview status/urgency strings | 24 inline literals → 6 constants. DTO contract stays String (frontend-facing) so no enum migration. |
| 7 | `chore/sonar-transactional-self-invocation` | `351d9575` | 6 (S6809) | 5 Critical — `@Transactional` self-invocation in 5 service impls | Setter-injection of `@Lazy <Interface> self` (matches `SuperAdminDashboardServiceImpl` pattern). 3 Mockito unit tests required `service.setSelf(service)` in `@BeforeEach`. |
| 8 | `chore/sonar-auth-controller-split` | `27963529` | 7 (S107) | **0 directly** — Option C only | 4 `@Value` scalars folded into `AuthControllerProperties`. Constructor 21→18 params; **still above Sonar's 7 threshold**. Residual is intentionally accepted; must be marked won't-fix in UI. |
| 12+13 | `chore/sonar-modernization-and-housekeeping` | `18844696` | 8, 9, 10, 15, 18 | 8 mixed (lambdas → method refs, `Math.clamp`, nested-ternary extraction, unused imports, unused method param) | One pattern-match-guard attempt (S6884) failed to compile — Java 21 `when` requires type patterns, not enum constants. Reverted with documenting comment. |

### Total via landed PRs

- **4 Vulnerabilities → 0** ✅
- **~47 Critical code smells closed**
- **8 open PRs awaiting review**

## Deferred — remaining playbook PRs

Ordering follows the playbook's §"Suggested PR sequence" but
re-evaluated against current bandwidth and reviewer load.

| # | Playbook label | Pattern(s) | Effort | Risk | Why deferred |
|---|---|---|---|---|---|
| 9 | `refactor(service): reduce cognitive complexity (PatientSnapshot, PatientPortal)` | 3 (S3776) | ~4h | **High** | The two worst offenders: `PatientSnapshotServiceImpl:52` at **65→15** and `PatientPortalServiceImpl:892` at **40→15** + Brain Method. Each requires meaningful method extraction in clinical-data paths; reviewer load is real. Land after the current 8 PRs are reviewed. |
| 10 | `refactor(service): cognitive complexity sweep (the other 13)` | 3 | ~2h | Medium | 13 methods in the 16–29 cognitive-complexity range. Many follow the same "extract guard-clause" pattern but each one is its own decision. Smaller individual surface than #9. |
| 11 | `refactor(loops): replace multi-break/continue with stream pipelines` | 4 (S135) | ~3h | Medium | 8 files with loops containing >1 break/continue. Each is its own algorithm — `takeWhile` doesn't always translate cleanly. Some are CDS Hooks rule engines where the loop shape encodes the published clinical rule (qSOFA etc.) — those should be marked won't-fix, not refactored. |
| 14 | `refactor(config): externalize hard-coded URIs / path delimiters` | 17 (S1075) | ~2h | Medium | Touches SplunkHecAppender + AppointmentServiceImpl + PatientPortalServiceImpl + FileUploadService. Each URL needs an `application.yml` default + per-env override; ops needs to know before the PR lands. |
| 15 | `refactor(api): return empty collection (not null) from SubscriptionFeatureGate` | 11 | ~1h | Medium | Behaviour change at an API boundary (null → empty list). Every caller needs an audit; if any callers explicitly null-check, an empty list might change downstream logic. Tests required to prove no semantic regression. |
| 16 | `refactor(misc): merge ifs, single-return, deprecated javadoc, static-final` | 12, 13, 19, 20 | ~30 min | **Low** | Smallest mechanical batch left. Could be done alongside any other PR or as a quick capstone after #9–#11. |

### Recommended order

1. **Pause first** — let the 8 open PRs collect review before stacking more.
2. When ready: **PR #16** (lowest risk, 30 min) — quick win that signals
   activity without adding review burden.
3. **PR #15** (1h, 1 file but needs caller audit) — clears the
   Major-class "return empty collection not null" warning.
4. **PR #11** (3h, 8 files) — case-by-case loop refactors. Be
   prepared to mark some as won't-fix where the loop encodes a
   clinical-rule shape (see §"When NOT to fix" in the playbook).
5. **PR #10** (2h, 13 methods) before **PR #9** (4h, 2 worst-offender
   methods). Doing the easier cognitive-complexity ones first builds
   reviewer trust in the refactoring style.
6. **PR #14** last — touches infra config + needs ops coordination.

## False positives — needs "won't fix" in SonarCloud UI

These five findings are **not actionable in code** and need to be
suppressed via SonarCloud's UI with a comment referencing this doc.
Without that they'll keep generating noise in every future scan.

| File:line | Rule | Why it's a false positive |
|---|---|---|
| `FeatureFlagController.java:60` | S125 "commented-out code" | Prose comment (`// MVP-7b — null organizationId targets the legacy global row;`) explaining the call below. Not Java code. |
| `UserRepository.java:87` | S125 | Block comment explaining the PostgreSQL `cast(... as string)` workaround on JDBC bind types. Load-bearing for future readers. |
| `JwtAuthenticationFilter.java:133` | S125 | Defense-in-depth log-redaction note. Documentation, not code. |
| `EncounterServiceImpl.java:401` | S125 | Catch-block rationale + PII-redaction note for ops triage. Documentation. |
| `SmartPhraseServiceImpl.java:354,364` | S6884 "pattern-match guard" | Java 21 `when` guards only attach to type patterns (JEP 441), not bare enum constants. The Sonar suggestion is **not compilable** here — proven empirically in PR #12+13 (the attempt failed compile and was reverted). Inline comment in the file points back to this entry. |

### How to mark won't-fix

1. Open the issue in SonarCloud (filter by file + rule above).
2. Set status to "Won't Fix".
3. In the resolution comment, paste:
   ```
   Tracked in docs/sonar-pr-queue.md §False positives. Not actionable
   in code — see <file>:<line> inline comment or the playbook entry.
   ```

## Won't-fix items embedded in landed PRs

PRs #6 (status enum literals) and #8 (AuthController split) made
explicit "won't fix" calls inside their commit bodies:

- **PR #6** — status literals stay as `private static final String`
  rather than migrate to existing enums (`EncounterStatus`,
  `EncounterUrgency`, etc.) because the DTO contract is String-typed
  and the frontend would have to migrate in lockstep. Documented in
  each affected file's inline comment.

- **PR #8** — `AuthController` constructor still at 18 params after
  the `AuthControllerProperties` fold. Full helper extraction was
  Option A and was deferred as a separate feature-scope refactor.
  Pattern 7 in the playbook calls this out under §"When NOT to fix".
  **Action required**: mark the residual S107 + S3776 + Brain Method
  findings on AuthController as won't-fix in the UI.

## Cross-references

- [docs/SonarQubeInstructions.md](./SonarQubeInstructions.md) — **how
  to fix** each pattern + the original 16-PR sequence proposal
- [docs/copilot-review.md](./copilot-review.md) — raw export of the
  SonarCloud snapshot this campaign is working against
- [.github/workflows/build.yml](../.github/workflows/build.yml) — the CI
  workflow that runs SonarCloud on every push / PR
