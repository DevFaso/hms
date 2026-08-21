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

- [ ] 1. **Nurse handoffs: real entity or removal** — `NurseTaskServiceImpl` fabricates handoff rows ("still synthetic, entity arrives in MVP 2"); completion buttons operate on generated data. Build the handoff entity (SBAR/I-PASS fields) + endpoints + wire the existing UI, or pull the surface until it's real.
- [ ] 2. **Nurse order-task queue: real entity or removal** — same file, "Orders — still synthetic (MVP 3)"; dashboard counts derive from fabricated lists. Same treatment as #1 (can share a PR if the entity work overlaps).
- [ ] 3. **Lab pending-review: delete the hardcoded endpoint** — `LabResultServiceImpl.getPendingReviewResults` returns invented patients ("Ava Johnson", "Michael Chen"); the real queue is `/me/results/review-queue`. Remove or redirect the fake path and any consumer.
- [ ] 4. **Bed/ward decision** — schema (V25), repositories, and an occupancy dashboard exist with **zero writers** (`bed_id` never populated; admissions use free-text `roomBed`). Either build bed CRUD + assignment workflow (unlocks census/bed board, in-app transfers) or drop the tables + occupancy tiles. Decide before any inpatient-logistics work.
- [ ] 5. **Critical-value escalation chain** — results are flagged + acknowledgeable but nothing notifies the ordering provider; add the notify → read-back → timer/escalation loop (IKODDI SMS + in-app; PR #430 gives the transport).

## P1 — Complete the specialty core + turn on what's already built

- [x] 6. **Labor & delivery: partograph + delivery record** — ✅ DONE (`feature/labor-delivery-partograph`): `clinical.labor_episodes` + `labor_partograph_entries` + `delivery_records` (V111), WHO alert/action-line evaluation with URGENT notifications, PPH/APGAR/stillbirth alerts, Labor & Delivery tab with SVG partograph chart, `NewbornAssessment.delivery_record_id` back-link (the audit's deferred pregnancy FK), episode outcome = the audit's missing `Pregnancy.outcome`.
- [x] 7. **Appointment reminders over SMS** — ✅ DONE (`feature/appointment-sms-reminders`): 15-min sweep reminds patients of appointments starting within 24 h — in-app push + IKODDI SMS (`deliversRealSms()` guard), `reminder_sent_at` exactly-once stamp (V112), first dispatch path to honour the stored `APPOINTMENT_REMINDER` notification preferences, FR-default localized message, manual trigger `POST /appointments/reminders/run`.
- [x] 8. **EMPI: finish probabilistic matching + merge** — ✅ DONE (`feature/empi-confirm-merge`): `/empi` admin controller (merge-by-patient w/ identity provisioning, identity merge, by-patient lookup); merge now reassigns aliases (fixes post-merge MRN lookups resolving to the dead identity), emits `PATIENT_MERGE` audit, rejects cross-tenant merges; matcher candidates scoped to the caller's hospital (tenant leak fixed); confirm-match navigates to the existing patient + `(confirm)` output; admin two-click merge mode in the panel; three stale "matcher returns empty" docs corrected. Out of scope (stated): clinical deep-merge, undo, HL7 A40 inbound.
- [x] 9. **Web parity: cancel/reschedule + proxy views** — ✅ DONE (`feature/web-appointment-proxy-parity`): patient-portal cancel + reschedule modals wired to the previously dead `PUT /me/patient/appointments/{cancel,reschedule}` service methods (all four reschedule fields sent — Android omits `newEndTime` and silently 400s); cancelled appointments now stay visible in Past instead of vanishing; new proxy data viewer (`/my-family-access/:patientId`) consuming all five `proxy-access/{patientId}/…` endpoints with permission-driven tabs. Two bugs fixed: the web grant form emitted a scope vocabulary (`APPOINTMENTS`) the backend never matches (`VIEW_APPOINTMENTS`) so every non-`ALL` web grant 403'd — form corrected + legacy tokens normalized server-side; and `expiresAt`/`EXPIRED` were stored but never enforced, so expired grants still read PHI.
- [x] 10. **Patient-facing education delivery** — ✅ DONE (`feature/patient-education-delivery`): new `/me/patient/education*` self-service API (list assigned + read one + record progress/rating/understanding + ask & list questions), all IDOR-safe via `resolvePatientId`; a `PatientEducationProgress` row IS the assignment record, so no entity or migration was needed. New `/my-education` portal page: to-read / completed / questions tabs, warning-sign safety banner, reader with mark-read → confirm-understanding → rate, and patient-authored questions (the entity, service method and Swagger description existed but no patient could reach them). Security fix: `GET /patient-education/progress/{id}` and `/questions/{id}` were `isAuthenticated()` with no ownership check — an IDOR the moment patients hold tokens; now staff-only. Dead columns `completionCount`/`ratingCount` (always 0) are now written on patient completion/rating.

### Field-reported fixes (not numbered — found in production, outside the audit's scope)

- [x] **Refill approval queue was unreachable** — ✅ DONE (`fix/refill-approval-reachability`): the whole chain existed and worked — patient submits, `notifyCareTeamForRefillRequest` emails and in-app-notifies the prescriber, `GET /refills` returns their queue, approve/reject enforce prescriber + status — but no click anywhere in the portal reached `/refills`. No sidebar entry, both dashboard "Refills" tiles pointed at `/prescriptions`, and the one `router.navigate(['/refills'])` fired on an inbox category `ResultReviewServiceImpl` never emitted (its `RefillRequestRepository` was injected and unused). Added the sidebar entry, corrected both tile routes, added a doctor tile with the pending count, and wrote the missing `REFILL_REQUEST` inbox emitter. Also added the missing **pause/hold** state (`RefillStatus.PAUSED`, `PUT /refills/{id}/pause`, reason mandatory since the patient is told): a held request stays actionable, the patient can still cancel it, and it drops out of the inbox so the queue clears. The `pharmacy-refill.spec.ts` E2E masked all of this — it deep-links to `/refills`, and asserted a `PENDING` filter that neither the component nor the enum has.
  - **Still open, deliberately:** approval is terminal — it writes `APPROVED` and creates no downstream prescription or pharmacy order — and `DISPENSED` is declared but nothing ever writes it. That's a workflow decision, not a wiring bug.

## P2 — Structural gaps with high leverage

- [ ] 11. **Slot inventory for scheduling** — visit types → session templates → searchable open slots. Unlocks real self-scheduling, waitlist auto-offer, and utilization reporting in one model. (Biggest single build in this list — consider a foundation-pass PR series.)
- [ ] 12. **Referral → appointment linkage** — referral completion stores a timestamp + free-text location but never creates the Appointment row; create + link it.
- [ ] 13. **Orphan-read writers** — on-call schedule (read by `GET /me/on-call-status`, written by nothing) and advance directives (read by storyboard/record-sharing, no controller): add minimal CRUD for each.
- [ ] 14. **Drug-interaction KB expansion** — checking pipeline is real at prescribe/dispense/CDS-Hooks layers but the local KB is a 12-pair seed; curate a WHO-essential-medicines-scale interaction set.
- [ ] 15. **Controlled-substance enforcement** — flags, two-factor and co-sign columns exist; nothing enforces them. Add the prescribe/dispense gates.
- [ ] 16. **Server-side prescription signing ceremony** — "signed" is currently a client-supplied status; require an authenticated server-side sign action (reuse the hash-based e-signature layer).
- [ ] 17. **HL7 outbound transport** — OML/ORU messages are built and queued in the instrument outbox but never transmitted; add the MLLP sender (mirror of the inbound listener).

## P3 — Broader parity, pick by demand

- [ ] 18. Growth charts (needs a height column on vitals) + flowsheets/I&O grids
- [ ] 19. Microbiology (cultures, susceptibilities) — biggest lab-domain absence
- [ ] 20. Note co-sign workflow (student/resident attestation)
- [ ] 21. Registration extras: patient photo capture, consent-to-treat e-sign at check-in, guarantor accounts
- [ ] 22. Recall lists + waitlist auto-offer (depends on #11)
- [ ] 23. Downtime/read-only continuity mode; wristband & label printing (scanning exists, printing doesn't)
- [ ] 24. FHIR bulk `$export` completion (foundation pass never finishes a job); FHIR write/$everything enable-runbook
- [ ] 25. Analytics: report builder / scheduled reports; NEWS2/MEWS early-warning scores

*Source audit + full evidence: artifact above. Related work already landed: PR #429 (cross-hospital
link-at-registration), PR #430 (phone-first registration + IKODDI SMS OTP).*
