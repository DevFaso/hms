# Web Portal Achievement Assessment & Gap Tasklist — 2026-08-18

> Full cross-reference of the Angular portal (`hospital-portal`) against the backend API surface
> (`hospital-core`: 97 controllers, ~730 endpoints). Assessment: the portal is mature for core
> operations (auth/RBAC shell, dashboards, patients, appointments, encounters, admissions, lab
> suite, reception, nurse station, chat/notifications via STOMP, patient portal, i18n EN/FR/ES,
> Playwright e2e) — but roughly a third of the backend's user-facing domains have **no web UI at
> all**, several shipped workflows are missing their closing half, and test/i18n/a11y quality
> lags behind the feature surface.

## Scorecard

| Area | Status |
| --- | --- |
| Core ops (patients, appointments, encounters, admissions, reception, nurse station) | ✅ Built |
| Lab suite (orders, results, approval queue, QC, ops, config, instruments, inventory) | ✅ Built (depth gaps: specimens, sign/ack, critical results, reflex rules) |
| Patient portal (14 `my-*` pages) | ✅ Built |
| Admin (users, roles, orgs, hospitals, platform, audit, feature flags) | 🟡 Partial (governance suite missing) |
| Discharge approvals + summaries | ❌ No UI |
| Imaging **results** (orders only) | ❌ No UI |
| Maternity suite (~70 endpoints: maternal history, ultrasound, birth plans, prenatal, postpartum, newborn, OB/GYN referrals, high-risk plans) | ❌ No UI |
| Medical history (social/family/immunizations, 24 EP) | ❌ No UI |
| Patient education (32 EP), procedure orders, medication history/pharmacy fills, insurance mgmt, registrations | ❌ No UI |
| Unit tests | 🟡 48 specs / 131 files (~37%); billing, imaging, encounters, admissions, consultations, scheduling, auth all untested |
| i18n | 🟡 ES missing 342 keys, FR missing 13; hardcoded EN strings in login/billing/forms |
| Accessibility | 🟡 aria-labels present; no skip-link, only 2 `aria-live`, no reduced-motion |

---

## Gaps in Priority Order

### P0 — Integrity risks in what's already shipped

1. **Hardcoded client-side permission map** — `hospital-portal/src/app/core/permission.service.ts` duplicates role→permission mapping in TS; drift already gutted LAB_MANAGER's sidebar once (see lab-role section below). Backend exposes `/roles/{id}/permissions`.
2. **Unreachable pages** — routes with no sidebar/nav entry: `analytics`, `feature-flags`, `digital-signatures`, `lab-staff`, `lab-instruments`, `lab-inventory`, `my-care-team`, `my-documents`, `my-family-access`, `my-notifications`, `notification-settings`; `force-change-password` component has no route at all.
3. **Mixed API prefixing** — 6 newer services hardcode `/api/...` while the rest rely on `apiPrefixInterceptor` (`auth.interceptor.ts:48`); double-prefix fragility.
4. **Silent 403 swallowing** — `error.interceptor.ts` suppresses 403s on 11 URL patterns, masking real authorization failures as "working" UI.
5. **Placeholder GA id `G-XXXXXXXXXX`** shipped in `index.html` + `environment.prod.ts`.

### P1 — Shipped workflows missing their closing half

6. **Discharge approvals + summaries** — `/discharge-approvals` (8 EP) and `/discharge-summaries` (11 EP incl. finalize, pending-results) have zero UI; admission/discharge exists but the approval + summary loop is broken.
7. **Imaging results** — portal only places orders; `/imaging/results` (view, status, critical-acknowledge) unconsumed. Ordering without resulting is half a workflow.
8. **Lab depth** — specimens (collect/receive), result **sign/acknowledge**, critical results (`hospital/{id}/critical[/unacknowledged]`), sequential result comparison, reflex rules: all backend-ready, no UI.
9. **Patient chart depth** — structured allergies/diagnoses CRUD, chart-updates, doctor-record/timeline, `/patients/search` unconsumed; portal treats allergies as a free-text field.
10. **Encounter transitions** — `complete-examination`, `ready-for-discharge`, AVS retrieval, note addendums not wired.

### P2 — Whole backend domains with no web UI

11. **Maternity suite** (~70 EP) — biggest single gap; MIDWIFE role exists in the portal but has no maternity pages.
12. **Medical history** — social/family history + immunizations (24 EP, incl. overdue/reminders).
13. **Patient education** (32 EP) + **procedure orders** (9 EP).
14. **Medication history / pharmacy fills** (6 EP) + pharmacy directory — PHARMACIST role only gets the prescriptions page.
15. **Insurance management** (6 EP) + multi-hospital **registrations** (6 EP) — reception only has issues/attest.
16. **Admin governance** — permission-matrix snapshots/audit, org security policies/rules (16 EP), super-admin user governance/credentials/security (15 EP), assignment admin CRUD + bulk-import, reference catalogs, service translations.
17. **Digital signatures actions** — UI lists + audit-trail only; sign/verify/revoke flows missing. **Billing depth** — invoice email, search, standalone payment recording.

### P3 — Quality & maturity

18. **Unit tests** — ~37% file coverage; entire domains untested (billing, imaging, consultations, treatment-plans, encounters, admissions, prescriptions, scheduling, auth, admin CRUD pages).
19. **i18n** — ES: 342 missing keys (PORTAL.*, RECEPTION.*); FR: 13 (PORTAL.SUMMARIES.*); hardcoded EN in `login.html`, `billing.html:294-361`, form placeholders, checkout dialog.
20. **Accessibility** — add skip-link, `aria-live` on toasts/async regions, shared `sr-only` utility, `prefers-reduced-motion`, focus-visible styles.
21. **Realtime consistency** — dashboard, nurse-station, patient-tracker poll via `setInterval` while STOMP infra already exists.

---

## Task List

### Phase 1 — P0 hardening (small, do first) — ✅ DONE 2026-08-18 (`feature/web-p0-hardening`)

- [x] 1. Drive UI permissions from backend: `PermissionService.loadFromBackend()` now fetches `GET /me/dashboard-config` `mergedPermissions` (union with static map as fallback; alias table bridges backend↔frontend permission names; nav rebuilds reactively via `effect()`)
- [x] 2. Nav entries added for orphaned routes (analytics, feature-flags, digital-signatures, lab-staff, lab-instruments, lab-inventory, my-care-team, my-documents, my-family-access; patient nav now uses `/my-notifications`). Note: `notification-settings` and `profile` were already reachable in-page — not gaps. `force-change-password` was dead code superseded by `account-setup` → deleted
- [x] 3. Stripped hardcoded `/api/` from dashboard, patient-portal, patient-tracker, in-basket, digital-signatures, assignment-public services (+ affected specs). Kept: auth refresh (intentional absolute URL) and SockJS `/api/ws-chat` (not HTTP-intercepted)
- [x] 4. Silenced 403s now reported once-per-URL to `POST /frontend-audit` (`type: SILENT_403`); errors still propagate to callers; `/frontend-audit` itself excluded from the 403 redirect
- [x] 5. GA: static gtag snippet removed from `index.html`; `AnalyticsService` bootstraps gtag at runtime only for a real `G-…` id; prod placeholder replaced with empty (disabled) + key added to dev/uat envs

### Phase 2 — Close broken workflows (P1) — ✅ DONE 2026-08-18 (`feature/web-p0-hardening`)

- [x] 6. `/discharge` page: approval queue (nurse request w/ auto-resolved registration, doctor approve/reject, cancel) + summary editor (unfinalized & pending-results worklists, med reconciliation / pending tests / follow-ups, finalize w/ signature, delete)
- [x] 7. Imaging Results view: hospital report list (status/modality/critical filters), report detail w/ measurements + status history, status updates, critical acknowledge, view-report from completed orders
- [x] 8. Lab: specimen collect/receive per order; result sign + acknowledge; Critical (unacknowledged) tab; comparison modal (trend, % change, significance); Reflex Rules manager in lab-test-config
- [x] 9. Patient chart tab: structured allergies CRUD (+audited deactivation), doctor-managed problem list, versioned chart-updates feed, audited doctor timeline; `PatientService.search()` added (list page still on `/patients?search=` — kept, both are server-side)
- [x] 10. Encounter detail: complete-examination + ready-for-discharge, AVS viewer, note history + addendums (also fixed addAddendum payload contract bug)
- [x] 11. Unit specs shipped with each feature (36 new service tests; 566 total green). E2E: deferred — Playwright flows for discharge/imaging-results/lab-depth need seeded backend fixtures (follow-up)

### Phase 3 — New modules (P2, by clinical value)

- [x] 12. Maternity module v1: maternal history (+ high-risk board, risk calc, mark-reviewed) and OB/GYN referrals with messaging — ✅ DONE (`/maternity` page: worklist board with risk badges + calculate-risk/mark-reviewed, versioned history form, referral lifecycle + messaging; referral attachments and high-risk care plans deferred)
- [x] 13. Maternity module v2: ultrasound orders/reports/templates, birth plans, prenatal scheduling, postpartum + newborn assessments — ✅ DONE (four new `/maternity` tabs: ultrasound worklists + report entry w/ NT & anatomy templates + review/notify, birth-plan CRUD + provider review, prenatal cadence generation + reschedule/reminders, postpartum observations + newborn assessments w/ backend alerts)
- [x] 14. Medical History tab on patient detail: social, family, immunizations (with overdue/upcoming) — ✅ DONE (History tab: versioned social summary+form, family worklists w/ genetic/screening filters, immunizations w/ client-computed overdue + mark-reminder-sent; roles mirror backend reads exactly)
- [x] 15. Pharmacy: medication-history timeline + pharmacy-fill recording (PHARMACIST workspace) — ✅ DONE (`/medication-history` page: timeline w/ overlap+interaction detection, polypharmacy warnings, fill record/edit; roles mirror backend exactly)
- [x] 16. Procedure orders page (order → consent-pending → scheduled → cancel) — ✅ DONE (`/procedure-orders`: status worklists + pending-consent, create modal, detail w/ schedule/consent/start/complete/postpone/site-mark/cancel; detail renders from list rows since HOSPITAL_ADMIN cannot GET-by-id)
- [x] 17. Insurance management + multi-hospital registration UI (reception + patient detail) — ✅ DONE (`/registrations` admin page w/ multi-hospital panel + Coverage tab on patient detail for insurance link/edit/delete and registration history; fixed the latent findActiveRegistration bare-array bug)
- [x] 18. Patient education: resource management + assignment/progress views — ✅ DONE (`/patient-education` page: resource library CRUD w/ category/type/search filters, evidence & warning-sign flags, view/completion/rating stats; per-patient assignment (POST progress NOT_STARTED) + progress/comprehension tracking w/ provider notes; Q&A + visit-documentation blocks deferred)
- [x] 19. Admin governance: assignment admin (CRUD, bulk-import, regenerate-code), permission-matrix snapshots/audit, org security policies/rules, super-admin governance (user import, credential health, security baselines) — ✅ DONE (`/admin-assignments`: paged worklist + filters, single/multi-scope create, edit, regenerate-code w/ verification-reset warning, resend, deactivate/delete, CSV bulk import; `/admin-governance` console (SUPER_ADMIN): permission-matrix snapshots w/ prefill+publish+audit trail, security policies/rules CRUD, user CSV import + force-password-reset + rotation health, credential health (read-only, MFA/recovery upserts deferred), baselines + export, rule-set templates/import/simulation)
- [ ] 20. Digital signatures: sign/verify/revoke flows; billing: invoice email + search

### Phase 4 — Quality floor (P3, parallelizable)

- [x] 21. Raise spec coverage: prioritize billing, encounters, admissions, imaging, consultations, scheduling, auth interceptors; enforce via `coverage-thresholds.json` ratchet — DONE (`feature/coverage-ratchet`): 120 new specs took billing/scheduling/imaging/consultations/encounters from 0% and interceptors from 30%→95%; `scripts/check-coverage.mjs` enforces global + per-module floors from `coverage-thresholds.json`; CI runs `test:coverage` + `coverage:check`. Follow-up: encounter-note-form + eligibility-check-dialog sub-components still untested (encounters floor pinned at 47%)
- [x] 22. i18n: backfill 342 ES + 13 FR keys; sweep hardcoded EN strings; add key-parity CI check — DONE (`feature/i18n-backfill`): FR was already 100%; ES backfilled 515 keys (NAV/PORTAL/RECEPTION/PHARMACY) to 100%, removed 1 orphan ES key; swept hardcoded EN out of `login.html`/`login.ts`, `billing.html`, checkout dialog (46 new keys ×3 locales); CI gate switched from 99%/89% thresholds to strict key parity (`npm run i18n:parity`)
- [x] 23. A11y pass: skip-link, `aria-live` regions, global `sr-only`, reduced-motion, focus-visible — DONE (`feature/a11y-pass`): skip-link + focus-visible were already shipped (row 11); this pass added live-region toasts (`role="alert"` for errors, `role="status"` otherwise, aria-hidden icons, labelled close button), a global `.sr-only` utility in `styles.scss` (local copies in stock-routing/pre-checkin removed), and a global `prefers-reduced-motion: reduce` kill-switch (0.01ms durations so end-events still fire)
- [x] 24. Migrate dashboard/nurse-station/patient-tracker polling to STOMP topics — DONE (`feature/live-flow-updates`): patient-tracker was already STOMP-driven (WS-gated 120s/30s poll); `PatientTrackerWsService` is now ref-counted so tracker + dashboard + nurse-station share one socket; clinician dashboard (previously manual-refresh only) and nurse-station now subscribe to `/topic/patient-tracker/{hospitalId}` and refresh their encounter-driven panels within seconds (debounced). Polling retained as heartbeat for time-driven data (vitals due, MAR) — no backend events exist for those domains yet (follow-up: publishers for MAR/vitals/tasks/inbox/orders/handoffs/announcements/admissions)

---
---

# Patient Tracker Board & Discharge → Encounter Auto-Complete

> Two bugs reported: Patient Tracker Board ignores carry-over encounters
> from prior days, and discharging an admission doesn't auto-complete the
> linked encounter.

## User Stories

### Story 1 — Carry-over encounters appear on the tracker board
**As a** Doctor/Nurse viewing the Patient Tracker Board,  
**I want** active encounters from prior days (e.g., IN_PROGRESS from yesterday) to appear on today's board,  
**So that** I can see all patients still in the clinic regardless of which day their encounter started.

**Acceptance Criteria:**
- Encounters in a non-terminal status (anything except COMPLETED/CANCELLED) that started before today still appear on the tracker board
- No duplicate entries when an encounter is from today AND active
- Board count and average wait reflect all active encounters (including carry-overs)

### Story 2 — Discharge auto-completes the associated encounter
**As a** Doctor/Nurse discharging a patient,  
**I want** the patient's active encounter(s) to automatically move to COMPLETED when I discharge them,  
**So that** discharged patients don't remain stuck as "In Progress" on the encounter list.

**Acceptance Criteria:**
- After admission discharge, any non-terminal encounters for the same patient+hospital are completed
- checkoutTimestamp is set to the discharge time
- Already-completed encounters are not affected

---

## Root Cause Analysis

| Bug | Root Cause |
|-----|-----------|
| Tracker empty for carry-overs | `PatientTrackerServiceImpl.getTrackerBoard()` queries `findAllByHospitalAndDateRange(today)` — encounters from yesterday are not included |
| Encounter stuck after discharge | `AdmissionServiceImpl.dischargePatient()` only sets admission status; no code touches the encounter |

---

## Task List

- [x] 1. Add `findCarryOverEncounters()` query to EncounterRepository — ✅ DONE (commit 31239f72)
- [x] 2. Update PatientTrackerServiceImpl to merge carry-over encounters — ✅ DONE (ID-deduped merge; counts/avg-wait include carry-overs)
- [x] 3. Inject EncounterRepository into AdmissionServiceImpl — ✅ DONE (since refactored to shared `EncounterAutoCompletionService`)
- [x] 4. Auto-complete active encounters in dischargePatient() — ✅ DONE (+ follow-up: discharge-approval `approve()` now also completes encounters, and both paths publish tracker WS events)
- [x] 5. Add JUnit tests for carry-over encounters (PatientTrackerServiceImplTest) — ✅ DONE (3 cases)
- [x] 6. Add JUnit tests for discharge auto-complete (AdmissionServiceImplTest) — ✅ DONE (+ EncounterAutoCompletionServiceTest, DischargeApprovalServiceImplTest coverage)
- [x] 7. Build, format, test, lint, JaCoCo — ✅ DONE
- [x] 8. Commit and push — ✅ DONE

> Follow-up (2026-08-19, `fix/tracker-discharge-residual-gaps`): today's staff-less encounters were still invisible on the board — `findAllByHospitalAndDateRange` used inner `JOIN FETCH e.staff` while the carry-over query used LEFT JOIN, so an unassigned walk-in only appeared the *next* day as a carry-over. Both queries now LEFT JOIN. Discharge via the approval flow now completes encounters too, and tracker boards get WebSocket pushes on discharge instead of waiting for the next poll.

---
---

# Lab Role Permission — Gap Analysis & Task List

> Generated from code audit against the Feature × Lab Role matrix.  
> Roles: **LAB_TECHNICIAN**, **LAB_SCIENTIST**, **LAB_MANAGER**, **LAB_DIRECTOR**, **QUALITY_MANAGER**  
> **Status: ✅ ALL GAPS FIXED (controller + service layer + i18n)** — branch `fix/lab-role-permission-gaps`

---

## Summary

| Metric              | Count |
|---------------------|-------|
| **Total gaps found**| 12    |
| **Gaps fixed**      | 8     |
| **No action needed**| 4     |
| **Backend gaps**    | 5     |
| **Frontend gaps**   | 7     |
| **High-risk flags** | 1     |

---

## Gap 1 — ✅ FIXED — `ROLE_LAB_MANAGER` missing from frontend `permission.service.ts`

**Impact:** LAB_MANAGER sees **zero** permission-gated nav items (Patients, Staff, Lab, Departments, Scheduling, etc.) — the entire sidebar is gutted.

| Expected (matrix) | Actual (code) |
|---|---|
| ✅ Patients, Lab, Staff, Departments, Scheduling, etc. | ❌ No entry in `ROLE_PERMISSIONS` map |

**File:** `hospital-portal/src/app/core/permission.service.ts`  
**Fix:** Add `ROLE_LAB_MANAGER` entry with: `View Dashboard`, `View Lab`, `Process Lab Tests`, `View Patient Records`, `View Staff`, `View Staff Schedules`, `View Departments`, `View Notifications`

---

## Gap 2 — ✅ FIXED — Lab Approval Queue nav hidden from `LAB_SCIENTIST` and `LAB_MANAGER`

**Impact:** LAB_SCIENTIST and LAB_MANAGER cannot reach the Lab Approval Queue from the sidebar, even though the route guard and backend allow them.

| Expected (matrix) | Actual (shell.ts) |
|---|---|
| LAB_SCIENTIST ✅, LAB_MANAGER ✅ | ❌ Nav only shown for `ROLE_LAB_DIRECTOR` and `ROLE_QUALITY_MANAGER` |

**File:** `hospital-portal/src/app/shell/shell.ts` (lines ~349-370)  
**Fix:** Expand the `hasAnyRole` guard for the Lab Approval Queue + QC Dashboard nav block to include `ROLE_LAB_SCIENTIST` and `ROLE_LAB_MANAGER`.

**Note:** The route guard in `app.routes.ts` already allows `ROLE_LAB_SCIENTIST` and `ROLE_LAB_MANAGER` for `lab-approval-queue`, so this is nav-only.

---

## Gap 3 — ✅ FIXED — QC Dashboard nav hidden from `LAB_MANAGER`

**Impact:** LAB_MANAGER cannot see the QC Dashboard nav link despite the route guard in `app.routes.ts` allowing `ROLE_LAB_MANAGER`.

| Expected (matrix) | Actual (shell.ts) |
|---|---|
| LAB_MANAGER ✅ | ❌ Nav only shown for `ROLE_LAB_DIRECTOR` and `ROLE_QUALITY_MANAGER` |

**File:** `hospital-portal/src/app/shell/shell.ts` (same block as Gap 2)  
**Fix:** Already addressed by Gap 2 fix — expanding the `hasAnyRole` guard.

---

## Gap 4 — ✅ FIXED — Ops Dashboard nav missing from shell.ts entirely

**Impact:** No role can see the Ops Dashboard in the sidebar nav. The route exists in `app.routes.ts` and the component exists (`lab-ops-dashboard/`), but there is no nav item for it.

| Expected (matrix) | Actual (shell.ts) |
|---|---|
| LAB_MANAGER ✅, LAB_DIRECTOR ✅, QUALITY_MANAGER ✅ | ❌ No nav item exists |

**File:** `hospital-portal/src/app/shell/shell.ts`  
**Fix:** Add an Ops Dashboard nav item gated by `hasAnyRole(['ROLE_LAB_DIRECTOR', 'ROLE_LAB_MANAGER', 'ROLE_QUALITY_MANAGER'])`.

---

## Gap 5 — ✅ FIXED — `LabTestDefinitionController` MANAGE_ROLES missing `LAB_DIRECTOR`

**Impact:** LAB_DIRECTOR cannot create or edit test definitions. Matrix says ✅.

| Expected (matrix) | Actual (code) |
|---|---|
| LAB_DIRECTOR ✅ for create/edit | ❌ `MANAGE_ROLES = "hasAnyRole('HOSPITAL_ADMIN', 'LAB_MANAGER', 'LAB_SCIENTIST', 'SUPER_ADMIN')"` |

**File:** `hospital-core/src/main/java/com/example/hms/controller/LabTestDefinitionController.java` (line 47)  
**Fix:** Add `'LAB_DIRECTOR'` to the `MANAGE_ROLES` constant.

---

## Gap 6 — ✅ FIXED — `LabTestDefinitionController` MANAGE_ROLES missing `QUALITY_MANAGER`

**Impact:** QUALITY_MANAGER cannot create or edit test definitions. Matrix says ✅.

| Expected (matrix) | Actual (code) |
|---|---|
| QUALITY_MANAGER ✅ for create/edit | ❌ Not in `MANAGE_ROLES` |

**File:** `hospital-core/src/main/java/com/example/hms/controller/LabTestDefinitionController.java` (line 47)  
**Fix:** Add `'QUALITY_MANAGER'` to the `MANAGE_ROLES` constant. *(Can combine with Gap 5 fix)*

---

## Gap 7 — ✅ FIXED — `LabResultController` create/update missing `LAB_DIRECTOR` and `QUALITY_MANAGER`

**Impact:** LAB_DIRECTOR and QUALITY_MANAGER cannot create or update lab results. Matrix says ✅ for "Lab Results (enter/verify)".

| Endpoint | Expected | Actual |
|---|---|---|
| `POST /lab-results` (create) | LAB_DIRECTOR ✅, QM ✅ | ❌ Both missing |
| `PUT /lab-results/{id}` (update) | LAB_DIRECTOR ✅, QM ✅ | ❌ Both missing |

**File:** `hospital-core/src/main/java/com/example/hms/controller/LabResultController.java` (lines 49, 86)  
**Fix:** Add `'LAB_DIRECTOR'`, `'QUALITY_MANAGER'` to the `@PreAuthorize` for create and update endpoints.

---

## Gap 8 — `LabTestDefinitionController` view route guard missing `QUALITY_MANAGER` in `app.routes.ts`

**Impact:** The `lab-test-config` frontend route allows `LAB_DIRECTOR`, `LAB_MANAGER`, `LAB_SCIENTIST` — but NOT `QUALITY_MANAGER`. Matrix says QUALITY_MANAGER gets ❌ for "Lab Test Config", so this is **actually correct per the matrix** and NOT a gap.

**Status:** ✅ No action needed — matrix confirms QUALITY_MANAGER should NOT have Lab Test Config access.

---

## Gap 9 — ✅ FIXED — Backend `DashboardConfigService` missing default permissions for 3 roles

**Impact:** When the backend merges persisted permissions with defaults, `LAB_TECHNICIAN`, `LAB_DIRECTOR`, and `QUALITY_MANAGER` get zero defaults. If those roles have no DB-persisted permissions, they have an empty permission set on the API side.

| Role | In `DashboardConfigService.createDefaultPermissions()` |
|---|---|
| LAB_TECHNICIAN | ❌ Missing |
| LAB_DIRECTOR | ❌ Missing |
| QUALITY_MANAGER | ❌ Missing |
| LAB_SCIENTIST | ✅ Present |
| LAB_MANAGER | ✅ Present |

**File:** `hospital-core/src/main/java/com/example/hms/service/DashboardConfigService.java`  
**Fix:** Add default permissions for `ROLE_LAB_TECHNICIAN`, `ROLE_LAB_DIRECTOR`, and `ROLE_QUALITY_MANAGER` mirroring the frontend `permission.service.ts` entries.

---

## Gap 10 — ✅ FIXED — `LabResultController` pending-review missing `LAB_TECHNICIAN` and `LAB_MANAGER`

**Impact:** LAB_TECHNICIAN and LAB_MANAGER cannot view lab results pending review. This seems intentional per the matrix (Lab Approval Queue is ❌ for Technician, ✅ for Manager).

| Role | Expected (matrix) | Actual |
|---|---|---|
| LAB_TECHNICIAN | ❌ Lab Approval Queue | ❌ Not in pending-review — **Correct** |
| LAB_MANAGER | ✅ Lab Approval Queue | ❌ Not in pending-review `@PreAuthorize` |

**File:** `hospital-core/src/main/java/com/example/hms/controller/LabResultController.java` (line 77)  
**Fix:** Add `'LAB_MANAGER'` to the `pending-review` endpoint `@PreAuthorize`. LAB_TECHNICIAN exclusion is correct.

---

## Gap 11 — `LabResultController` release missing `LAB_TECHNICIAN`

**Impact:** LAB_TECHNICIAN cannot release lab results. Matrix says ✅ for "Lab Results (enter/verify)" — however, "release" is a supervisory action distinct from "enter/verify".

| Expected (matrix) | Actual (code) |
|---|---|
| Ambiguous — matrix says "enter/verify" ✅ | ❌ LAB_TECHNICIAN not in release `@PreAuthorize` |

**Status:** ⚠️ Intentional — release is an elevated action. LAB_TECHNICIAN should enter/verify but not release. **No action needed** unless matrix explicitly requires release for Technician.

---

## Gap 12 — `LabQcEventController` summary missing `LAB_SCIENTIST`

**Impact:** LAB_SCIENTIST cannot view QC summary (aggregated stats). Matrix says QC Events review/approve ❌ for LAB_SCIENTIST, so this is **correct per the matrix**.

**Status:** ✅ No action needed.

---

## Confirmed Correct (No Gaps)

These were investigated and confirmed matching the matrix:

| Feature | Status |
|---|---|
| Lab Orders (view/enter) — all 5 roles | ✅ Correct |
| QC Events (record) — all 5 roles | ✅ Correct |
| QC Events (review/approve) — only MANAGER, DIRECTOR, QM | ✅ Correct |
| Validation Studies — excludes LAB_TECHNICIAN | ✅ Correct |
| Lab Instruments — TECH, MANAGER, DIRECTOR (not SCIENTIST, QM) | ✅ Correct |
| Lab Inventory — TECH, MANAGER, DIRECTOR (not SCIENTIST, QM) | ✅ Correct |
| Consent Management — only DIRECTOR, QM | ✅ Correct |
| Staff Scheduling (view) — all 5 roles | ✅ Correct |
| Staff (create/update) — MANAGER, DIRECTOR | ✅ Correct |
| Staff (delete) — DIRECTOR only | ✅ Correct |
| Lab Test Config route — DIRECTOR, MANAGER, SCIENTIST (not QM, TECH) | ✅ Correct |
| Test Definitions (approve) — DIRECTOR, MANAGER, SCIENTIST, QM (not TECH) | ✅ Correct |
| Test Definitions (view) — all 5 roles | ✅ Correct |

---

## Actionable Fix Tasks (Ordered by Dependency)

### Backend

| # | Task | File | Roles to Add |
|---|---|---|---|
| 1 | Add `LAB_DIRECTOR`, `QUALITY_MANAGER` to `MANAGE_ROLES` | `LabTestDefinitionController.java:47` | `LAB_DIRECTOR`, `QUALITY_MANAGER` |
| 2 | Add `LAB_DIRECTOR`, `QUALITY_MANAGER` to create `@PreAuthorize` | `LabResultController.java:49` | `LAB_DIRECTOR`, `QUALITY_MANAGER` |
| 3 | Add `LAB_DIRECTOR`, `QUALITY_MANAGER` to update `@PreAuthorize` | `LabResultController.java:86` | `LAB_DIRECTOR`, `QUALITY_MANAGER` |
| 4 | Add `LAB_MANAGER` to pending-review `@PreAuthorize` | `LabResultController.java:77` | `LAB_MANAGER` |
| 5 | Add default permissions for `LAB_TECHNICIAN`, `LAB_DIRECTOR`, `QUALITY_MANAGER` | `DashboardConfigService.java` | — |

### Frontend

| # | Task | File | Change |
|---|---|---|---|
| 6 | Add `ROLE_LAB_MANAGER` to `ROLE_PERMISSIONS` map | `permission.service.ts` | New entry with lab/staff/dept permissions |
| 7 | Expand Lab Approval Queue + QC Dashboard `hasAnyRole` in shell | `shell.ts:~349` | Add `ROLE_LAB_SCIENTIST`, `ROLE_LAB_MANAGER` |
| 8 | Add Ops Dashboard nav item | `shell.ts` | New nav block for DIRECTOR, MANAGER, QM |

### Tests

| # | Task | File |
|---|---|---|
| 9 | Update `LabTestDefinitionController` tests for new roles | Backend test files |
| 10 | Update `LabResultController` tests for new roles | Backend test files |
| 11 | Update `permission.service.spec.ts` for `ROLE_LAB_MANAGER` | Frontend test files |
| 12 | Update `shell.spec.ts` for new nav items | Frontend test files |

---

## Patient Access Analysis — Which Lab Roles Don't Need Patient Records?

| Role | Needs Patient Records? | Reason |
|---|---|---|
| **LAB_TECHNICIAN** | ⚠️ Debatable | Technicians process specimens, not patients directly. They need the **lab order** (which references a patient) but rarely need to browse the full patient chart. Current access: ✅ — could be **downgraded to read-only lab-order context** if PHI minimization is a priority. |
| **LAB_SCIENTIST** | ✅ Yes | Scientists verify results in clinical context — they need patient history, allergies, and prior results to validate abnormal findings. |
| **LAB_MANAGER** | ✅ Yes | Managers oversee lab operations and occasionally review patient-related quality issues. |
| **LAB_DIRECTOR** | ✅ Yes | Directors are responsible for lab compliance and need access for audits and escalations. |
| **QUALITY_MANAGER** | ⚠️ Debatable | QMs focus on process quality (QC events, SOPs, accreditation) not individual patient care. They may need **aggregate/anonymized** data rather than individual patient records. Current access: ✅ — could be reviewed for PHI minimization. |

### Recommendation

- **Keep access** for LAB_SCIENTIST, LAB_MANAGER, LAB_DIRECTOR — clinical and operational need.
- **Review for restriction** on LAB_TECHNICIAN and QUALITY_MANAGER — they could potentially work with lab-order-scoped views instead of full patient records. However, this would be a future enhancement, not a bug fix.

---

## Service-Layer Alignment (PR Review Follow-up)

Copilot PR review identified that controller-level `@PreAuthorize` expansions were ineffective because **service-layer enforcement** was more restrictive. Fixed:

### Fix A — `LabTestDefinitionServiceImpl.assertUserCanManageHospital()`

**Problem:** Role set only included `HOSPITAL_ADMIN`, `LAB_MANAGER`, `SUPER_ADMIN`, `LAB_SCIENTIST`. LAB_DIRECTOR and QUALITY_MANAGER would get `AccessDeniedException` on create/update/delete despite passing the controller gate.

**Fix:** Added `ROLE_LAB_DIRECTOR` and `ROLE_QUALITY_MANAGER` to the `Set.of(...)` in `assertUserCanManageHospital()`.

### Fix B — `LabResultServiceImpl.validateLabScientistOrMidwife()`

**Problem:** Only allowed `ROLE_LAB_SCIENTIST` or midwife. All other roles in the controller `@PreAuthorize` (DOCTOR, LAB_TECHNICIAN, LAB_MANAGER, LAB_DIRECTOR, QUALITY_MANAGER, NURSE) would get `BusinessException` on create/update.

**Fix:** Renamed to `validateLabResultAuthor()` and expanded to all roles from the controller gate: LAB_SCIENTIST, MIDWIFE, DOCTOR, NURSE, LAB_TECHNICIAN, LAB_MANAGER, LAB_DIRECTOR, QUALITY_MANAGER.

### Fix C — Missing i18n key `NAV.OPS_DASHBOARD`

**Problem:** The new Ops Dashboard nav item used `translationKey: 'NAV.OPS_DASHBOARD'` but the key was missing from all locale files, rendering the raw key in the UI.

**Fix:** Added `OPS_DASHBOARD` to `en.json` ("Ops Dashboard"), `fr.json` ("Tableau de Bord Opérationnel"), `es.json` ("Panel de Operaciones").

---

*Last updated: 2026-04-07 — all gaps fixed (controller + service layer + i18n) on `fix/lab-role-permission-gaps`*

---

# Epic-Alignment P0 — Shipped (2026-04-28)

> All four interoperability blockers (FHIR R4, HL7 v2 MLLP, CDS Hooks,
> SMART-on-FHIR) landed in PR #139, promoted to `main` via #140 (develop→uat)
> and #141 (uat→main). See `claude/finding-gaps.md` for the full audit.

## Done

- [x] **P0.1 — FHIR R4 read API** at `/api/fhir/*` (HAPI 7.4.5). Patient, Encounter, Observation (vitals + labs), Condition, MedicationRequest, Immunization. — `docs/fhir.md`
- [x] **P0.2 — HL7 v2 MLLP TCP listener** (off by default, bounded thread pool, ORU^R01 + ADT^A01/A04/A08, framed AA/AE/AR ACK). — `docs/hl7-mllp.md`
- [x] **P0.3 — CDS Hooks 1.0** services at `/api/cds-services` (`hms-patient-view`, `hms-medication-allergy-check`). — `docs/cds-hooks.md`
- [x] **P0.4 — SMART-on-FHIR App Launch 1.0** discovery + CapabilityStatement OAuth security extension. — `docs/smart-on-fhir.md`

Quality gates (PR #139): 13/13 GitHub CI checks, SonarCloud gate clean (0 PR issues after 26 → 2 → 0 cleanup), JaCoCo 80% threshold satisfied, 17 new backend tests.

---

# Epic-Alignment P1 — Active queue

Top-down priority. Each item ships as one PR per the foundation-pass pattern in [`.claude/skills/pr-review-response/SKILL.md`](.claude/skills/pr-review-response/SKILL.md) + [`.claude/skills/liquibase-migration/SKILL.md`](.claude/skills/liquibase-migration/SKILL.md) (backend + tests + Liquibase + frontend in the same PR). West-Africa context lives in `claude/finding-gaps.md`.

- [ ] **P1.1 — Terminology binding** (gap #5)
  - [ ] LOINC on `LabTestDefinition` (column + DTO + Liquibase + UI)
  - [ ] ICD-10/11 on `PatientProblem` (already has `icdVersion` — wire validation + admin curation)
  - [ ] WHO ATC + RxNorm on `MedicationCatalogItem`
  - [ ] Update FHIR mappers to advertise the bound systems (`http://loinc.org`, `http://hl7.org/fhir/sid/icd-10`, `http://www.nlm.nih.gov/research/umls/rxnorm`, WHO ATC)

- [ ] **P1.2 — MLLP / FHIR persistence**
  - [ ] Resolve OBR-3 → `LabOrder.id` from analyzer messages (with allowlisted facility mapping)
  - [ ] Persist ORU^R01 results as `LabResult` rows via the existing `LabResultService`
  - [ ] Project ADT^A01/A04/A08 into `Patient` + `Encounter` via the EMPI service
  - [ ] Per-facility allowlist (sending facility → hospital)

- [ ] **P1.3 — CDS rule engine** (gap #3 expanded)
  - [ ] Drug-drug interaction check on `order-sign` (depends on P1.1 RxNorm)
  - [ ] Duplicate-order detection on `order-sign`
  - [ ] Pediatric dose check (uses `Patient.dateOfBirth` + bound dose)
  - [ ] BPA scaffolding for protocol cards (malaria, sepsis, OB hemorrhage)

- [ ] **P1.4 — CPOE order-set builder** (gap #6) — versioned templates, search-driven picker

- [ ] **P1.5 — Storyboard patient banner** (gap #15) — persistent allergy / problem / encounter / code-status header on every chart route

- [ ] **P1.6 — Chart Review tabbed viewer** (gap #16) — Encounters / Notes / Results / Meds / Imaging / Procedures with timeline

- [ ] **P1.7 — Cadence visual scheduling grid** (gap #17) — FullCalendar multi-resource block view

- [ ] **P1.8 — Inpatient eMAR** (gap #10) — barcode-scan administration loop on top of pharmacy + MAR entities

- [ ] **P1.9 — Break-the-glass workflow** (gap #21) + **granular consent scopes** (gap #22)

- [ ] **P1.10 — Telehealth low-bandwidth** (gap #12) — audio + photo + chat reusing the chat module

- [ ] **P1.11 — DHIS2 ADX export** (gap #14) — immunization, ANC, malaria reporting tied to FHIR `Immunization`

- [ ] **P1.12 — Referral lifecycle** (gap #13) — accept / decline / complete states on `GeneralReferral`

P2 backlog (gaps #9, #11, #18, #19, #20, #23, #24) tracked in `claude/finding-gaps.md`.

*Last updated: 2026-04-28 — P0 shipped to main via #139/#140/#141*

---
---

# Epic Parity Gap Tasklist — 2026-08-20

> Derived from the **HMS × Epic Parity Ledger** audit (13 inspection agents, develop @ 63ddd536,
> 197 present / 113 absent capabilities verified with file-level evidence):
> <https://claude.ai/code/artifact/a2d071f3-8b46-49a6-b2f1-10ad85ae87f2>
> Ranked by risk-to-clinical-truth first, then leverage. One item = one PR into develop unless noted.
> Deliberate LMIC non-goals (NOT tasks): Surescripts/NCPDP, X12 837/835, US registries, SNOMED,
> video visits, full revenue cycle (roadmap: partner with a billing vendor).

## P0 — Clinical-truth risks (fabricated or dead-end data in production UI)

- [x] 1. **Nurse handoffs: real entity or removal** — ✅ DONE (PR #431 `feature/nurse-station-real-data`, V108 + V110): — `NurseTaskServiceImpl` fabricates handoff rows ("still synthetic, entity arrives in MVP 2"); completion buttons operate on generated data. Build the handoff entity (SBAR/I-PASS fields) + endpoints + wire the existing UI, or pull the surface until it's real.
- [x] 2. **Nurse order-task queue: real entity or removal** — ✅ DONE (PR #431): queue and dashboard counts now derive from real lab/imaging/procedure orders. — same file, "Orders — still synthetic (MVP 3)"; dashboard counts derive from fabricated lists. Same treatment as #1 (can share a PR if the entity work overlaps).
- [x] 3. **Lab pending-review: delete the hardcoded endpoint** — ✅ DONE (PR #432 `fix/lab-pending-review-synthetic`): — `LabResultServiceImpl.getPendingReviewResults` returns invented patients ("Ava Johnson", "Michael Chen"); the real queue is `/me/results/review-queue`. Remove or redirect the fake path and any consumer.
- [x] 4. **Bed/ward decision** — ✅ DONE (PR #433 `feature/bed-management`), built rather than dropped: — schema (V25), repositories, and an occupancy dashboard exist with **zero writers** (`bed_id` never populated; admissions use free-text `roomBed`). Either build bed CRUD + assignment workflow (unlocks census/bed board, in-app transfers) or drop the tables + occupancy tiles. Decide before any inpatient-logistics work.
- [x] 5. **Critical-value escalation chain** — ✅ CLOSED 2026-08-22 (PR #462 `fix/reassessment-p0-p1`): the mismatch read-back now persists through a `REQUIRES_NEW` transaction that reloads the row inside the inner transaction (a `BusinessException` in the caller no longer rolls it back); `acknowledgeResult` refuses a critical result with no read-back; `signPrescription`-style bypass closed on `signLabResult` too (lab attestation no longer auto-silences the ordering-clinician receipt); `criticalReadBackValue` surfaced through mapper → DTO → portal. Was reopened 2026-08-21: a mismatched read-back is *never persisted* — `recordReadBack` is `@Transactional` and throws `BusinessException` (a `RuntimeException`), so the row it just wrote rolls back, while `CriticalValueNotificationService.java:217-218` and `V116…sql:41-42` both assert the mismatch is stored "verbatim so a mismatch is auditable". `critical_readback_value` is therefore write-only. Read-back is also never *required*: `acknowledgeResult` sets `acknowledged=true` with no critical-value guard, and the sweep exits on `acknowledged=false`, so acknowledging still silences a critical result without any read-back. Previously recorded as: ✅ DONE (PR #434, completed by PR #451 `feature/critical-value-readback-escalation`): — results are flagged + acknowledgeable but nothing notifies the ordering provider; add the notify → read-back → timer/escalation loop (IKODDI SMS + in-app; PR #430 gives the transport).

## P1 — Complete the specialty core + turn on what's already built

- [x] 6. **Labor & delivery: partograph + delivery record** — ✅ DONE (`feature/labor-delivery-partograph`): `clinical.labor_episodes` + `labor_partograph_entries` + `delivery_records` (V111), WHO alert/action-line evaluation with URGENT notifications, PPH/APGAR/stillbirth alerts, Labor & Delivery tab with SVG partograph chart, `NewbornAssessment.delivery_record_id` back-link (the audit's deferred pregnancy FK), episode outcome = the audit's missing `Pregnancy.outcome`.
- [x] 7. **Appointment reminders over SMS** — ✅ DONE (`feature/appointment-sms-reminders`): 15-min sweep reminds patients of appointments starting within 24 h — in-app push + IKODDI SMS (`deliversRealSms()` guard), `reminder_sent_at` exactly-once stamp (V112), first dispatch path to honour the stored `APPOINTMENT_REMINDER` notification preferences, FR-default localized message, manual trigger `POST /appointments/reminders/run`.
- [x] 8. **EMPI: finish probabilistic matching + merge** — ✅ DONE (`feature/empi-confirm-merge`): `/empi` admin controller (merge-by-patient w/ identity provisioning, identity merge, by-patient lookup); merge now reassigns aliases (fixes post-merge MRN lookups resolving to the dead identity), emits `PATIENT_MERGE` audit, rejects cross-tenant merges; matcher candidates scoped to the caller's hospital (tenant leak fixed); confirm-match navigates to the existing patient + `(confirm)` output; admin two-click merge mode in the panel; three stale "matcher returns empty" docs corrected. Out of scope (stated): clinical deep-merge, undo, HL7 A40 inbound.
- [x] 9. **Web parity: cancel/reschedule + proxy views** — ✅ CLOSED 2026-08-22 (PR #462): proxy appointments now flow through a new `AppointmentService.getAppointmentsForVerifiedPatient(patientId)` (no username resolution — the proxy layer has already verified access), the `isNull()`-stubbed test re-written against the real method; grant form gained the missing `expiresOn` date input (end-of-day, min today). Was reopened 2026-08-21: the proxy viewer's Appointments tab throws at runtime. `PatientPortalServiceImpl.java:1259` passes a null username into `AppointmentServiceImpl.java:755`, which calls `getUserOrThrow(username)` unconditionally. `PatientPortalServiceImplProxyTest.java:114` stubs that call with `isNull()`, so the contract is *asserted* rather than exercised and no test fails. Cancel/reschedule themselves verify clean. Previously recorded as: ✅ DONE (`feature/web-appointment-proxy-parity`): patient-portal cancel + reschedule modals wired to the previously dead `PUT /me/patient/appointments/{cancel,reschedule}` service methods (all four reschedule fields sent — Android omits `newEndTime` and silently 400s); cancelled appointments now stay visible in Past instead of vanishing; new proxy data viewer (`/my-family-access/:patientId`) consuming all five `proxy-access/{patientId}/…` endpoints with permission-driven tabs. Two bugs fixed: the web grant form emitted a scope vocabulary (`APPOINTMENTS`) the backend never matches (`VIEW_APPOINTMENTS`) so every non-`ALL` web grant 403'd — form corrected + legacy tokens normalized server-side; and `expiresAt`/`EXPIRED` were stored but never enforced, so expired grants still read PHI.
- [x] 10. **Patient-facing education delivery** — ✅ DONE (`feature/patient-education-delivery`): new `/me/patient/education*` self-service API (list assigned + read one + record progress/rating/understanding + ask & list questions), all IDOR-safe via `resolvePatientId`; a `PatientEducationProgress` row IS the assignment record, so no entity or migration was needed. New `/my-education` portal page: to-read / completed / questions tabs, warning-sign safety banner, reader with mark-read → confirm-understanding → rate, and patient-authored questions (the entity, service method and Swagger description existed but no patient could reach them). Security fix: `GET /patient-education/progress/{id}` and `/questions/{id}` were `isAuthenticated()` with no ownership check — an IDOR the moment patients hold tokens; now staff-only. Dead columns `completionCount`/`ratingCount` (always 0) are now written on patient completion/rating.

### Field-reported fixes (not numbered — found in production, outside the audit's scope)

- [x] **Refill approval queue was unreachable** — ✅ DONE (`fix/refill-approval-reachability`): the whole chain existed and worked — patient submits, `notifyCareTeamForRefillRequest` emails and in-app-notifies the prescriber, `GET /refills` returns their queue, approve/reject enforce prescriber + status — but no click anywhere in the portal reached `/refills`. No sidebar entry, both dashboard "Refills" tiles pointed at `/prescriptions`, and the one `router.navigate(['/refills'])` fired on an inbox category `ResultReviewServiceImpl` never emitted (its `RefillRequestRepository` was injected and unused). Added the sidebar entry, corrected both tile routes, added a doctor tile with the pending count, and wrote the missing `REFILL_REQUEST` inbox emitter. Also added the missing **pause/hold** state (`RefillStatus.PAUSED`, `PUT /refills/{id}/pause`, reason mandatory since the patient is told): a held request stays actionable, the patient can still cancel it, and it drops out of the inbox so the queue clears. The `pharmacy-refill.spec.ts` E2E masked all of this — it deep-links to `/refills`, and asserted a `PENDING` filter that neither the component nor the enum has.
  - **Still open, deliberately:** approval is terminal — it writes `APPROVED` and creates no downstream prescription or pharmacy order — and `DISPENSED` is declared but nothing ever writes it. That's a workflow decision, not a wiring bug.

### Verification pass — 2026-08-21

All ten P0/P1 items re-verified against develop with code evidence rather than
checkboxes (the P0 boxes above were stale: every one of items 1–5 had shipped in
PRs #431–#434 and none had been ticked). Seven were genuinely closed. Three were
partial and have been completed:

- **#5 critical-value escalation** — read-back did not exist (the acknowledge
  endpoint takes no body, so nothing recorded *what* the clinician was told), and
  the escalation re-notified the same provider once then stamped a flag the sweep
  excluded on, so an unacknowledged critical result went permanently silent after
  two alerts to one person. Closed by PR #451 (V116).
- **#6 labor & delivery** — the `NewbornAssessment.delivery_record_id` back-link
  was persistence-only: column, FK and `@ManyToOne` with nothing setting or
  reading them. Closed by PR #450. Note the remaining half-bridge:
  `DeliveryRecord` holds no reference to a newborn patient at all, so nothing can
  populate the link automatically yet — that is a maternity-workflow design
  decision, not wiring.
- **#8 EMPI** — the merge panel shipped unclickable (no nav entry anywhere, the
  same defect class as the refill queue), and the merge endpoints checked the two
  identities against each other but never against the caller, so a hospital-A
  admin could merge hospital-B patients. Closed by PR #449.

Also landed alongside: PR #447 (one orphaned `patient_id` returned 500 for the
entire registrations desk) and PR #448 (the foreign key that would have prevented
it — the schema has none at all, because V1 came from Hibernate SchemaExport).

Open decisions left for a human, all stated in the relevant PR bodies:
`app.empi.probabilistic.enabled` still defaults to false; the surviving orphaned
registration row is untouched; FK coverage beyond `patient_id` needs a call on
what a hospital delete should do to its registrations.

## P2 — Structural gaps with high leverage

- [x] 11. **Slot inventory for scheduling** — ✅ CLOSED 2026-08-22 (PR #467 `feature/slot-inventory-population`, no migration): the model can now be populated end-to-end — visit-type + session-template CRUD (`/visit-types`, `/session-templates`: duplicate code refused pointing at reactivate, retired visit type refused, window-shorter-than-one-slot refused because generation would silently produce nothing, unscoped super-admin refused, foreign staff/department/rows 404) and the `/slot-admin` portal page (HOSPITAL_ADMIN/SUPER_ADMIN), first caller of all five `/slots` endpoints (generate, search, hold, release, block-with-reason). `capacity_per_slot` deliberately not exposed (unimplementable under `uq_slot_staff_start`); booking an Appointment from a slot still open (which model owns the time — blocks #22). Was ⚠ REOPENED 2026-08-21 (see Reassessment): the model cannot be populated. There is no CRUD, service or seed for `visit_types` or `session_templates`, and `VisitTypeRepository` has zero callers — so no row can enter either parent table, `POST /slots/generate` can only ever return `slotsCreated=0`, and `GET /slots/search` only ever returns empty. All five `/slots` endpoints have no portal caller and no nav entry. `appointment_slots.appointment_id`, `SlotStatus.BOOKED`, `session_templates.capacity_per_slot` (unimplementable under `uq_slot_staff_start`) and `visit_types.patient_bookable` all ship dead. Previously recorded as: ✅ FOUNDATION DONE (PR #459 `feature/slot-inventory-foundation`, V121): visit types → session templates → generated slots, idempotent generation, open-slot search, hold/release/block, expired-hold reclaim. Real FKs (new tables). Deliberately deferred to follow-ups: booking an Appointment from a slot (the two models need reconciling on which owns the time), patient self-scheduling, waitlist auto-offer (#22), utilisation reporting. — visit types → session templates → searchable open slots. Unlocks real self-scheduling, waitlist auto-offer, and utilization reporting in one model. (Biggest single build in this list — consider a foundation-pass PR series.)
- [x] 12. **Referral → appointment linkage** — ✅ DONE (PR #453, V117): scheduling a referral now creates and links a real Appointment. Null when the referral targets an external facility with no receiving provider or department — Appointment requires staff, department AND assignment. — referral completion stores a timestamp + free-text location but never creates the Appointment row; create + link it.
- [x] 13. **Orphan-read writers** — ✅ DONE (PR #456): `/on-call` CRUD (overlap-refusing, HOSPITAL_ADMIN writes) and `/advance-directives` CRUD (revoke, never delete). No migration — both tables existed; only the writers were missing. Portal surfaces landed 2026-08-22 (PR #465): `/on-call` page (route + nav roles mirror backend READ_ROLES exactly; writes gated in-component to WRITE_ROLES; overlap refusal surfaced verbatim) and an Advance Directives tab on patient detail (revoke ceremony, never delete; only ACTIVE/PENDING editable). Also fixed the storyboard banner, which rendered REVOKED/EXPIRED directives as an active code status — it now filters to active ones and marks PENDING. — on-call schedule (read by `GET /me/on-call-status`, written by nothing) and advance directives (read by storyboard/record-sharing, no controller): add minimal CRUD for each.
- [x] 14. **Drug-interaction KB expansion** — ✅ DONE (PR #458, V120): 12 → 29 pairs covering the warfarin, rifampicin-induction, QT, statin-myopathy, electrolyte and serotonergic sets, each citing BNF 86 / WHO Model Formulary 2024 / NICE. ⚠ THE SEED NEEDS A PHARMACIST'S SIGN-OFF. The durable half is the new `/drug-interactions` admin API so pharmacy can curate without a migration. Admin UI landed 2026-08-22 (PR #466): `/pharmacy/drug-interactions` page (RxCUI-validated create, deactivate with a confirm that owns the blast radius, reactivate on retired rows, platform-scope banner) plus backend fixes — `PUT /{id}/reactivate`, a duplicate guard that now also catches retired pairs ("reactivate it instead of re-creating it"), the severity+includeInactive list no longer returning only-inactive rows, and notes/monitoring-interval actually persisted. Closes the one-tenant-can-permanently-silence-a-MAJOR-interaction finding; platform-vs-tenant scoping itself is still an open decision. — checking pipeline is real at prescribe/dispense/CDS-Hooks layers but the local KB is a 12-pair seed; curate a WHO-essential-medicines-scale interaction set.
- [x] 15. **Controlled-substance enforcement** — ✅ CLOSED 2026-08-22 (PR #463 `fix/reassessment-p2-security`): `controlledSubstance`/`requiresCosign` got writers (request DTO fields, set-only mapping — a flag once true cannot be edited off; cancel and rewrite instead); `POST /prescriptions/{id}/cosign` second-prescriber ceremony added (DOCTOR/NURSE_PRACTITIONER, cosigner must differ from prescriber); the duplicated rule folded into one `ControlledSubstanceGuard` component used by both prescribe and dispense paths, with the gate test pinning the component directly. Two-factor transport still an open decision — ships fail-closed; the portal deliberately offers only `requiresCosign` so no UI path can brick a prescription. Was reopened 2026-08-21 — the gate can never fire: nothing in `src/main` ever calls `setControlledSubstance` or `setRequiresCosign`. `PrescriptionRequestDTO` has no such field and `PrescriptionMapper` never maps one, so no API path can flag a prescription as controlled; the gate's condition is unreachable by construction. There is also no writer for `twoFactorVerifiedAt`/`cosignedAt`/`cosignedBy` and no cosign or two-factor endpoint in any controller, so a row that *is* flagged (legacy or hand-edited) can never be cleared and is permanently unsignable. The gates themselves are correct — they simply guard a flag nothing can set. Previously recorded as: ✅ DONE (PR #454): gates at prescribe (status-keyed) and dispense (irreversible step). Both needed — RefillApprovalServiceImpl writes SIGNED directly, bypassing the prescribe path. — flags, two-factor and co-sign columns exist; nothing enforces them. Add the prescribe/dispense gates.
- [x] 16. **Server-side prescription signing ceremony** — ✅ CLOSED 2026-08-22 (PR #463): client-assertable statuses whitelisted to `DRAFT / PENDING_SIGNATURE / PENDING_CLARIFICATION / CANCELLED / DISCONTINUED` — `TRANSMITTED` (dispensable, and reachable only through the hole) and every other workflow status now refuse with a message naming the owning endpoint; signature evidence (signedAt / cosign state / two-factor state / pre-V118 marker) rendered in the portal detail panel; `TRANSMITTED` removed from the edit dropdown. Was reopened 2026-08-21: three separate comments claim this is "the only path" to a dispensable state, and it is not. `rejectClientAssertedSignature` blocks only `SIGNED`, while a client-asserted `TRANSMITTED` is still copied onto the entity by `PrescriptionMapper.java:96-98` — and `TRANSMITTED` is dispensable. So the ceremony is bypassable for the exact purpose it exists to serve. A prescription created as `TRANSMITTED` and later refill-approved also reaches `SIGNED` via `RefillApprovalServiceImpl.java:199` with `signature_value` NULL, making it indistinguishable from the documented "signed before V118, unverifiable" case. Signature evidence is dead on the wire (the mapper emits it; no portal file reads it). Previously recorded as: ✅ DONE (PR #455, V118): `POST /prescriptions/{id}/sign` is the only path to SIGNED; records signer, instant and a SHA-256 digest. Create/update now REFUSE a client-asserted SIGNED. Pre-V118 rows deliberately NOT backfilled — `signature_value IS NULL` on a SIGNED row means "signed before V118, unverifiable". — "signed" is currently a client-supplied status; require an authenticated server-side sign action (reuse the hash-based e-signature layer).
- [x] 17. **HL7 outbound transport** — ✅ DONE (PR #457, V119): MllpOutboundSender + dispatch sweep, mirroring the inbound listener and reusing MllpFrameCodec. MSA-1 parsed (AA/CA only); negative ACK terminal, transport failure retried to a ceiling. Off by default. Observability landed 2026-08-22 (PR #464): `GET /lab-instrument-outbox` scoped page + per-status counts, `GET /{id}` full payload, retry-from-ERROR (resets attempts, keeps lastError), transport-config endpoint, and the `/lab-outbox` portal page — first reader of `last_error`/`attempts`; `GET /orders/{id}` no longer filters to PENDING (it silently absorbed ERROR rows), and the read-role list now matches the security filter (LAB_DIRECTOR/QUALITY_MANAGER were 403'd by the method guard the filter admitted them past). — OML/ORU messages are built and queued in the instrument outbox but never transmitted; add the MLLP sender (mirror of the inbound listener).

### P2 execution — 2026-08-21

All seven P2 items landed as PRs #453–#459. Notes that outlive the tickets:

- **#11 is a foundation pass, not the whole feature** — the tasklist anticipated
  a series. What exists is the model and its inventory operations; what does not
  is booking an Appointment from a slot, which needs a decision on whether the
  slot or the appointment owns the time. Everything in #22 waits on that.
- **#14's seed needs a pharmacist's sign-off** before anyone relies on it. The
  rows are transcribed from standard references and each names its source, but
  clinical content belongs to a pharmacist rather than to whoever wrote the
  migration. The admin API is the part that makes the KB maintainable.
- **#15 and #16 each carry a copy of the controlled-substance rule.** Neither
  path covers the other — a prescription signed through #16's endpoint never
  passes #15's create/update gate — so both are needed until they merge and can
  be folded into one helper.
- **New tables carry real foreign keys.** V117, V118, V119 and V121 all add
  constraints, which the tables V1 generated cannot (V1 came from Hibernate
  SchemaExport, which emits none). Every new migration is verified against a
  real postgres:16-alpine in `LiquibaseSchemaIT` — the H2 suite builds tables
  FROM the entities, so it can never catch a column a migration forgot, and prod
  runs ddl-auto=validate against the Liquibase-built schema.

### Deploy incident — 2026-08-21

Merging the P2 tier broke `develop` and took the dev deployment down. Two
distinct faults, both of which reached develop through PRs whose CI was red.

**1. Three migrations shipped unregistered.** V116, V117 and V118 were written,
reviewed and merged (#451, #453, #455) and none was ever added to
`changelog.xml`. A `.sql` file under `db/migration` is inert until the changelog
lists it, so Liquibase ran 114 changesets, logged *"Database is up to date, no
changesets to execute"*, and the entities then declared columns the database had
never been told to create. `ddl-auto=validate` refused to build the
SessionFactory:

```text
Schema-validation: missing column [critical_escalation_level] in table [lab.lab_results]
```

which fails `FhirConfig` → the application context → Tomcat. A total outage from
three files that were present in the diff, in the directory listing and in the
review. #457 carried the identical bug with V119.

**2. `develop` did not compile.** PR #455 shipped `signPrescription` on the
interface, the controller, the entity columns, the DTO, the mapper and six tests
— but never `PrescriptionServiceImpl.signPrescription`. Every branch cut from
develop failed the same way until PR #460 restored it, written against the tests
that #455 had shipped, which were the real specification.

**The structural cause, which is not fixed.** Every migration's `<changeSet>` is
appended immediately before the closing tag of one ~1800-line `changelog.xml`, so
any two branches that add a migration edit the same lines. Resolving that overlap
by taking develop drops the branch's own changeset while leaving its `.sql` on
disk — the file is plainly there and the diff looks untouched, so nothing reads as
wrong. GitHub's **"Update branch" button resolves it exactly that way**: V120 was
lost once and V121 twice, the second time minutes after being restored.

> **Avoid "Update branch" on any branch carrying a migration.** Merge develop
> locally and confirm the registered set afterwards.

**The guard.** `hospital-core/src/test/java/com/example/hms/db/MigrationRegistrationTest.java`
fails on any migration that is unregistered, referenced-but-absent, empty, or
misnamed. Nothing else can catch this: the H2 test profile builds its schema FROM
the entities with `create-drop`, so the columns always exist there regardless of
what the changelog says. It caught V120 on #458 and V121 on #459 — in both cases
converting what would have been a silent production defect into a red build. On
PR #458 the loss would have left the drug-interaction checker running against
V63's 12-pair seed while reporting green — worse than no checker at all.

**Deferred, needs a decision:** the durable fix is to move *new* changesets into
per-file fragments under an `includeAll` directory so two branches never touch the
same lines. It is not done here because Liquibase identifies a changeset by
`(id, author, file path)` — relocating the existing 119 would make it treat them
all as new and **re-run every migration against production**. Applied to new
migrations only it is safe, but it is a change to migration infrastructure and
belongs in its own PR.

**Two findings surfaced by the guard and by verifying the sign endpoint, both
left unfixed on purpose:**

- **`R__prod_role_grants.sql` has never run.** Its header says it is *"a Liquibase
  'repeatable' changeset (R__ prefix)"*. `R__` is **Flyway's** convention;
  Liquibase discovers nothing by filename and spells repeatable as
  `runOnChange="true"` on a changeSet it lists. Nothing references the file, so
  `hms_app` / `hms_readonly` / `hms_migrator` hold only whatever privileges were
  granted by hand. Registering it would make every deploy attempt those `GRANT`s
  and fail where the roles do not exist — an operational call.
- **Only `ROLE_DOCTOR` can sign a prescription.** `ROLE_NURSE_PRACTITIONER`
  appears in four `@PreAuthorize` expressions on `PrescriptionController` and
  behind `RoleValidator.isNursePractitioner`, but that role is never seeded — it
  exists only as a `JobTitle`. `ROLE_MIDWIFE` *is* seeded and midwives prescribe
  throughout the OB module, but is not on the sign annotation. Doctors-only may be
  correct; it should be a decision rather than an accident of an unseeded name.

**Portal side of #16.** The prescription edit form offered `SIGNED` in its status
`<select>` — which is how "signed" came to mean a clinician picked a word from a
dropdown. With the backend now refusing a client-asserted signature, that option
was a control that always fails; it is removed, and a sign action added, because
the endpoint otherwise had no caller anywhere.

### Reassessment — 2026-08-21 (code evidence, 23 agents)

All seventeen P0/P1/P2 items re-verified against merged `develop`, with every
non-DONE verdict handed to an adversarial challenger told to *refute* the gap.
**No challenge overturned a finding.** Result: **6 clean, 11 partial**, five of
which are reopened above because their central claim does not hold.

| Tier | Clean | Partial |
| --- | --- | --- |
| P0 | #1 #2 #3 #4 | **#5** |
| P1 | #7 | #6 #8 **#9** #10 |
| P2 | #12 | **#11** #13 #14 **#15** **#16** #17 |

Bold = checkbox reopened. The rest shipped and work; they carry gaps worth a
follow-up, not a retraction.

**The pattern is not that the work was not done — it is that the last mile was
not.** Ten of the eleven partials are the same three shapes this list has been
tracking all along: a surface with no caller, a column with no reader, and a
comment asserting behaviour the code does not implement. The features are real;
what is missing is the wiring that lets anyone use them.

**Unreachable from the portal** — built, tested, merged, no way in:

- `/on-call` and `/advance-directives` (#13) — no nav entry, no route, no portal
  service. A HOSPITAL_ADMIN cannot create a rota entry and a clinician cannot
  record a DNR from the UI; the tables are still read-only in practice, which is
  the exact condition #13 existed to fix.
- `/drug-interactions` admin API (#14) — the "durable half" of that PR, per its
  own body, has no caller anywhere.
- All five `/slots` endpoints (#11), `GET /lab-instrument-outbox/orders/{id}`
  (#17), `GET /empi/identities/by-patient/{id}` and `POST
  /empi/identities/{id}/merge` (#8), and `POST /appointments/reminders/run` (#7,
  which the sweep otherwise passed clean).

**Dead columns** — written, never read: `critical_readback_value` (#5),
`last_error` (#17, the column V119's own header justifies as the fix for
"status = ERROR with no reason is a dead end at 3am"),
`NurseHandoff.completedAt`/`completedByName` (#1),
`labor_partograph_entries.original_entry_time` (#6), plus the four dead fields
listed under #11.

**#6's linkage is still not wired.** PR #450 was supposed to close
`NewbornAssessment.deliveryRecordId`; the column and mapping exist, but
`postpartum-tab.ts:257-260` never sets it, no UI carries a delivery id from the
Labor tab, and no template renders the field. It remains what the previous
verification pass called it: persistence-only.

#### Cross-cutting findings the per-item sweep could not see

- **Two security issues.** `AdvanceDirectiveServiceImpl.java:50-51` resolves the
  patient with a bare `findById` and no hospital scoping, while the same file's
  `resolveHospital` (`:102-103`) explicitly warns a client-supplied id "would
  otherwise be a cross-tenant write vector" — a clinician at hospital A can
  record a DNR against hospital B's patient. And `LookupController.java:25` is
  `isAuthenticated()` with no `SecurityConfig` rule; it returns `patientName`,
  `patientEmail`, `patientPhone` and appointment `reason` for any supplied
  email/phone/MRN. Pre-existing, but newly reachable now that patients hold
  tokens.
- **The drug-interaction KB is platform-global and tenant-writable.**
  `DrugInteraction` has no `hospital_id` and the admin service has no tenant
  scoping, yet writes are open to `ROLE_PHARMACIST`. With no reactivate path
  (`deactivate()` exists; every read hard-filters `active = true`), one tenant's
  pharmacist can permanently silence a MAJOR interaction for every hospital in
  the deployment.
- **No new P0–P2 write surface emits an audit event** — signing, read-back,
  directives, on-call, slot holds, KB edits. EMPI merge is the sole exception,
  and this list called that convention out at the time.
- **Three check-then-act races.** Slot hold reads `isOfferable` then saves with
  no `@Version` and no lock, so two receptionists both win. The reminder sweep
  and its manual trigger have the same shape, under a javadoc asserting "each
  appointment is stamped exactly once". There is no ShedLock anywhere.
- **`LiquibaseSchemaIT` does not do what I wrote on it.** Its comment claims it
  catches columns a migration forgot "because prod runs ddl-auto=validate while
  H2 builds tables FROM the entities". It never boots Hibernate — it applies the
  changelog and hand-checks five named objects. Every test profile is
  `create-drop`; `validate` appears only in dev/local/prod/uat. **Nothing in CI
  compares entities to the migrated schema**, which is precisely the second half
  of the outage recorded above. `MigrationRegistrationTest` proves a file is
  *listed*, never that its contents match the model. Defect class 3, on the file
  written to prevent the recurrence.
- **`docs/roadmap.csv` / `roadmap.md` were last touched 2026-05-17**, three
  months before PRs #431–#460. The entire P0–P2 tier is absent from the file the
  `roadmap-sync-workflow` skill designates as the status source of truth, and
  `roadmap.csv:25` still records the stale "EMPI matcher returns empty" claim.
- **Backend i18n is broadly unkeyed.** 27 of the 53 dotted keys thrown as
  exception messages are missing from `messages.properties`, so
  `GlobalExceptionHandler` returns the raw key to the client. The missing
  `prescription.sign.ceremony.required` is the house norm, not a #16 regression.
  The portal, by contrast, is clean: 6755 keys × 3 locales, zero drift.

### Promotion — 2026-08-21

`develop dfc01f9e` → `uat 313b3e82` → `main 4952824c`, carrying #448–#460.

Verified **before** promoting, not after: V115–V121 all registered in
`changelog.xml`, `MigrationRegistrationTest` green, and `LiquibaseSchemaIT`
applying the full changelog to a real `postgres:16-alpine`.

### Post-reassessment fixes — 2026-08-22

Every reassessment finding with a **REOPENED** checkbox is closed, in two PRs:

- **PR #462 `fix/reassessment-p0-p1`** — all P0/P1 findings: critical read-back
  persists (REQUIRES_NEW) and is *required* to acknowledge or sign-silence a
  critical result; newborn `deliveryRecordId` got its first writer (delivery
  select + single-delivery preselect on the postpartum tab); EMPI nav hoisted out
  of the receptionist-only gate (NURSE/DOCTOR reachability specs); proxy
  appointments no longer throw (`getAppointmentsForVerifiedPatient`); proxy grant
  expiry input; education library staff-only (incl. MIDWIFE + RECEPTIONIST);
  completed handoffs readable via `?status=`; stale `roadmap.csv:25` corrected.
- **PR #463 `fix/reassessment-p2-security`** — #15/#16 closures above, plus the
  two cross-cutting security findings: `AdvanceDirectiveServiceImpl.create` now
  requires the patient registered at the caller's hospital (404 idiom), and
  `/lookup` is staff-only (was `isAuthenticated()` returning patient
  name/email/phone to any token-holder, including patients).

Promoted: `develop 0ef4e9ef` → `uat 7d72a4a5` → `main 4ce54e64`
(MigrationRegistrationTest + LiquibaseSchemaIT verified on develop first).

**SonarCloud is green again (2026-08-22).** `SONAR_TOKEN` was regenerated and
stored as a repo secret; the `Build and analyze` job on develop (run 32545107632)
completed **success** — first green Sonar since May. Expect a backlog of real
findings on the next few analyses rather than a clean pass. ⚠ The token was
pasted in a chat transcript during rotation — revoke it in SonarCloud (My
Account → Security), issue a fresh one, and update the secret.

The second adversarial re-verification of the seven #462 fix claims (13 agents,
verify → refute → coverage-critic) completed 2026-08-22: **five DONE and
challenge-survived** (newborn-delivery link, EMPI nav, proxy appointments, grant
expiry, education gates) and **two PARTIAL** — critical read-back evidence was
persisted but rendered nowhere, with the read-back button shown to lab roles the
endpoint 403s and hidden from the admins it authorizes; and the completed-handoffs
`?status=` filter had zero portal callers. Both closed by **PR #468
`fix/p0-p1-last-mile`** (read-back evidence + mismatch panels, `canReadBack`
role gate, Pending/Completed handoff toggle with completion metadata), plus a
challenger-found stale-response race in the postpartum delivery loader.
With PR #468 merged, **P0/P1 are done end-to-end**.

The P2 remainder landed the same day, one PR each, all gates green incl. Sonar:
**PR #464** outbox observability (#17), **PR #465** on-call + advance-directive
portal surfaces (#13), **PR #466** drug-KB admin UI (#14), **PR #467** slot
population (#11) — evidence recorded on the items above. Promoted to uat/main
2026-08-22 (second sync of the day; MigrationRegistrationTest +
LiquibaseSchemaIT verified green on develop `b1e26153` first).

## P3 — Broader parity, pick by demand

With P0–P2 closed end-to-end (promoted 2026-08-22), this tier is the working
list. All P3 items are now closed: the final batch (22, 24, 25) shipped
2026-08-22 as the stacked PRs #476 → #477 → #478 (V128–V130).

- [x] 18. Growth charts (needs a height column on vitals) + flowsheets/I&O grids — ✅ DONE 2026-08-22, two PRs. **PR #469** (V122): `height_cm` + `head_circumference_cm` on the vitals bundle, wired through all four write paths incl. the triage form's `heightCm` (accepted and silently DISCARDED since it shipped — first reader); FHIR LOINC 8302-2/9843-4; weight floor 1.0→0.2 kg (NICU); `GET /patients/{id}/growth-chart` (ageDays server-side; birth-weight seed from single-infant deliveries only, hospital-scoped) + pediatric-only Growth tab (inline-SVG trajectory, months/years axis). ⚠ WHO percentile curves DELIBERATELY deferred: LMS reference tables must be imported from a verified source with clinical sign-off (the V120 seed precedent) — never fabricated. Also fixed en passant: the patient-detail vitals tab rendered NO metric in production (portal interface invented field names the wire never carried; specs mocked the wrong shape and stayed green) — `VitalSignService` now mirrors the response DTO field-for-field. **PR #470** (V123, was stacked on #469): `clinical.intake_output_entries`, the first NUMERIC I&O surface (the old INTAKE_OUTPUT was a free-text task category with no volumes); route enum carries its category so intake/output mismatch is unrepresentable; `POST`/`GET /patients/{id}/intake-output` with server-computed window totals + balance (default 24 h); I&O patient-detail tab, modal-per-timepoint (labor/postpartum pattern). Deferred: per-shift totals (no shift-boundary concept exists anywhere), consuming the still-dead `RECORD_INTAKE_OUTPUT` catalog permission (role-gates kept for consistency — permission-model decision flagged, not made), entry amendment/retraction.
- [x] 19. Microbiology (cultures, susceptibilities) — ✅ DONE 2026-08-22 (**PR #472**, V124: `lab.micro_culture_results` / `micro_isolates` / `micro_susceptibilities`, real FKs into lab_orders/lab_specimens). Culture report on a lab order with PRELIMINARY→FINAL→CORRECTED lifecycle (ImagingReportStatus naming): finalize requires a growth result, GROWTH requires ≥1 isolate, post-FINAL mutations demand a correction reason and stamp CORRECTED permanently. S/I/R susceptibility rows per isolate (duplicate antibiotic per isolate unrepresentable). Finalized GROWTH notifies the ordering provider best-effort — non-numeric "Positive" values are invisible to the numeric critical chain. New `/micro-cultures` root deliberately NOT under `/lab-orders/**` (that POST matcher 403s all lab roles first-match — live defect on transition/specimen endpoints, documented not fixed); 404-not-403 on reads AND writes (the lab-results acknowledge/read-back cross-tenant hole was NOT copied). Portal: `/microbiology` workbench (first caller of every endpoint) + patient-detail Micro tab (roles mirror controller; PHARMACIST included for stewardship); MICRO i18n 93 keys ×3. ⚠ Deferred: micro HL7 ingest (ORU parser reads only the FIRST OBX and first OBR, ACKs AA while dropping the rest — prerequisite fix of its own), FHIR DiagnosticReport (none exists anywhere), organism/antibiotic coded vocabularies.
- [x] 20. Note co-sign workflow (student/resident attestation) — ✅ DONE 2026-08-22 (**PR #473**, V125, was stacked on #472; GitHub did not retarget after #472 merged so the merge landed on the feature branch — carried into develop by merge `a56c0bc0`). EncounterNote signature was fully client-asserted (applySignature copied signedAt/signedByName from the request; the form stamped a browser time and promised a lock that didn't exist). Now: `POST /encounters/{id}/notes/sign` (author-only 403, server identity, SHA-256 digest, re-sign refused) + `/notes/cosign` (DOCTOR; requires declared requiresCosign + signed note; self-cosign refused; co-signer staff resolved AT THE NOTE'S HOSPITAL — the prescription-cosign anywhere-lookup gap fixed rather than copied). Signed note LOCKS the upsert (incl. pre-V125 asserted rows); client-asserted signature refused loudly naming the endpoint; requiresCosign set-only; addendum signedAt server-stamped. Pending co-signs feed the Clinical Inbox `DOCUMENT_TO_SIGN` category (existing SIGN routing = zero new queue UI), minus the viewer's own. `signature_value IS NULL` on a signed row = "asserted pre-V125, unverifiable" (V118 stance, no backfill). Portal: form's free-text Signer fieldset → set-only requires-cosign checkbox; sign/cosign buttons + evidence on the encounter panel; chart-review co-sign pill. ⚠ Deferred: STUDENT/RESIDENT roles (none exist — role-model decision, the NP-unseeded precedent), staff-to-staff supervision relation (queue is hospital-wide role-based like DischargeApproval), NursingNote co-sign, AuditEventLog integration (EncounterNoteHistory NOTE_SIGNED/NOTE_COSIGNED rows are the audit substrate).
- [x] 21. Registration extras: patient photo capture, consent-to-treat e-sign at check-in, guarantor accounts — ✅ DONE 2026-08-22 (**PR #474**, V126: photo columns on clinical.patients + `patient_treatment_consents` + `patient_guarantors`, real FKs). Photo binaries live OUTSIDE the upload tree and stream only through authenticated `GET /patients/{id}/photo` — because `GET /uploads/**` is permitAll and serves the whole upload dir statically (⚠ existing profile images + patient documents are downloadable UNAUTHENTICATED with semi-guessable names — flagged, separate security decision, NOT fixed here). LIVE BUG FIXED: pre-check-in `consentAcknowledged` was REQUIRED by the portal form and silently DISCARDED by the backend — now persisted, idempotent per appointment. Consent is a RECORD, not a gate (blocking check-in/walk-ins on missing consent = open clinical decision); revoke-never-delete + SHA-256 digest on ELECTRONIC captures; recorded from check-in dialog (best-effort, never blocks the desk), pre-check-in, and manual endpoints. Guarantors: deactivate-never-delete, one primary per patient+hospital (invoice linkage deferred). Portal: photo component (authenticated blob fetch — `<img src>` carries no bearer token; file upload + webcam frame-grab), check-in consent step, coverage-tab guarantor+consent panels; +58 i18n keys ×3. Guarantor PUT deliberately omits SUPER_ADMIN (the `PUT /patients/**` chain matcher hard-denies it). Sonar round: `PhotoPayload` record→class (S6218) + coverage top-up.
- [x] 22. Recall lists + waitlist auto-offer (depends on #11) — ✅ DONE 2026-08-22 (**PR #476**, V128), built on the same-day decision (user): **the Appointment owns the time**. Slot booking: `POST /slots/{id}/book` creates a normal Appointment (mandatory-assignment refusal per the AppointmentServiceImpl contract, referral-recipe builder) and stamps the slot BOOKED + appointment_id — first writer of the dead column and `SlotStatus.BOOKED`; new `@Version` column turns the double-book race into "That slot was just taken" (saveAndFlush INSIDE the tx so the rollback takes the appointment insert with it); free-on-cancel wired into BOTH cancel paths (confirmOrCancelAppointment CANCELLED/RESCHEDULED + patient-portal cancelMyAppointment); a freed slot whose time already passed re-enters as BLOCKED, never OPEN (searchOpen would hide it but the rota must read honestly). Waitlist offers grew teeth: offer now takes a CONCRETE slot (picker = searchOpen filtered by the entry's dept/provider/date window), HOLDs it for the offer window (default 48 h, capped at slot start — a stale accept must not book the past), stamps offered_slot_id/offer_expires_at and notifies the patient via new `PatientOutreachNotifier` (AppointmentReminderService channel contract: in-app needs portal account, SMS only over `deliversRealSms()` — mock transport logs bodies, never PHI — both honouring APPOINTMENT_REMINDER preferences, 320-char cap); desk accept releases-the-hold-then-books and closes the entry, decline frees + rewaits, and a reconcile sweep returns lapsed offers to WAITING (SlotHoldReclaimScheduler already frees the slot side; nothing flipped the entry). No inbound SMS reply channel exists → v1 is offer + expiry + desk confirm, by design. Recalls: `scheduling.patient_recalls` (+ named FKs, partial idx_recall_due) + `/recalls` endpoints (close/cancel-never-delete; link-appointment→SCHEDULED with same-patient guard; CLASS-level @PreAuthorize — /recalls rides anyRequest().authenticated() so a forgotten method annotation fails OPEN incl. ROLE_PATIENT) + RecallReminderService sweep (V112 exactly-once notifiedAt stamp — stamped even when every channel skipped, so it converges; no clinical reason in the SMS body) + reception-cockpit Recalls tab (overdue flags, manual add, status filters). LIVE BUG FIXED: checkout's FollowUpAppointmentRequest (reason/preferredDate/notes) was REQUIRED by the AVS flow since MVP 6 and silently DISCARDED at EncounterServiceImpl — now feeds a CHECKOUT-source recall (best-effort try/catch, never fails the checkout — the referral-appointment precedent). +40 i18n keys ×3. ⚠ Deferred: patient self-service accept/decline (needs an inbound reply channel), automatic offer matching (v1 is desk-driven; the sweep only reconciles), recall→booking one-click shortcut (book + link-appointment are two calls today).
- [x] 23. Downtime/read-only continuity mode; wristband & label printing — ✅ DONE 2026-08-22 (**PR #475**, V127, was stacked on #474; the base branch was not deleted before merging so GitHub again did not retarget — carried into develop by merge `20ea151c`; NEXT TIME: delete the base branch immediately after merging the bottom PR). Downtime: `platform.platform_downtime_state` singleton (V80 pattern, seeded, fail-open) + `ReadOnlyModeFilter` blocking POST/PUT/PATCH/DELETE with 503 + `X-Readonly-Mode` discriminator; deliberately HTTP-layer, NOT DB-level read-only (login writes lastLoginAt; the audit logger swallows failures — DB-level would break auth and silently drop the compliance trail); allowlist is /api-PREFIXED (servlet filters see the context path — pinned by test) and keeps auth/telemetry/the toggle open. Registered via FilterRegistrationBean in a @Configuration, NOT @Component — a Filter @Component with a service dependency breaks every @WebMvcTest slice (found on the first full-suite run). Portal: persistent non-dismissible shell banner (60s poll — survives login, unlike the STOMP broadcast), error interceptor insta-marks the banner on a readonly 503, offline-dispense interceptor now REFUSES to queue X-Readonly-Mode 503s (bare 503 would silently queue pharmacy dispenses for doomed replay), super-admin Emergency page toggle card. Printing: `WristbandPdfService` (pdfbox+zxing already on classpath — zero new deps); wristband QR = BARE patient UUID (FiveRightsVerificationService does UUID.fromString on the raw scan — pinned by test; MRN human-readable only); specimen label QR = `barcode_value` ("LAB-"+accession) — that column's first reader since V27. Print buttons on patient-detail header + lab specimen table. ⚠ Deferred: downtime packet PDF (census/MAR aggregation), PlatformReleaseWindow.freeze_changes enforcement (still decorative), scan-to-receive lookup endpoints. Sonar round: volatile→AtomicReference (S3077).
- [x] 24. FHIR bulk `$export` completion; FHIR enable-runbook — ✅ DONE 2026-08-22 (**PR #477**, V129, stacked on #476). The foundation pass queued jobs into a ConcurrentHashMap that nothing ever ran: statuses IN_PROGRESS/COMPLETED/FAILED were unreachable, the poll endpoint returned 202 forever without reading job state, and a restart forgot every job. Now: `platform.fhir_bulk_export_jobs` + `fhir_bulk_export_files` (V129, platform-schema infra precedent) + `FhirBulkExportRunner` — a `@Scheduled` sweep (house pattern; deliberately NOT `@Async`, which would require `@EnableAsync` and silently activate the codebase's one dormant `@Async` method) claiming QUEUED jobs via atomic conditional UPDATE (safe without ShedLock), streaming NDJSON write-as-you-page through the same five hospital-scoped queries + FHIR mappers `$everything` uses, one file per resource type, empty types leaving neither files nor manifest rows. Output on LOCAL DISK under `app.fhir.operations.bulk-export.storage-dir` — a SIBLING of the upload tree (V126 photo precedent), never under permitAll `/uploads/**`; no S3 client exists on the classpath, so S3 stays deferred honestly instead of implied. Poll endpoint finally has all spec branches: 202+X-Progress (real N/M patient counts), **200+manifest** (`transactionTime`/`request`/`requiresAccessToken:true`/`output[]`), 500+OperationOutcome on FAILED, 404 for cancelled (row KEPT — deactivate-never-delete — but polls 404 per the spec's post-DELETE contract), plus the authenticated download endpoint (file name resolved from the DB row, never raw client input). Kickoff hardened: requires an active hospital (the null-tenant dead-job hole closed with a 400), rejects unsupported `_type` and `_outputFormat` at kickoff (400 — silent dropping would fake completeness), and **requires SUPER_ADMIN/HOSPITAL_ADMIN** (mass PHI extract; `/fhir/**` has no role gate of its own — checked in the service; the status controller mirrors it class-level, since `/fhir-bulk-status/**` otherwise rode `anyRequest().authenticated()` down to ROLE_PATIENT). Flag-off flipped 405→**501** exactly as the foundation pass documented it would ("ships with the async runner"); ITs updated. Cancel mid-run aborts at the next patient page and discards partial output; failure marks FAILED + message + cleanup, no auto-retry. docs/fhir-bulk.md rewritten with the **enable runbook** (env-var flags + restart, NOT DB flags; storage-dir rules; role requirements); stale "V103 in the next free slot" and fhir.md's "not yet" fixed. ⚠ Deferred: Group-level $export (no Group resource), canonical poll-URL mounting under /api/fhir/*, S3 target, retry/DLQ, output retention sweep.
- [x] 25. Analytics: report builder / scheduled reports; NEWS2/MEWS early-warning scores — ✅ DONE 2026-08-22 (**PR #478**, V130, stacked on #477). **25a Scheduled reports**: no report concept existed. `platform.report_definitions` (per-hospital config, Dhis2FacilityConfig shape) + `report_runs` with **UNIQUE (definition, period_token)** — the run row is inserted-and-flushed as GENERATING BEFORE generation, so the constraint IS the claim and two sweep instances (no ShedLock) can never email one period twice (deliberately NOT the check-then-act reminder-stamp idiom; deliberately no outer tx so the claim commits before the slow work). Two canned types v1 (ENCOUNTER_ACTIVITY, APPOINTMENT_ACTIVITY), **AGGREGATE-ONLY by design** — counts per day, never patient rows, because the delivery channel is an email attachment and email must not carry PHI (the recall-SMS stance applied to the second untrusted channel). commons-csv (on classpath since ever, first use) + EmailService.sendWithAttachment; hourly sweep emits the prior CLOSED period (DAILY yyyy-MM-dd / WEEKLY ISO yyyy-Www / MONTHLY yyyyMM, DHIS token idiom); manual run-now may RETRY a FAILED period (row reuse) but REFUSES a duplicate of a SUCCEEDED one; unparseable token = refusal not guess. `/reports` controller class-level @PreAuthorize(HOSPITAL_ADMIN, SUPER_ADMIN) — no SecurityConfig matcher, would fail OPEN — + `/reports` portal page (first caller of every endpoint: create modal, run history, run-now, stop/resume) + nav. **25b NEWS2**: patient_vital_signs had 5 of 7 parameters — no supplemental-oxygen flag, no consciousness. V130 adds `on_oxygen` + `consciousness_level` (ACVPU incl. NEW_CONFUSION), wired through ALL clinical write paths (vitals mapper, nurse capture, triage DTO+builder — the item-18 heightCm lesson applied preemptively) and portal capture (triage form + nurse-station dialog: ACVPU select + O₂ checkbox). `NewsScoreCalculator` (RCP 2017 tables, boundary-pinned tests): **partial scores are explicit, never silent** — missing parameters are NAMED and flagged incomplete ("the true score can only be equal or higher"), because silent partial under-scores exactly the deteriorating patients the score exists to catch, while refusing to score would hide what IS known. Score computed on READ (mapper → response DTO), never stored — no staleness. Write paths auto-flag `clinicallySignificant` at NEWS2 ≥5 (the aggregate catches multi-parameter deterioration the legacy per-vital thresholds miss). `NewsScoreProtocolRule` BPA card (V130-seeded NEWS2_EWS protocol row — a missing row would silently no-op the rule) fires at MEDIUM/any-single-3, WARNING only (BPA contract forbids CRITICAL), **excludes PATIENT_REPORTED home readings**; patient-detail vitals cards get a band-coloured NEWS2 chip with an explicit Incomplete marker. ⚠ Deferred: SpO₂ scale 2 (needs a per-patient hypercapnic care-plan flag that doesn't exist), tiered escalation of NEWS≥7 through the CriticalValueNotificationService chain (its stamps/rounds live on lab_results — a vitals-side escalation ledger is its own migration; v1 surfaces via significant-flag worklists + BPA card), custom report query builder (v1 is canned types), report output download endpoint (email is the delivery channel; no CSV is stored).

*Source audit + full evidence: artifact above. Related work already landed: PR #429 (cross-hospital
link-at-registration), PR #430 (phone-first registration + IKODDI SMS OTP).*
