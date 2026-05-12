# SonarCloud PR Queue — Status

> Tracks what's merged, what's deferred, and what needs manual
> action in the SonarCloud UI for the remediation effort kicked off
> against the snapshot in [docs/copilot-review.md](./copilot-review.md).
>
> **How-to-fix** is the per-pattern playbook in
> [docs/SonarQubeInstructions.md](./SonarQubeInstructions.md).
> The playbook landed on `develop` via PR #299 (the merge of
> `chore/sonar-pin-workflow-actions`).
>
> This file is the **state** (per-PR merged/deferred + reasoning) so a
> reviewer doesn't have to re-derive the picture from the playbook +
> git log.
>
> Last refreshed: 2026-05-11 (round 2 — three follow-up branches pushed
> covering the previously-deferred PRs #9, #10, #11, #14, #15, #16. All
> three branches are green against the backend gate locally; awaiting
> PR creation + GitHub UI merge).

## Snapshot

| Metric | Start of campaign | After round 1 (8 chore PRs) | After round 2 (3 follow-ups) |
| --- | --- | --- | --- |
| Vulnerabilities | 4 | **0** ✅ | **0** ✅ |
| Critical / Major code smells closed (cumulative) | 0 | ~47 | **~62** |
| False positives identified (need won't-fix in UI) | 0 | 5 | 5 |
| PRs **merged** to `develop` (cumulative) | 0 | **8** ✅ | **8** ✅ |
| Follow-up branches **prepared / pending PR + merge** | 0 | 0 | **3** |

## Merged into `develop` (8 PRs)

All branched off `develop@db504cbc` and merged via the GitHub UI
(PRs #299–#306). Each PR's body documents its verification — the full
backend gate is:

```bash
./gradlew :hospital-core:test :hospital-core:jacocoTestReport :hospital-core:jacocoTestCoverageVerification
```

— and landed green on every PR (5179 / 5179 backend tests, JaCoCo 80%
gate passing).

> **Note on numbering** — the playbook in `SonarQubeInstructions.md`
> originally proposed a sequence of 16 PRs. PR **#2** in that sequence
> ("Move workflow-level permissions to job level") was folded into
> PR #1 once execution started, since both findings live in the same
> three workflow files and SHA-pinning + permission relocation are
> reviewed together cleanly. Hence the playbook-PR numbering below jumps
> from 1 to 3, and PRs #12 + #13 were combined into a single low-risk
> housekeeping batch. (The "GH PR #" column on the right is the actual
> GitHub PR number assigned at merge time.)

| Playbook # | GH PR | Branch | Pattern | Findings closed | Notes |
|---|---|---|---|---|---|
| 1 | [#299](https://github.com/DevFaso/hms/pull/299) | `chore/sonar-pin-workflow-actions` | 1a + 1b + 2 (S6321 + S6396) | 4 vulns + SHA-pinned all third-party actions | Closes every Vulnerability-class finding in the snapshot. Folds in original PR #2 (workflow → job-level perms). |
| 3 | [#298](https://github.com/DevFaso/hms/pull/298) | `chore/sonar-securityconfig-path-constants` | 5 (S1192) | 4 Critical — path-literal duplications in SecurityConfig | 13 inline literals → 3 new constants + 1 existing |
| 4 | [#300](https://github.com/DevFaso/hms/pull/300) | `chore/sonar-role-literal-constants` | 5 | ~22 Critical — role-string literals across EncounterController + ChatMessageServiceImpl + UserServiceImpl | Reuses existing `SecurityConstants` + adds `ROLE_PREFIX` |
| 5 | [#301](https://github.com/DevFaso/hms/pull/301) | `chore/sonar-i18n-message-keys` | 5 | 6 Critical — i18n message keys + a non-i18n error prefix across 6 service impls | 25 inline literals → 6 constants |
| 6 | [#302](https://github.com/DevFaso/hms/pull/302) | `chore/sonar-status-enum-literals` | 5 | 6 Critical — DoctorWorklist / ClinicalDashboard / ResultReview status/urgency strings | 24 inline literals → 6 constants. DTO contract stays String (frontend-facing) so no enum migration. |
| 7 | [#303](https://github.com/DevFaso/hms/pull/303) | `chore/sonar-transactional-self-invocation` | 6 (S6809) | 5 Critical — `@Transactional` self-invocation in 5 service impls | Setter-injection of `@Lazy <Interface> self` (matches `SuperAdminDashboardServiceImpl` pattern). 3 Mockito unit tests required `service.setSelf(service)` in `@BeforeEach`. |
| 8 | [#305](https://github.com/DevFaso/hms/pull/305) | `chore/sonar-auth-controller-split` | 7 (S107) | **0 directly** — Option C only | 4 `@Value` scalars folded into `AuthControllerProperties`. Constructor 21→18 params; **still above Sonar's 7 threshold**. Residual is intentionally accepted; must be marked won't-fix in UI. |
| 12+13 | [#306](https://github.com/DevFaso/hms/pull/306) | `chore/sonar-modernization-and-housekeeping` | 8, 9, 10, 15, 18 | 8 mixed (lambdas → method refs, `Math.clamp`, nested-ternary extraction, unused imports, unused method param) | Combined as one batch (lower review burden than two separate PRs). One pattern-match-guard attempt (S6884) failed to compile — Java 21 `when` requires type patterns, not enum constants. Reverted with documenting comment. |

### Total via merged PRs

- **4 Vulnerabilities → 0** ✅
- **~47 Critical code smells closed and merged to `develop`**
- **All 8 chore PRs merged via the GitHub UI on 2026-05-11**

## Round 2 — three follow-up branches (pushed 2026-05-11, awaiting GH PR + merge)

Round 1 left six deferred playbook rows (PRs #9, #10, #11, #14, #15, #16).
Rather than push six separate PRs, those rows were grouped into **three
risk-tiered branches** so reviewers see a clean low → medium → high
escalation. Every branch was independently verified against the backend
gate before commit:

```bash
./gradlew :hospital-core:test :hospital-core:jacocoTestReport :hospital-core:jacocoTestCoverageVerification
```

— 5179 / 5179 tests pass, JaCoCo 80% gate green on each branch.

| Branch | Playbook PR rows | Pattern(s) | Files | Risk | Notes |
| --- | --- | --- | --- | --- | --- |
| `chore/sonar-housekeeping-modernization` | #16 | 12, 13, 19, 20 | 5 | **Low** | Mechanical sweep: single-return, merge-nested-if, `@deprecated` Javadoc, `static final` promotion. No behaviour change. |
| `refactor/sonar-null-hygiene-config-uris` | #15, #14 | 11 (S1168), 17 (S1075) | 5 | Medium | `SubscriptionFeatureGateServiceImpl` switched to `Optional<Set<String>>` internally (Set.of() would have **inverted** the gating semantic — `null` here meant "ungated/passthrough", not "no keys"). URI fragments moved to `@Value` with current values as defaults; ops can override per env. |
| `refactor/sonar-cognitive-complexity` | #9, #10 (partial), #11 (partial) | 3 (S3776), 4 (S135) | 6 | **High** | `PatientSnapshotServiceImpl#getSnapshot` 65→~5 via 8 section helpers; `PatientPortalServiceImpl#notifyCareTeamForRefillRequest` 40→~5 (also clears Brain Method) via 5 extracted helpers + a `Recipients` record. Plus `AuditEventLogServiceImpl#doLogEvent`, `DepartmentServiceImpl#searchDepartments`, `PatientDocumentMapper#toDto`, and `ResultReviewServiceImpl#getResultReviewQueue` (2-continue loop → stream filter). |

### What's intentionally left for a later PR

- **Pattern 14 (useless curly braces)** at `ResultReviewServiceImpl.java:143,180` — team policy keeps braces for single-line `if` to prevent goto-fail-style bugs. Mark won't-fix in SonarCloud UI.
- **Pattern 4 CDS-Hooks rule classes** — `SepsisQsofaProtocolRule`, `MedicationAllergyCdsService`, `MedicationPrescribeRulesCdsService`, `RxNormCodingExtractor`, `CdsHookContext`. Per playbook §"When NOT to fix" the loop shape encodes the published clinical rule (qSOFA, drug-allergy precedence etc.). Mark won't-fix with a link to the source rule.
- **Pattern 3 remaining methods** — about 9 of the 13 cognitive-complexity-16–25 methods in PR row #10 plus the AuthController residual (already documented in PR #8 / GH #305) are not in branch 3. They form a clean residual sweep for a follow-up "PR #10b" once the round-2 branches are reviewed.
- **`DoctorWorklistServiceImpl` loops at L165/181/197 (PR row #11)** — the line numbers in the playbook predate PR #6 (GH #302); those sites were touched during the status-enum-literal extraction and need a fresh look before being refactored.

### Round-2 branch summary by playbook PR row

> Two-stage status: **Implemented on branch** is what the local commit
> actually contains (verified by the backend gate); **Merged to develop**
> only flips after the GitHub PR is reviewed and merged. None of these
> are merged yet, so the right-hand column stays at ⏳ until the
> per-PR ✅ tick replaces it.

| Playbook # | Implemented on branch | Merged to `develop` | Branch |
| --- | --- | --- | --- |
| 9 (worst-offender cognitive) | ✅ Both methods refactored | ⏳ Pending PR review | `refactor/sonar-cognitive-complexity` |
| 10 (cognitive sweep, 13 methods) | ⚠️ Partial — 3 of 13 done (`AuditEventLog#doLogEvent`, `DepartmentService#searchDepartments`, `PatientDocumentMapper#toDto`); the rest are queued as a residual "PR #10b" | ⏳ Pending PR review | `refactor/sonar-cognitive-complexity` |
| 11 (loop refactors) | ⚠️ Partial — `ResultReviewServiceImpl#getResultReviewQueue` done; CDS-Hooks classes marked won't-fix; `DoctorWorklistServiceImpl` needs re-baselining | ⏳ Pending PR review | `refactor/sonar-cognitive-complexity` |
| 14 (externalize URIs) | ✅ Done | ⏳ Pending PR review | `refactor/sonar-null-hygiene-config-uris` |
| 15 (null → empty collection) | ✅ Done (via `Optional<>` for semantic preservation) | ⏳ Pending PR review | `refactor/sonar-null-hygiene-config-uris` |
| 16 (misc housekeeping) | ✅ Done | ⏳ Pending PR review | `chore/sonar-housekeeping-modernization` |

### Suggested merge order on GitHub

1. `chore/sonar-housekeeping-modernization` — smallest review surface, low risk, builds reviewer confidence.
2. `refactor/sonar-null-hygiene-config-uris` — single semantic-preserving refactor + additive config keys (no infra change required because all defaults match prior hard-coded values).
3. `refactor/sonar-cognitive-complexity` — largest diff, but each extracted helper is independently named and short. Recommend reading file-by-file.

Branches 2 and 3 touch overlapping files (`AppointmentServiceImpl`, `PatientPortalServiceImpl`, `FileUploadService`) — merge in the order above and the second/third will need a trivial rebase against `develop`.

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

## Won't-fix items embedded in merged PRs

Playbook PRs #6 (GH #302, status enum literals) and #8 (GH #305,
AuthController split) made explicit "won't fix" calls inside their
commit bodies:

- **PR #6 (GH #302)** — status literals stay as
  `private static final String` rather than migrate to existing enums
  (`EncounterStatus`, `EncounterUrgency`, etc.) because the DTO
  contract is String-typed and the frontend would have to migrate in
  lockstep. Documented in each affected file's inline comment.

- **PR #8 (GH #305)** — `AuthController` constructor still at 18
  params after the `AuthControllerProperties` fold. Full helper
  extraction was Option A and was deferred as a separate feature-scope
  refactor. Pattern 7 in the playbook calls this out under §"When NOT
  to fix". **Action required**: mark the residual S107 + S3776 + Brain
  Method findings on AuthController as won't-fix in the UI.

## Cross-references

- [docs/SonarQubeInstructions.md](./SonarQubeInstructions.md) —
  **how to fix** each pattern + the original 16-PR sequence proposal.
  Landed on `develop` via PR #299.
- [docs/copilot-review.md](./copilot-review.md) — raw export of the
  SonarCloud snapshot this campaign is working against
- [.github/workflows/build.yml](../.github/workflows/build.yml) — the CI
  workflow that runs SonarCloud on every push / PR
