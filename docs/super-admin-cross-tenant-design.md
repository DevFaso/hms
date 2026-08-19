# Super-admin cross-tenant clinical pages — design

Background and diagnosis (why this is needed) is in
[`docs/copilot-review.md`](./copilot-review.md) under the 2026-05-05 entry.
Short version: the super-admin shell links to per-hospital list pages
(`/consultations`, `/encounters`, `/lab-tests`, `/lab-results`,
`/admissions`, `/prescriptions`, `/treatment-plans`, `/referrals`); the
SPA's [`auth.interceptor.ts`](../hospital-portal/src/app/interceptors/auth.interceptor.ts)
unconditionally injects `X-Hospital-Id` on every API call; and the
underlying controllers (e.g. `ConsultationController` →
`/api/consultations`) filter by that header. So a super-admin sees only
data for their primary hospital and nothing else. Commit `2678681f`
already added cross-tenant **dashboard counters** but did not address
the **list pages** themselves.

A naive dropdown of all hospitals doesn't scale to 10k+ tenants. The
design below uses **server-side debounced typeahead** ("scope chip")
with all-hospitals as the default super-admin view.

---

## UX shape

**Default for super-admin: all-hospitals view.** Each affected list
page header gets a hospital-scope chip:

```
┌─────────────────────────────────────────────────────────────┐
│ Consultations    [🌐 All hospitals    ▾]      [+ New]       │
│ ─────────────────────────────────────────────────────────── │
│ Hospital            Patient       Doctor      Date          │
│ Memorial Hospital   J. Smith      Dr. Adams   2026-05-05    │
│ Riverside Clinic    M. Doe        Dr. Lee     2026-05-04    │
│ Memorial Hospital   T. Brown      Dr. Adams   2026-05-04    │
│ …                                                           │
└─────────────────────────────────────────────────────────────┘
```

Click the chip → typeahead overlay opens:

```
┌──────────────────────────────────────────┐
│ 🔍 Filter by hospital                    │
│ ─────────────────────────────────────── │
│ ┌──────────────────────────────────────┐ │
│ │ memo|                                │ │ ← user types
│ └──────────────────────────────────────┘ │
│                                          │
│ ◯ All hospitals                          │
│ ● Memorial Hospital                      │
│   Memorial Children's Center             │
│   Memorial East Campus                   │
│ ─────────────────────────────────────── │
│ Showing 3 of 3 matches                   │
└──────────────────────────────────────────┘
```

Pick **Memorial Hospital** → page reloads scoped to that one tenant,
the **Hospital** column hides, and the chip becomes
`🏥 Memorial Hospital ✕`. The `✕` clears back to global view.

### Why typeahead, not dropdown

| Concern | Dropdown of all hospitals | Server-side typeahead |
|---|---|---|
| 10k+ hospitals | Unusable — 10k DOM nodes, slow scroll, no findability | Always renders ≤20 results |
| Network cost | One huge payload at first open | Debounced 300 ms, capped server-side `LIMIT 20` |
| Findability | Scroll/`Ctrl-F` only | Substring or prefix match |
| Stay-in-flow | Modal-feel, blocks the page | Inline overlay, dismiss on outside click |

This is the same pattern Stripe / Linear / Auth0 use for org switching.

### URL state

The selected hospital is reflected in the URL:

- `/consultations` → all-hospitals (global) view
- `/consultations?hospitalId=82430285-…` → scoped view

Back / forward / share-link all work. Refresh restores state.

### Empty-state messaging

When the table is genuinely empty in global view, the empty-state must
distinguish "no scope mistake — really no data":

> *"No consultations across any of your 3 hospitals."*

…not the generic "No data". Otherwise users can't tell whether scoping
is wrong or data is just missing.

---

## Component inventory

| Layer | Piece | Status |
|---|---|---|
| **DB** | Index on `hospitals(name)` for ILIKE prefix search | ✅ shipped — [`V90__hospital_name_search_index.sql`](../hospital-core/src/main/resources/db/migration/V90__hospital_name_search_index.sql) (functional btree on `LOWER(name)`) |
| **DB** | `(created_at DESC)` on the seven clinical parent tables for cursor pagination | ✅ shipped 2026-05-06 — [`V91__clinical_created_at_desc_indexes.sql`](../hospital-core/src/main/resources/db/migration/V91__clinical_created_at_desc_indexes.sql) (F4) |
| **Repo** | `HospitalRepository.searchHospitals(name, city, state, active, pageable)` | ✅ exists at [`HospitalRepository.java:33`](../hospital-core/src/main/java/com/example/hms/repository/HospitalRepository.java#L33) |
| **Service** | `HospitalService.searchHospitals(...)` | ✅ exists at [`HospitalServiceImpl.java:163`](../hospital-core/src/main/java/com/example/hms/service/HospitalServiceImpl.java#L163) |
| **Controller** | `GET /api/super-admin/hospitals/search?q=&limit=` thin wrapper, gated on `isSuperAdmin` claim | ✅ shipped on [`SuperAdminDashboardController`](../hospital-core/src/main/java/com/example/hms/controller/SuperAdminDashboardController.java) — belt-and-braces gate (`@PreAuthorize` + `HospitalContextHolder.isSuperAdmin()` re-check), 2-char min query, server cap of 20, `active=true` filter |
| **Controller (per-resource)** | "no scope ⇒ all tenants for SUPER_ADMIN" branch on `ConsultationController`, `EncounterController`, `LabTestController` (orders + results + definitions), `AdmissionController`, `PrescriptionController`, `TreatmentPlanController`, `ReferralController` | ✅ shipped — 6 of 7 services already had the `requireActiveHospitalId() == null → unscoped findAll` pattern; `EncounterServiceImpl.list()` was the one outlier (used to throw) and is now aligned. Lab-test-definitions is correctly unscoped (catalog data). Per-resource gating now reads the JWT-claim super-admin signal via `RoleValidator.requireActiveHospitalId()` (F1, 2026-05-06). |
| **DTOs** | Each list-DTO needs `hospitalId` + `hospitalName` for the new column | ✅ verified — all 7 carry both fields. `PrescriptionResponseDTO` was missing `hospitalName`; added + populated in [`PrescriptionMapper`](../hospital-core/src/main/java/com/example/hms/mapper/PrescriptionMapper.java) (prefers prescription's own hospital, falls back to encounter for legacy rows) |
| **Frontend interceptor** | [`auth.interceptor.ts`](../hospital-portal/src/app/interceptors/auth.interceptor.ts) skip `X-Hospital-Id` when `isSuperAdmin && globalView === true` | ✅ shipped — interceptor reads `RoleContextService.effectiveHospitalIdForRequest()`, which returns `null` (omit header) for super-admin in global view |
| **Frontend state** | [`RoleContextService`](../hospital-portal/src/app/core/role-context.service.ts) adds `globalView: signal<boolean>` (default `true` for super-admin) and `selectedHospitalId: signal<string \| null>` | ✅ shipped — added `globalView`, `selectedHospitalId`, `effectiveHospitalIdForRequest` computed signals + `enableGlobalView()` / `scopeToHospital()` / `markSuperAdminGlobalDefaults()`. Default-to-global wired into `AppComponent` and `OidcAuthService` post-login bootstrap. |
| **Frontend UI** | `<hospital-scope-chip>` shared component (header chip + overlay) | ✅ shipped — [`hospital-scope-chip.component.ts`](../hospital-portal/src/app/shared/hospital-scope-chip/hospital-scope-chip.component.ts) (renders nothing for non-super-admin, click-outside-to-dismiss, ✕ clear) |
| **Frontend UI** | `<hospital-typeahead>` standalone autocomplete used inside the chip and reusable elsewhere | ✅ shipped — [`hospital-typeahead.component.ts`](../hospital-portal/src/app/shared/hospital-typeahead/hospital-typeahead.component.ts) (300 ms debounce, sub-2-char queries skip the network, error/empty states, accessible listbox/option roles) |
| **Frontend list pages** | Show "Hospital" column when `globalView === true`, hide when scoped | ✅ shipped — all 7 templates updated (consultations, encounters, lab-results, admissions, prescriptions, treatment-plans, referrals); each uses `@if (isSuperAdmin() && globalView())` |
| **Routing glue** | `?hospitalId=` query param sync per page | ✅ shipped — centralized in [`HospitalScopeUrlService`](../hospital-portal/src/app/core/hospital-scope-url.service.ts); host pages call `applyUrlScopeSync(route)` from `ngOnInit` BEFORE the first `load()` to avoid the child-component lifecycle race; chip handles writeback on user-driven changes |

---

## Design calls (locked-in unless we revisit)

1. **Permissions gate.** Cross-tenant branches must check the
   dedicated **`isSuperAdmin` JWT claim**, *not* `hasRole('SUPER_ADMIN')`
   from authorities. Reason: [`JwtTokenProvider.getAuthenticationFromJwt()`](../hospital-core/src/main/java/com/example/hms/security/JwtTokenProvider.java#L679-L693)
   inflates super-admin to inherit 7 sub-roles, so `hasRole(...)` checks
   would also pass for impersonation contexts. The
   `isSuperAdmin` claim is the only safe "really a super-admin right
   now" signal.

   **Status (2026-05-06): HONORED (F1 closed).** ✅ The new
   `/super-admin/hospitals/search` endpoint enforces this with a
   belt-and-braces re-check against
   `HospitalContextHolder.getContextOrEmpty().isSuperAdmin()`. ✅ The
   per-resource fall-through path (`requireActiveHospitalId()`) now
   short-circuits on the JWT-claim super-admin signal *before*
   reading `activeHospitalId`, via the new
   `RoleValidator.isSuperAdminFromJwtClaim()` helper. The
   authorities-based check survives only as a final safety net for
   paths that bypass the JWT filter chain (legacy unit tests).
   This same change closed a user-reported runtime bug where
   clicking a dashboard card opened the list page with **0 rows**:
   `JwtTokenProvider.buildHospitalContext` populated
   `HospitalContext.activeHospitalId` from the
   `CLAIM_PRIMARY_HOSPITAL_ID` claim even when no `X-Hospital-Id`
   header was sent, and the old ordering returned that primary
   hospital from step 1 — silently re-scoping every list endpoint
   to the super-admin's home hospital while the dashboard counters
   (which call `repository.count()` directly, bypassing
   `RoleValidator`) showed correct global totals. See
   `docs/copilot-review.md` 2026-05-06 entry.

2. **Pagination.** Cross-tenant queries reuse the existing `Pageable`
   pattern. No "load all in memory then paginate". Cursor / keyset
   preferred over offset for stable scrolling at scale.

   **Status (2026-05-05): HONORED (offset).** All cross-tenant queries
   reuse the existing `Pageable` / `findAll(...)` patterns. Cursor /
   keyset was a "preferred" not "required" call; not adopted in this
   slice. Follow-up only if scroll instability shows up in load tests.

3. **Search style.** Default to **prefix match** (`ILIKE 'memo%'`,
   B-tree friendly). Substring match (`ILIKE '%memo%'`) requires
   `pg_trgm` GIN — only add if the prefix UX tests poorly.

   **Status (2026-05-06): HONORED (F2 closed).**
   `HospitalRepository.searchHospitals`'s `:name` clause flipped from
   `LIKE LOWER(CONCAT('%', :name, '%'))` to
   `LIKE LOWER(CONCAT(:name, '%'))` — prefix match, design-aligned.
   The V90 `idx_hospitals_lower_name` functional B-tree index is now
   **load-bearing** (the prefix LIKE becomes a true B-tree range
   scan). `:city` / `:state` deliberately stay on substring (no
   equivalent index, rarely-queried admin search path). V90
   migration comment updated with the prefix semantics + history.

4. **Audit.** Every cross-tenant read goes through the existing audit
   layer (the one commit `2678681f` wired the recent-activity counters
   into). Super-admin views must be traceable.

   **Status (2026-05-06): VERIFIED + WIRED (F3 closed for the
   super-admin recent-activity surface).** Audit layer was confirmed
   to be **opt-in** (manual `AuditEventLogService.logEvent` calls),
   *not* an aspect that auto-records GETs. New
   `CrossTenantReadAudit` component (`security.audit` package) wraps
   the emission with super-admin-only gating, exception safety, and
   X-Forwarded-For-aware client-IP capture; it is wired into all 9
   `recent-*` endpoints + `/hospitals/search` on
   `SuperAdminDashboardController` and into the new aggregate
   `/recent-activity` endpoint (F5). The aggregate emits one
   `RECENT_ACTIVITY_BUNDLE` audit entry per call instead of nine, to
   keep the trail compact. Per-resource list endpoints
   (`ConsultationController.getAllConsultations` etc.) — which take
   non-super-admin traffic too — were *not* wired in this slice; a
   thin AOP advice scoped to the super-admin fallback branches is the
   right next step if compliance asks for it. The optional
   `HOSPITAL_SCOPE_SWITCH` `FrontendAuditEvent` is still TODO.

5. **Default scope.** Super-admin lands on each list page in
   **global view by default**, *not* on their primary hospital. A
   primary-hospital default would silently re-introduce the bug we're
   fixing.

   **Status (2026-05-05): HONORED.**
   `RoleContextService.markSuperAdminGlobalDefaults()` is called from
   `AppComponent.ngOnInit` (refresh / direct nav) and
   `OidcAuthService.processCallback` (Keycloak login).

---

## Rollout order

1. **One vertical slice end-to-end on `/consultations` first.** ✅ shipped 2026-05-05
   - `GET /api/super-admin/hospitals/search?q=&limit=` endpoint
   - `ConsultationController` cross-tenant branch gated on
     `isSuperAdmin` claim *(see design call #1 status — partial)*
   - `ConsultationResponseDTO` carries `hospitalName` (add if missing)
   - `<hospital-scope-chip>` + `<hospital-typeahead>` standalone
     components
   - Consultations list page: chip in header, Hospital column toggle,
     `?hospitalId=` URL state
   - `auth.interceptor.ts` honors `globalView`
   - Tests: backend slice tests + Karma specs for the chip/typeahead
2. **Repeat for the other 6** clinical pages. Second one is ~30 min
   once the pattern is proven; the chip/typeahead/interceptor changes
   are reused. ✅ shipped 2026-05-05 — encounters, lab-results,
   admissions, prescriptions, treatment-plans, referrals all wired.
3. **Add `recent-encounters`** to `SuperAdminDashboardController` —
   the 1-of-9 gap noted in [`copilot-review.md`](./copilot-review.md).
   Mirrors the 8 endpoints from commit `2678681f`. ✅ shipped
   2026-05-05 — canonical `/recent-encounters` path added (legacy
   `/encounters` retained as alias) + `Locale` parameter to match
   sister endpoints.

---

## Implementation status (2026-05-05)

The design has been implemented end-to-end across the three rollout
phases. The branch is committable; a small number of follow-ups remain
explicitly deferred (listed below).

### What shipped

#### Backend

- `V90__hospital_name_search_index.sql` — functional btree on
  `LOWER(hospital.hospitals.name)`.
- `SuperAdminDashboardController.searchHospitalsForScopeChip` —
  `GET /api/super-admin/hospitals/search?q=&limit=` with
  belt-and-braces gating, 2-char minimum, server cap of 20,
  `active=true` filter. 8 MockMvc-style unit tests.
- `SuperAdminDashboardController.getRecentEncounters` — added
  canonical `/recent-encounters` mapping (legacy `/encounters`
  retained as alias) + `Locale` parameter to align with sister
  endpoints. 3 new tests, including a regression-pin on the
  multi-path mapping.
- `EncounterServiceImpl.list()` — replaced its throw-on-missing-scope
  branch with the `requireActiveHospitalId() == null → unscoped
  findAll` pattern that the other 5 services already used.
- `PrescriptionResponseDTO` + `PrescriptionMapper` — added
  `hospitalName` field, populated from the prescription's own
  `hospital` field with encounter fallback.

#### Frontend

- `RoleContextService` — `globalView` + `selectedHospitalId` signals,
  `effectiveHospitalIdForRequest` computed, `enableGlobalView()` /
  `scopeToHospital()` / `markSuperAdminGlobalDefaults()` mutators.
- `auth.interceptor.ts` — reads `effectiveHospitalIdForRequest()` so
  super-admin in global view emits no `X-Hospital-Id` header at all.
- `AppComponent` + `OidcAuthService` — call
  `markSuperAdminGlobalDefaults()` after the JWT is decoded.
- `HospitalScopeUrlService` — single source of truth for `?hospitalId=`
  ↔ `RoleContextService` synchronisation, used by both the host pages
  (pre-load) and the chip (label + writeback).
- `<app-hospital-scope-chip>` + `<app-hospital-typeahead>` — new
  shared standalone components.
- All 7 clinical list pages updated:
  `/consultations`, `/encounters`, `/lab-results`, `/admissions`,
  `/prescriptions`, `/treatment-plans`, `/referrals`. Each renders
  the chip in `.page-actions`, conditionally shows a Hospital column
  in global view, and uses the design-doc'd scoped empty-state copy.
- i18n: `HOSPITAL_SCOPE.{ALL_HOSPITALS,FILTER_BY_HOSPITAL,…}` and
  `HOSPITAL_SCOPE.EMPTY_GLOBAL.{ENCOUNTERS,LAB_RESULTS,…}` keys in
  `en.json` and `fr.json` (Spanish falls back to English per app
  config).

#### Tests

- Backend: 11 new tests on `SuperAdminDashboardControllerTest`
  (search endpoint × 8, recent-encounters × 3).
- Frontend: 68 specs across `role-context.service.spec`,
  `hospital-typeahead.component.spec`, `hospital-scope-chip.component.spec`,
  plus updates to `referrals.spec` and `lab-results.spec`. All green.

### Open follow-ups (tracked, not blocking)

#### F1 — Per-resource cross-tenant gating uses authorities, not the JWT claim ✅ CLOSED 2026-05-06

- **Origin:** Design call #1.
- **What landed:** Added `RoleValidator.isSuperAdminFromJwtClaim()`
  reading `HospitalContextHolder.getContextOrEmpty().isSuperAdmin()`.
  Reordered `requireActiveHospitalId()` so this check fires *before*
  reading `activeHospitalId`. Authorities-based safety net retained
  for paths that bypass the JWT filter chain (legacy unit tests).
- **Bonus:** This same change closed a separately-reported runtime
  bug where clicking a super-admin dashboard card opened the list
  page with 0 rows — `JwtTokenProvider` populated
  `HospitalContext.activeHospitalId` from `CLAIM_PRIMARY_HOSPITAL_ID`
  even with no header sent, silently re-scoping every list endpoint
  to the super-admin's home hospital.
- **Tests:** 3 new `RoleValidatorTest` cases (impersonation case,
  click-card reproducer, JWT-claim helper).

#### F2 — Hospital typeahead uses substring instead of prefix ✅ CLOSED 2026-05-06

- **Origin:** Design call #3.
- **What landed:** `HospitalRepository.searchHospitals`'s `:name`
  clause flipped from `LIKE '%name%'` to `LIKE 'name%'`. V90 index
  now load-bearing instead of decorative. V90 migration comment
  updated with prefix semantics + history note.

#### F3 — Cross-tenant list reads not verified to write to audit layer ✅ CLOSED 2026-05-06 (super-admin surface)

- **Origin:** Design call #4.
- **What landed:** Audit layer confirmed to be opt-in (manual
  `logEvent` calls; no auto-audit aspect on GETs). New
  `CrossTenantReadAudit` component centralises the emission with
  super-admin-only gating + exception safety + X-Forwarded-For-aware
  client-IP capture. Wired into all 9 `recent-*` endpoints +
  `/hospitals/search` + the new aggregate `/recent-activity` (F5)
  on `SuperAdminDashboardController`.
- **Still pending (intentional):** per-resource list endpoints
  (`ConsultationController.getAllConsultations` etc.) — which take
  non-super-admin traffic — and the optional
  `HOSPITAL_SCOPE_SWITCH` `FrontendAuditEvent`.
- **Tests:** 4 new `CrossTenantReadAuditTest` cases (real super-admin
  emits / non-super-admin skips / empty context skips / logEvent
  failure swallowed). 11 new
  `SuperAdminDashboardControllerTest` cases lock the wiring per
  endpoint.

#### F4 — `(created_at DESC)` indexes on the 7 clinical parent tables ✅ CLOSED 2026-05-06

- **Origin:** Component inventory.
- **What landed:** New `V91__clinical_created_at_desc_indexes.sql`
  migration adding `(created_at DESC)` indexes on
  `clinical.consultations`, `clinical.encounters`,
  `clinical.prescriptions`, `clinical.treatment_plans`,
  `lab.lab_orders`, `admissions`, `general_referrals`. All
  `CREATE INDEX IF NOT EXISTS` — strictly additive. Lays the
  groundwork for the cursor/keyset-pagination follow-up from
  design call #2.

#### F5 — Aggregate `/super-admin/recent-activity` endpoint ✅ CLOSED 2026-05-06

- **Origin:** Copilot review on commit 2678681f, finding #5.
- **What landed (B1):**
  - **Backend:** new `RecentActivityDTO` (9 lists), new
    `SuperAdminDashboardService.getRecentActivity(limit, locale)`
    method composing the existing nine `getRecent*` calls under a
    single `@Transactional(readOnly=true)` snapshot, new
    `GET /super-admin/recent-activity` controller wrapper. The
    aggregate emits a single `RECENT_ACTIVITY_BUNDLE` audit entry
    via F3's `CrossTenantReadAudit`.
  - **Frontend:** new `getRecentActivity()` method +
    `SuperAdminRecentActivityBundle` interface on
    `dashboard.service.ts`. `super-admin.ts`'s `loadAll()` collapsed
    from 8 individual subscriptions into a single
    `getRecentActivity(10).subscribe(...)` that bulk-writes nine
    signal slots from one consistent snapshot. Per-feed methods
    retained on `dashboard.service.ts` for future consumers.
- **Tests:** 3 new `SuperAdminDashboardControllerTest` cases,
  1 new `SuperAdminDashboardServiceImplTest` case
  (`getRecentActivity_composesAllNineFeedsIntoOneBundle`),
  Karma `super-admin.spec` rewritten around the new bundle path
  with a `bundleWith({...})` helper. The "fetches 8 endpoints"
  test became "fetches the aggregate bundle in a single call (F5)".

---

## Out of scope (for now)

- Org-level scoping (filter by organization, not just hospital). The
  JWT carries `organizationIds`; we may add an "Organization" tier above
  the hospital chip later, but it's not in this slice.
- Bulk cross-tenant operations (e.g. mass-export). Read-only for now.
- The misleading auth log lines from
  [`copilot-review.md`](./copilot-review.md) Cause-1 / Finding "8 roles
  in JWT". Tracked separately; not blocking this work.
- The `setAllowedClockSkewSeconds(30)` JWT fix from the same review.
  Independent, do later.
