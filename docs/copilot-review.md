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
