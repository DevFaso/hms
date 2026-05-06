# Copilot review archive

## 2026-05-04 — `feature/super-admin-mvp-c2-frontend`

Six Copilot findings on the MVP-c2 frontend PR (the four UI surfaces:
MVP-3b probe/resync/history, hospital-lifecycle, MVP-9c policy editor,
MVP-8c aggregation tab + saved-search REST migration). All addressed
in a follow-up commit on the same branch before merging.

### 1. Missing `REGION_POLICY.COL.*` i18n keys — **High**

> The template references translation keys like
> `REGION_POLICY.COL.REGION/RETENTION/EXPORT/DEPLOYMENT/UPDATED`,
> but no `REGION_POLICY.COL` entries exist in the i18n JSON files.
> This will render raw keys in the UI.

**Fix.** Added a complete `REGION_POLICY.COL.{REGION,RETENTION,
EXPORT,DEPLOYMENT,UPDATED}` block in en/fr/es so the policy table
column headers translate.

### 2. `migrateLegacyEntries()` lacks per-upload `catchError` — **Major**

> `migrateLegacyEntries()` claims to swallow per-entry failures, but
> each upload only uses `map(...)` and does not `catchError`. If any
> `create()` call errors, `forkJoin` will error the whole migration,
> potentially causing successful uploads to be retried later
> (duplicate server rows) and preventing the legacy key from being
> cleared.

**Fix.** Each upload is now wrapped in `catchError(() => of({ ok:
false }))` so a failed row falls through as a discriminated-union
miss instead of erroring the `forkJoin`. The synchronous-throw path
in `create()` (blank name) is also caught with a try/catch around
the pipe. New "all-failed → keep legacy key" branch leaves the
entries for retry; "partial success → clear key" branch prevents
duplicate server rows on a re-run.

**Tests.** Two new specs:
`migrateLegacyEntries() partial failure: keeps the legacy key when
ALL uploads fail` (asserts the legacy key + flag stay untouched)
and `… clears the legacy key when at least one upload succeeded`
(asserts the result has only the surviving row and the legacy key
is cleared).

### 3. Probe-error key shown for re-sync failures — **Minor**

> `finishAction()` sets `errorKey` to `INTEGRATION_HEALTH.PROBE.ERROR`
> for any null result, including `resync()`. This will show a
> probe-specific error message for re-sync failures.

**Fix.** `finishAction(integrationId, kind, result)` now takes the
action kind. `errorKey` resolves to `INTEGRATION_HEALTH.PROBE.ERROR`
for probe failures and `INTEGRATION_HEALTH.RESYNC.ERROR` for
re-sync failures. New `RESYNC.ERROR` i18n key added in en/fr/es.

**Test.** New `resync() failure surfaces the RESYNC error key, not
PROBE` spec asserts the new behavior.

### 4. `refresh()` flips loading off on lifecycle alone — **Minor**

> `refresh()` sets loading to false only when the lifecycle request
> completes, while the hospital request runs independently. This can
> clear the loading state before the hospital is loaded.

**Fix.** Coordinated both fetches with `forkJoin`; loading flips
off only after both observables emit. Each side is still wrapped
in `catchError` so a single failure is recoverable. Refactored the
nested-ternary action dispatch into a `dispatchLifecycleAction()`
helper to satisfy SonarTS S3358.

### 5. Open-detail link routes super-admin-only path for everyone — **Major**

> The hospital list is accessible to multiple roles, but the new
> "open detail" link routes to `/hospitals/:id`, which is guarded as
> `ROLE_SUPER_ADMIN` only. Non-super-admin users will see the icon
> but be blocked on navigation.

**Fix.** `HospitalListComponent` now exposes
`isSuperAdmin = roleContext.isSuperAdmin` and the link is wrapped
in `@if (isSuperAdmin())`. Hospital-admin / receptionist / nurse /
midwife rows show only the existing edit + delete actions.

### 6. Aggregated UI allows deselecting all sources — **Major**

> The UI allows deselecting all aggregated audit sources, but when
> sources is empty the client omits the query param and the backend
> defaults to "all sources". This can confuse users who unchecked
> everything expecting no results.

**Fix.** `toggleAggregatedSource()` is now a no-op when only one
source remains active. Added `isLastActiveSource(source)` helper;
the template binds `[disabled]` and a `disabled` CSS class on the
locked checkbox plus a tooltip via `AUDIT_SEARCH.AGGREGATED.LAST_SOURCE_LOCKED`
("At least one source must stay selected.").

**Tests.** Three new specs in a new
`super-admin/audit-search/audit-search.spec.ts`:
toggle add/remove, last-source lock no-op, `isLastActiveSource`
truthiness.

### Verification

- `npm run format`, `npm run lint` clean
- Karma **865/865** SUCCESS (up from 859 — +6 Copilot-fix tests)
- `:hospital-core:test` green
- `:hospital-core:jacocoTestCoverageVerification` (80% INSTRUCTION
  gate) green


---

## 2026-05-05 — commit `2678681f` + cross-tenant runtime fixes

Five Copilot findings on the prior super-admin recent-activity work
(commit `2678681f`), plus **two pre-existing runtime bugs** that the
cross-tenant list-pages slice exposed when the user actually ran the
app under `local-h2`. All addressed in the same fixup commit.

### 1. `getRecentLabOrders` sorts by `createdAt`, not `orderDatetime` — **High**

> This endpoint orders "recent" lab orders by `createdAt`, but the
> existing lab-order search/list path orders by `orderDatetime`.
> Backfilled or edited orders will appear in the wrong position.

**Fix.** `SuperAdminDashboardServiceImpl.getRecentLabOrders` now sorts
by `Sort.Order.desc("orderDatetime")` then `Sort.Order.desc("createdAt")`
as a tiebreaker / null-safety fallback.

### 2. `getRecentAdmissions` sorts by `createdAt`, not `admissionDateTime` — **High**

> Records entered late or migrated after the fact will be surfaced as
> the newest admissions even when the actual admission happened weeks
> earlier.

**Fix.** `getRecentAdmissions` now sorts by
`admissionDateTime DESC, createdAt DESC` per Copilot's suggested
changeset.

**Audit-pass widening.** The same defect class was found across every
other affected `getRecent*` and fixed in the same commit:
`getRecentEncounters` → `encounterDate`,
`getRecentLabResults` → `resultDate`,
`getRecentTreatmentPlans` → `timelineStartDate`,
`getRecentReferrals` → `submittedAt`.
`getRecentPrescriptions` deliberately stays on `createdAt` because
the prescription entity has no separate clinical-write-time field;
that choice is locked by an explicit "intentionally createdAt"
test so a future "consistency" refactor doesn't silently change it.

### 3. Activity-row timestamp extractor misses entity-specific fields — **Medium**

> The extractor only checks `createdAt`, `requestedAt`,
> `admissionDate`, and `orderedAt`, but several DTOs use
> `orderDatetime` and `admissionDateTime`. Those rows render blank.

**Fix.** Extended `super-admin.ts` to read in clinical-time-first order:
`encounterDate` → `admissionDateTime` → `resultDate` → `orderDatetime`
→ `submittedAt` → `timelineStartDate` → `consentTimestamp` →
`requestedAt` → `admissionDate` → `orderedAt` → `createdAt`.
Order matches the new backend sort fields so both sides stay in
lock-step. +7 parameterised Karma tests (one per tab + a
"prefers-clinical-time" sanity check).

### 4. Activity tabs lack ARIA tab/panel linkage — **Medium**

> Tabs use `role="tab"` / `role="tabpanel"` with no programmatic
> link. Screen-reader users can't tell which panel each tab opens.

**Fix.** Each tab now carries `id="activity-tab-{key}"` +
`aria-controls="activity-tabpanel"`; the panel sets
`id="activity-tabpanel"` + `aria-labelledby` that flips dynamically
with the active tab. Roving `tabindex` (0 active, -1 inactive) added
for keyboard navigation. +1 Karma DOM test asserting the linkage.

### 5. `forkJoin` of 10 endpoints blocks the whole dashboard — **Medium**

> One slow clinical endpoint delays the whole dashboard.

**Fix (B2 from the scope discussion).** Replaced the `forkJoin` with
eight independent subscriptions; each result writes into its own slot
in the `recent` signal as it arrives. The header (`summary` +
`platform`) flips `loading=false` as soon as the summary returns so
the dashboard renders progressively. Total round-trip count is
unchanged — the proper aggregate-endpoint fix (B1) is deferred and
tracked as **F5** in `docs/super-admin-cross-tenant-design.md`.

### 6. Boot refused under `local-h2` profile — **Critical (runtime)**

Not from Copilot — surfaced when the user ran `gradlew bootRun`. App
refused to start with:

> `Refusing to start: hms.tenant-archive.kek-source=noop is only
> permitted in dev/test profiles (active profiles=[local-h2])`

The KEK-safety gate in `TenantArchiveEncryptionServiceImpl.isDevOrTestProfile()`
substring-matched only `dev` / `test` / `default`, but the project's
own seeders (`RoleSeeder`, `OrganizationSecuritySeeder`,
`DevSyntheticDataSeeder`, `HospitalOrganizationAlignmentRunner`)
treat `local` and `local-h2` as dev-equivalent.

**Fix.** Extended the substring-match to also cover the `local`
family (`local`, `local-h2`, `local-uat`, …). Production profiles
(`prod`, `staging`) still don't contain any of the dev/test/local
tokens and remain strictly gated. +2 regression tests
(`noopModeIsAllowedUnderLocalH2Profile`,
`noopModeIsAllowedUnderPlainLocalProfile`).

### 7. NPE on every cross-tenant list endpoint — **Critical (runtime)**

Also not from Copilot — surfaced when the user logged in as a
super-admin and the dashboard fired its 10 calls. **9 endpoints
500'd** with the same stack trace:

> `NullPointerException: Cannot invoke "Hospital.getId()" because the
> return value of "UserRoleHospitalAssignment.getHospital()" is null
>     at RoleValidator.getCurrentHospitalId(RoleValidator.java:95)`

Affected endpoints:
`/api/super-admin/recent-treatment-plans`,
`/api/super-admin/recent-prescriptions`,
`/api/super-admin/recent-lab-results`,
`/api/lab-results`, `/api/lab-orders`,
`/api/billing-invoices/search`,
`/api/referrals`, `/api/treatment-plans`, `/api/consultations`.

**Root cause.** `UserRoleHospitalAssignment.hospital` is legally
nullable (no `optional=false` on the JPA mapping) — a null hospital
represents a *global* assignment, the typical shape for a
SUPER_ADMIN role granted without a tenant scope.
`RoleValidator.getCurrentHospitalId` did
`active.get(0).getHospital().getId()` without a null check.

**Why it became visible now.** Before the cross-tenant slice this
fallback path was rarely reached for super-admins (the
`X-Hospital-Id` header was always set). The new "global view"
deliberately omits the header so list endpoints fall through to
their unscoped branch — which means `requireActiveHospitalId()`
runs `getCurrentHospitalId()` and used to NPE.

**Fix.** Null-safe access in `getCurrentHospitalId`:

```java
if (active.size() != 1) return null;
var hospital = active.get(0).getHospital();
return hospital != null ? hospital.getId() : null;
```

The caller's super-admin branch in `requireActiveHospitalId()` then
takes over and returns null, letting the unscoped `findAll` run.

**Tests.** New `RoleValidatorTest` (7 cases) covering: no auth, no
assignments, multiple assignments, single scoped assignment, single
**global** assignment (the regression), context-wins-over-fallback,
and end-to-end `requireActiveHospitalId_returnsNullForSuperAdminWithGlobalAssignment`
that exercises the full production path.

### Verification

- `npm run format`, `npm run lint` clean
- Karma **914/914** SUCCESS (up from 906 — +8 super-admin tests for
  finding #3 + #4)
- `./gradlew :hospital-core:check` green (test +
  `jacocoTestCoverageVerification` at 80% INSTRUCTION threshold)
- `./gradlew :hospital-core:build` green
- Filtered JaCoCo INSTRUCTION coverage: **89.40%** (76,756 / 85,854) —
  +9 pts above the project gate
- `gradlew bootRun` under `local-h2` now starts in ~30s (was: refused
  to start). **Boot-log NPE count: 0** — verified by grepping
  `bootrun.log` for the previously-recurring stack trace; 9 endpoints
  no longer 500.
- 7 new `SuperAdminDashboardServiceImplTest` cases lock each
  `getRecent*` sort field via `ArgumentCaptor<Pageable>`.
- 7 new `RoleValidatorTest` cases lock the global-assignment null-safety.
- 2 new `TenantArchiveEncryptionServiceImplTest` cases lock the
  `local` / `local-h2` profile allowance.

---

## 2026-05-06 — F1–F5 follow-ups + click-card-shows-no-data bug fix

User-reported runtime symptom: super-admin dashboard cards show
non-zero counts (e.g. "5 Encounters", "3 Consultations") but clicking
a card opens the list page with the global "All hospitals" chip and
**zero rows** ("No encounters across any of your hospitals."). Same
shape on the "Recent clinical activity" panel — the count badge shows
N but the panel body shows "No recent items in this category yet" for
several feeds. Also closes all five F1–F5 follow-ups tracked in
`docs/super-admin-cross-tenant-design.md`.

### Root cause (the click-card bug = F1, same fix)

`JwtTokenProvider.buildHospitalContext` (line 565, 588–590) populates
`HospitalContext.activeHospitalId` from the
`CLAIM_PRIMARY_HOSPITAL_ID` JWT claim — the super-admin's home
hospital — even when the SPA omits the `X-Hospital-Id` header. Then
`RoleValidator.requireActiveHospitalId()` checked `activeHospitalId`
**before** super-admin status, so it returned the JWT-derived primary
hospital and the unscoped fallback path silently re-scoped the list
endpoints to one hospital. The dashboard counters use
`repository.count()` directly, bypassing `RoleValidator`, which is why
they showed correct global totals while every list page returned 0.

### F1 — JWT-claim gate in `RoleValidator.requireActiveHospitalId()`

- Added `RoleValidator.isSuperAdminFromJwtClaim()` reading
  `HospitalContextHolder.getContextOrEmpty().isSuperAdmin()` (the
  discrete JWT claim, not the inflated authorities).
- Reordered `requireActiveHospitalId()` so super-admin (per JWT
  claim) short-circuits to `null` *before* reading `activeHospitalId`.
  Authorities-based check kept as a final safety net for paths that
  bypass the JWT filter chain (legacy unit tests).
- Closes both the F1 impersonation correctness gap (authorities can
  be inflated; the JWT claim is the source of truth) and the
  click-card runtime bug (super-admin's JWT-derived primary hospital
  no longer leaks into the unscoped fallback).
- 3 new `RoleValidatorTest` cases:
  `requireActiveHospitalId_returnsNullForSuperAdmin_evenWhenJwtPopulatesActiveHospitalId`
  (the click-card reproducer),
  `requireActiveHospitalId_returnsScopedHospital_whenAuthoritiesInflateSuperAdminButJwtClaimDoesNot`
  (the F1 impersonation case), and
  `isSuperAdminFromJwtClaim_returnsTrueOnlyWhenContextSays`.

### F2 — Hospital typeahead uses prefix match

- `HospitalRepository.searchHospitals` `:name` clause flipped from
  `LIKE LOWER(CONCAT('%', :name, '%'))` (substring) to
  `LIKE LOWER(CONCAT(:name, '%'))` (prefix). Makes the V90
  `idx_hospitals_lower_name` btree index load-bearing instead of
  decorative.
- V90 migration comment updated to reflect prefix-match semantics +
  history note.
- `:city` / `:state` stay on substring (no equivalent index, rarely
  queried).

### F3 — Audit on cross-tenant super-admin reads

- New `CrossTenantReadAudit` component (`security.audit` package).
  Single-call-site helper that emits a `DATA_ACCESS` audit event per
  cross-tenant read, gated on the JWT-claim super-admin signal so
  scoped reads (already audited per-resource) aren't double-counted.
  Exception-safe — never propagates audit failures back to the read
  path.
- Wired into all 9 `recent-*` endpoints + `/hospitals/search` on
  `SuperAdminDashboardController`. The new aggregate
  `/recent-activity` (F5) emits **one** `RECENT_ACTIVITY_BUNDLE`
  audit entry instead of nine separate ones, keeping the audit trail
  compact while preserving traceability via the bundle's
  `rowsReturned` total.
- 4 new `CrossTenantReadAuditTest` cases (real super-admin emits;
  non-super-admin skips; empty context skips; logEvent failure
  swallowed). 11 new `SuperAdminDashboardControllerTest` cases lock
  the wiring per endpoint.

### F4 — `(created_at DESC)` indexes on the seven clinical parents

- New `V91__clinical_created_at_desc_indexes.sql` migration adding:
  - `clinical.consultations(created_at DESC)`
  - `clinical.encounters(created_at DESC)`
  - `clinical.prescriptions(created_at DESC)`
  - `clinical.treatment_plans(created_at DESC)`
  - `lab.lab_orders(created_at DESC)`
  - `admissions(created_at DESC)`
  - `general_referrals(created_at DESC)`
- All `CREATE INDEX IF NOT EXISTS` — strictly additive. Lays the
  groundwork for the cursor-/keyset-pagination follow-up from
  design call #2 without forcing it into the same PR.
- Registered V90 (previously unregistered) and V91 in Liquibase
  `changelog.xml`.

### F5 — Aggregate `GET /super-admin/recent-activity` endpoint

- Backend: new `RecentActivityDTO` carrying nine lists, new
  `SuperAdminDashboardService.getRecentActivity(limit, locale)`
  method composing the existing nine `getRecent*` calls under a
  single `@Transactional(readOnly=true)` snapshot, new
  `GET /super-admin/recent-activity` controller wrapper. 3 new
  `SuperAdminDashboardControllerTest` cases + 1 new
  `SuperAdminDashboardServiceImplTest` case
  (`getRecentActivity_composesAllNineFeedsIntoOneBundle` — distinct
  single-row stubs prove no cross-feed bleeding).
- Frontend: new `getRecentActivity()` method + matching
  `SuperAdminRecentActivityBundle` interface on
  `dashboard.service.ts`. `super-admin.ts`'s `loadAll()` collapsed
  from 8 individual subscriptions into a single
  `getRecentActivity(10).subscribe(...)` that bulk-writes nine
  signal slots from one consistent snapshot. The 8 per-feed
  `getRecent*` helpers stay on `dashboard.service.ts` as a public
  API for any future consumers that need a single feed.
- Karma `super-admin.spec` rewritten to drive the new bundle path:
  one stub returning a `SuperAdminRecentActivityBundle` per scenario
  via a new `bundleWith({...})` helper. The "fetches 8 endpoints"
  test became "fetches the aggregate bundle in a single call (F5)",
  locking the new behaviour.

### Verification

- `./gradlew :hospital-core:test` — green, full suite (unchanged
  test count + 22 new tests across F1/F3/F5 land green).
- `./gradlew :hospital-core:build` — **BUILD SUCCESSFUL**.
- `./gradlew :hospital-core:jacocoTestCoverageVerification` — green.
  Filtered INSTRUCTION coverage **89.46%** (gate ≥80%, +9.46 pts
  above floor; 77,609 / 86,755 covered across 392 included classes).
- Frontend Karma — **914/914 SUCCESS**.
- `npm run format` clean (no files reformatted).
- `npm run lint` clean (0 errors / 0 warnings).

---

## 2026-05-06 (b) — Copilot + Sonar review on the F1–F5 PR

Six findings on PR #245 (the F1–F5 follow-up commit `3f5eb636`):
five from Copilot, two High/Critical Sonar code smells. All
addressed in a single fixup commit on the same branch before
merging.

### C1 / C2 — Cross-tenant `recent-*` endpoints lack JWT-claim re-check — **High**

> The `/super-admin/recent-*` endpoints are gated only by
> `@PreAuthorize("hasRole('SUPER_ADMIN')")`. Only `/hospitals/search`
> does the additional `HospitalContextHolder.getContextOrEmpty().isSuperAdmin()`
> check. Since these endpoints are also cross-tenant reads, they
> should apply the same belt-and-braces JWT-claim gate (ideally via
> a shared private helper) before calling the service and before
> emitting the audit event.
>
> Same finding for `/recent-activity`.

**Fix.** Extracted a private
`SuperAdminDashboardController.requireRealSuperAdminFromJwtClaim()`
helper that throws `ResponseStatusException(403)` when the JWT
claim is absent. Called from all 9 `recent-*` endpoints + the new
`/recent-activity` + the existing `/hospitals/search` (de-dup'd the
inline check). Gate fires BEFORE the service call AND BEFORE the
audit emission, so neither data nor audit can be reached by a
principal whose JWT does not carry the claim.

**Tests.** New parameterised
`SuperAdminDashboardControllerTest.everyCrossTenantEndpoint_blocks403WhenJwtClaimAbsent`
loops all 10 endpoints, verifies 403 + `verifyNoInteractions`
on both `dashboardService` and `crossTenantReadAudit`. New
`@BeforeEach setupSuperAdminContext()` sets a real-super-admin
context as the default so existing happy-path tests don't each
have to set it.

### C3 — `totalRows` NPE risk on null `RecentActivityDTO` lists — **Medium**

> `totalRows` calls `.size()` directly on each list. Since
> `RecentActivityDTO` fields are nullable (not `@NotNull`), this can
> throw NPE if any feed is omitted (future partial responses, test
> stubs, error paths).

**Fix.** Added a private static `sizeOrZero(List<?>)` helper, used
for all 9 list size accumulations. The audit hook now records 0
rows for a fully-null bundle instead of 500'ing.

**Test.** New `getRecentActivity_isNullSafeWhenBundleListsAreNull`
exercises an empty `RecentActivityDTO.builder().build()` and asserts
`recordCrossTenantRead("RECENT_ACTIVITY_BUNDLE", ..., 0)`.

### C4 — `nullSafePrincipal()` is unused dead code — **Minor**

> The static helper is only present to justify keeping the `UUID`
> import and should be removed.

**Fix.** Removed both the helper and the now-unused
`import java.util.UUID`. The `HospitalContext` import remains
(still referenced by `getContextOrEmpty()`).

### C5 — `super-admin.ts` subscriptions leak on navigation away — **Medium**

> `loadAll()` creates subscriptions (`getSummary`,
> `platform.getSummary`, `getRecentActivity`) without teardown. The
> codebase already uses `takeUntilDestroyed` elsewhere; do the same
> here to avoid leaks when navigating away.

**Fix.** Injected `DestroyRef` and piped each of the three
subscriptions through `takeUntilDestroyed(this.destroyRef)`,
matching the pattern used in `HospitalScopeChipComponent` /
`HospitalTypeaheadComponent`.

### S6 — Self-invocation of `@Transactional` methods (Sonar S6809) — **Critical × 9**

> `getRecentActivity` calls `getRecentEncounters(...)`,
> `getRecentConsultations(...)`, etc. via `this`. Each inner method
> is `@Transactional(readOnly = true)`, but Spring's proxy-based
> transaction management bypasses self-invocations — the inner
> annotations are silently dropped.

**Fix.** Added a self-reference field
`private SuperAdminDashboardService self` on
`SuperAdminDashboardServiceImpl` with `@Autowired @Lazy`
setter-injection (the `@Lazy` breaks the otherwise-circular
constructor dep Spring would detect). `getRecentActivity` now calls
through `self.getRecentEncounters(...)` etc., so the AOP proxy is
crossed and each inner `@Transactional` actually applies. Setter
injection (vs. extending the 30-arg `@RequiredArgsConstructor`)
keeps the existing constructor untouched.

**Test wiring.** Added `@BeforeEach service.setSelf(service)` in
`SuperAdminDashboardServiceImplTest` — Mockito's `@InjectMocks`
doesn't call public setters, so we wire the self-reference to the
real instance for unit tests (no Spring container, so no proxy is
needed; behaviour is correct via plain method dispatch).

### S7 — `recordCrossTenantRead_swallowsExceptionsFromAuditService` lacks assertion (Sonar S2699) — **Blocker**

> The test exercises the throwing path but has no assertion.

**Fix.** Added explicit
`assertThatCode(...).doesNotThrowAnyException()` plus a
belt-and-braces `verify(auditEventLogService).logEvent(any())` to
confirm we exercised the throwing branch (not the gating
short-circuit).

### Verification

- `./gradlew :hospital-core:test` — green, full suite (+ 2 new
  controller tests, + 1 new service-test `@BeforeEach`, all targeted
  test classes pass).
- `./gradlew :hospital-core:build` — **BUILD SUCCESSFUL**.
- `./gradlew :hospital-core:jacocoTestCoverageVerification` — green.
  Filtered INSTRUCTION coverage **89.46%** (gate ≥80%, +9.46 pts
  above floor; 77,622 / 86,768 covered across the included classes).
- Frontend Karma — **914/914 SUCCESS**.
- `npm run format` clean. `npm run lint` clean (0 errors / 0
  warnings).

---

## 2026-05-06 (c) — Copilot + Sonar second-pass review on the F1–F5 PR

Seven new findings on PR #245 after the (b) fixup landed: four from
Copilot, three from Sonar. Some Sonar findings (S6809 self-invocation
× 9, missing assertion in CrossTenantReadAuditTest, dead
`nullSafePrincipal()`) were stale — already fixed in 0b3c0e7e but
the analysis hadn't re-run; ignored here.

### C1 — `requireActiveHospitalId()` ignored explicit X-Hospital-Id for super-admin — **High (regression)**

> requireActiveHospitalId() now returns null for any request where
> HospitalContext.isSuperAdmin() is true, even when the request
> explicitly provides X-Hospital-Id (which
> HospitalContextRequestOverrides would have validated and applied).
> This effectively ignores scoped super-admin requests and can
> cause super-admin list endpoints to run unscoped even when the UI
> is trying to pin to a specific hospital.

The (b) fixup over-corrected: my F1 short-circuit returned `null`
for any super-admin, dropping the JWT-derived primary
(intentional — fixes the click-card bug) AND the
header-overridden scoped value (regression — the explicit
X-Hospital-Id path was getting ignored too).

**Fix.** Added a `boolean headerOverridden` field to `HospitalContext`
(defaults to `false`). `HospitalContextRequestOverrides.applyRequestOverrides`
sets it to `true` whenever it successfully validates and applies an
`X-Hospital-Id` header. `RoleValidator.requireActiveHospitalId()` now:

```java
if (ctx.isSuperAdmin()) {
    if (ctx.isHeaderOverridden() && ctx.getActiveHospitalId() != null) {
        return ctx.getActiveHospitalId();   // explicit chip-scope wins
    }
    return null;                            // global view (JWT-only primary dropped)
}
```

This honours both the click-card fix (no header → null → unscoped
fallback) AND the explicit scoping path (header → scoped value).

**Tests.**
- New `RoleValidatorTest.requireActiveHospitalId_returnsScopedHospital_forSuperAdminWithExplicitHeaderOverride`
  asserts header-override is honoured for super-admin.
- New `HospitalContextRequestOverridesTest.inScopeHeaderSwitchesActiveHospital`
  extension asserts `headerOverridden = true` after a successful
  header override.
- New `HospitalContextRequestOverridesTest.noHeaderLeavesHeaderOverriddenFalse`
  locks the no-header path stays at `false` so the JWT-primary
  drop in `RoleValidator` keeps working.

### C2 — Nested interactive elements in hospital-scope-chip.component.html — **Major (a11y)**

> The template nests `<button class="hospital-scope-chip__clear">`
> inside another `<button class="hospital-scope-chip__button">`.
> Nested interactive elements are invalid HTML and break
> click/keyboard behavior and screen-reader semantics.

**Fix.** Restructured the chip template so the main "open overlay"
button and the (conditional) clear button are SIBLINGS within the
`.hospital-scope-chip` flex container, not nested. The chip's
existing flex SCSS already supports the sibling layout (no style
changes needed). Also added `[attr.title]` on the clear button
alongside `[attr.aria-label]` to satisfy the `discernible-text`
a11y lint rule on icon-only buttons.

The chip Karma spec uses `[data-testid="hospital-scope-chip-clear"]`
as the selector — unchanged by the un-nesting — so no test edits
were needed.

### C3 — `disableUrlSync` JSDoc claims it's configurable but it's hard-coded — **Minor**

> Documented as something callers can turn on (“Use this on pages…”)
> but it's `readonly disableUrlSync = false` with no `@Input` /
> `input()` to actually configure it.

**Fix.** Promoted `disableUrlSync` to an `@Input()` so callers can
opt out:
```html
<app-hospital-scope-chip [disableUrlSync]="true" />
```
Added `Input` to the existing `@angular/core` import block.

### C4 — Spanish translations missing for HOSPITAL_SCOPE.* + CONSULTATIONS.NO_DATA_GLOBAL — **Major (i18n)**

> es.json is missing the new keys introduced by this PR. Spanish
> users will see raw translation keys.

**Fix.** Added a complete `HOSPITAL_SCOPE` block to es.json (placed
alphabetically between `FEATURE_FLAGS` and `HOSPITALS`) with all
seven top-level keys + the six `EMPTY_GLOBAL.*` entity-specific
empty-state strings. Also added the missing
`CONSULTATIONS.NO_DATA_GLOBAL` key. fr.json already had both —
verified via grep. Spanish translations follow standard medical
Spanish conventions (e.g. "encuentros" / "ingresos" / "derivaciones").

### Sonar — Math.clamp instead of Math.max/min — **Major**

**Fix.** `int safeLimit = Math.max(1, Math.min(limit, MAX))` →
`int safeLimit = Math.clamp(limit, 1, HOSPITAL_SEARCH_MAX_LIMIT)`.
Java 21+ idiom, single function call, no nesting.

### Sonar — "createdAt" duplicated 8 times — **Critical**

**Fix.** Extracted
`private static final String CREATED_AT = "createdAt";` on
`SuperAdminDashboardServiceImpl`. All 8 sort-spec usages now
reference the constant. Comment notes the rationale (single update
site if the BaseEntity audit-time mapping ever changes).

### Sonar — `sanitizeLimit_clampsAtMax` had `assertThat(true).isTrue()` — **Major**

> Replace this literal with the actual expression you want to assert.

**Fix.** Rewrote the test to capture the `Pageable` flowing into
`treatmentPlanService.listAll(...)` via `ArgumentCaptor` and assert
`getPageSize() == 100` (the max-cap configured in `sanitizeLimit`).
Replaces the previous "no-throw is enough" filler with a real
behavioural assertion.

### Verification

- `./gradlew :hospital-core:test` — green (full suite + 2 new
  RoleValidatorTest cases + 1 new HospitalContextRequestOverridesTest
  case + assertion-bearing sanitizeLimit_clampsAtMax).
- `./gradlew :hospital-core:build` — **BUILD SUCCESSFUL**.
- `./gradlew :hospital-core:jacocoTestCoverageVerification` — green.
  Filtered INSTRUCTION coverage **89.46%** (+9.46 above gate;
  77,623 / 86,768 across the included classes).
- Frontend Karma — **914/914 SUCCESS**.
- `npm run format` clean. `npm run lint` clean.
