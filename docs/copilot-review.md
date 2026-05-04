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
