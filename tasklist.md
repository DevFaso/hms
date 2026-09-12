# Web Portal Achievement Assessment & Gap Tasklist â€” 2026-08-18

> Full cross-reference of the Angular portal (`hospital-portal`) against the backend API surface
> (`hospital-core`: 97 controllers, ~730 endpoints). Assessment: the portal is mature for core
> operations (auth/RBAC shell, dashboards, patients, appointments, encounters, admissions, lab
> suite, reception, nurse station, chat/notifications via STOMP, patient portal, i18n EN/FR/ES,
> Playwright e2e) â€” but roughly a third of the backend's user-facing domains have **no web UI at
> all**, several shipped workflows are missing their closing half, and test/i18n/a11y quality
> lags behind the feature surface.

## Scorecard

| Area | Status |
| --- | --- |
| Core ops (patients, appointments, encounters, admissions, reception, nurse station) | âœ… Built |
| Lab suite (orders, results, approval queue, QC, ops, config, instruments, inventory) | âœ… Built (depth gaps: specimens, sign/ack, critical results, reflex rules) |
| Patient portal (14 `my-*` pages) | âœ… Built |
| Admin (users, roles, orgs, hospitals, platform, audit, feature flags) | ðŸŸ¡ Partial (governance suite missing) |
| Discharge approvals + summaries | âŒ No UI |
| Imaging **results** (orders only) | âŒ No UI |
| Maternity suite (~70 endpoints: maternal history, ultrasound, birth plans, prenatal, postpartum, newborn, OB/GYN referrals, high-risk plans) | âŒ No UI |
| Medical history (social/family/immunizations, 24 EP) | âŒ No UI |
| Patient education (32 EP), procedure orders, medication history/pharmacy fills, insurance mgmt, registrations | âŒ No UI |
| Unit tests | ðŸŸ¡ 48 specs / 131 files (~37%); billing, imaging, encounters, admissions, consultations, scheduling, auth all untested |
| i18n | ðŸŸ¡ ES missing 342 keys, FR missing 13; hardcoded EN strings in login/billing/forms |
| Accessibility | ðŸŸ¡ aria-labels present; no skip-link, only 2 `aria-live`, no reduced-motion |

---

## Gaps in Priority Order

### P0 â€” Integrity risks in what's already shipped

1. **Hardcoded client-side permission map** â€” `hospital-portal/src/app/core/permission.service.ts` duplicates roleâ†’permission mapping in TS; drift already gutted LAB_MANAGER's sidebar once (see lab-role section below). Backend exposes `/roles/{id}/permissions`.
2. **Unreachable pages** â€” routes with no sidebar/nav entry: `analytics`, `feature-flags`, `digital-signatures`, `lab-staff`, `lab-instruments`, `lab-inventory`, `my-care-team`, `my-documents`, `my-family-access`, `my-notifications`, `notification-settings`; `force-change-password` component has no route at all.
3. **Mixed API prefixing** â€” 6 newer services hardcode `/api/...` while the rest rely on `apiPrefixInterceptor` (`auth.interceptor.ts:48`); double-prefix fragility.
4. **Silent 403 swallowing** â€” `error.interceptor.ts` suppresses 403s on 11 URL patterns, masking real authorization failures as "working" UI.
5. **Placeholder GA id `G-XXXXXXXXXX`** shipped in `index.html` + `environment.prod.ts`.

### P1 â€” Shipped workflows missing their closing half

6. **Discharge approvals + summaries** â€” `/discharge-approvals` (8 EP) and `/discharge-summaries` (11 EP incl. finalize, pending-results) have zero UI; admission/discharge exists but the approval + summary loop is broken.
7. **Imaging results** â€” portal only places orders; `/imaging/results` (view, status, critical-acknowledge) unconsumed. Ordering without resulting is half a workflow.
8. **Lab depth** â€” specimens (collect/receive), result **sign/acknowledge**, critical results (`hospital/{id}/critical[/unacknowledged]`), sequential result comparison, reflex rules: all backend-ready, no UI.
9. **Patient chart depth** â€” structured allergies/diagnoses CRUD, chart-updates, doctor-record/timeline, `/patients/search` unconsumed; portal treats allergies as a free-text field.
10. **Encounter transitions** â€” `complete-examination`, `ready-for-discharge`, AVS retrieval, note addendums not wired.

### P2 â€” Whole backend domains with no web UI

11. **Maternity suite** (~70 EP) â€” biggest single gap; MIDWIFE role exists in the portal but has no maternity pages.
12. **Medical history** â€” social/family history + immunizations (24 EP, incl. overdue/reminders).
13. **Patient education** (32 EP) + **procedure orders** (9 EP).
14. **Medication history / pharmacy fills** (6 EP) + pharmacy directory â€” PHARMACIST role only gets the prescriptions page.
15. **Insurance management** (6 EP) + multi-hospital **registrations** (6 EP) â€” reception only has issues/attest.
16. **Admin governance** â€” permission-matrix snapshots/audit, org security policies/rules (16 EP), super-admin user governance/credentials/security (15 EP), assignment admin CRUD + bulk-import, reference catalogs, service translations.
17. **Digital signatures actions** â€” UI lists + audit-trail only; sign/verify/revoke flows missing. **Billing depth** â€” invoice email, search, standalone payment recording.

### P3 â€” Quality & maturity

18. **Unit tests** â€” ~37% file coverage; entire domains untested (billing, imaging, consultations, treatment-plans, encounters, admissions, prescriptions, scheduling, auth, admin CRUD pages).
19. **i18n** â€” ES: 342 missing keys (PORTAL.*, RECEPTION.*); FR: 13 (PORTAL.SUMMARIES.*); hardcoded EN in `login.html`, `billing.html:294-361`, form placeholders, checkout dialog.
20. **Accessibility** â€” add skip-link, `aria-live` on toasts/async regions, shared `sr-only` utility, `prefers-reduced-motion`, focus-visible styles.
21. **Realtime consistency** â€” dashboard, nurse-station, patient-tracker poll via `setInterval` while STOMP infra already exists.

---

## Task List

### Phase 1 â€” P0 hardening (small, do first) â€” âœ… DONE 2026-08-18 (`feature/web-p0-hardening`)

- [x] 1. Drive UI permissions from backend: `PermissionService.loadFromBackend()` now fetches `GET /me/dashboard-config` `mergedPermissions` (union with static map as fallback; alias table bridges backendâ†”frontend permission names; nav rebuilds reactively via `effect()`)
- [x] 2. Nav entries added for orphaned routes (analytics, feature-flags, digital-signatures, lab-staff, lab-instruments, lab-inventory, my-care-team, my-documents, my-family-access; patient nav now uses `/my-notifications`). Note: `notification-settings` and `profile` were already reachable in-page â€” not gaps. `force-change-password` was dead code superseded by `account-setup` â†’ deleted
- [x] 3. Stripped hardcoded `/api/` from dashboard, patient-portal, patient-tracker, in-basket, digital-signatures, assignment-public services (+ affected specs). Kept: auth refresh (intentional absolute URL) and SockJS `/api/ws-chat` (not HTTP-intercepted)
- [x] 4. Silenced 403s now reported once-per-URL to `POST /frontend-audit` (`type: SILENT_403`); errors still propagate to callers; `/frontend-audit` itself excluded from the 403 redirect
- [x] 5. GA: static gtag snippet removed from `index.html`; `AnalyticsService` bootstraps gtag at runtime only for a real `G-â€¦` id; prod placeholder replaced with empty (disabled) + key added to dev/uat envs

### Phase 2 â€” Close broken workflows (P1) â€” âœ… DONE 2026-08-18 (`feature/web-p0-hardening`)

- [x] 6. `/discharge` page: approval queue (nurse request w/ auto-resolved registration, doctor approve/reject, cancel) + summary editor (unfinalized & pending-results worklists, med reconciliation / pending tests / follow-ups, finalize w/ signature, delete)
- [x] 7. Imaging Results view: hospital report list (status/modality/critical filters), report detail w/ measurements + status history, status updates, critical acknowledge, view-report from completed orders
- [x] 8. Lab: specimen collect/receive per order; result sign + acknowledge; Critical (unacknowledged) tab; comparison modal (trend, % change, significance); Reflex Rules manager in lab-test-config
- [x] 9. Patient chart tab: structured allergies CRUD (+audited deactivation), doctor-managed problem list, versioned chart-updates feed, audited doctor timeline; `PatientService.search()` added (list page still on `/patients?search=` â€” kept, both are server-side)
- [x] 10. Encounter detail: complete-examination + ready-for-discharge, AVS viewer, note history + addendums (also fixed addAddendum payload contract bug)
- [x] 11. Unit specs shipped with each feature (36 new service tests; 566 total green). E2E: deferred â€” Playwright flows for discharge/imaging-results/lab-depth need seeded backend fixtures (follow-up)

### Phase 3 â€” New modules (P2, by clinical value)

- [x] 12. Maternity module v1: maternal history (+ high-risk board, risk calc, mark-reviewed) and OB/GYN referrals with messaging â€” âœ… DONE (`/maternity` page: worklist board with risk badges + calculate-risk/mark-reviewed, versioned history form, referral lifecycle + messaging; referral attachments and high-risk care plans deferred)
- [x] 13. Maternity module v2: ultrasound orders/reports/templates, birth plans, prenatal scheduling, postpartum + newborn assessments â€” âœ… DONE (four new `/maternity` tabs: ultrasound worklists + report entry w/ NT & anatomy templates + review/notify, birth-plan CRUD + provider review, prenatal cadence generation + reschedule/reminders, postpartum observations + newborn assessments w/ backend alerts)
- [x] 14. Medical History tab on patient detail: social, family, immunizations (with overdue/upcoming) â€” âœ… DONE (History tab: versioned social summary+form, family worklists w/ genetic/screening filters, immunizations w/ client-computed overdue + mark-reminder-sent; roles mirror backend reads exactly)
- [x] 15. Pharmacy: medication-history timeline + pharmacy-fill recording (PHARMACIST workspace) â€” âœ… DONE (`/medication-history` page: timeline w/ overlap+interaction detection, polypharmacy warnings, fill record/edit; roles mirror backend exactly)
- [x] 16. Procedure orders page (order â†’ consent-pending â†’ scheduled â†’ cancel) â€” âœ… DONE (`/procedure-orders`: status worklists + pending-consent, create modal, detail w/ schedule/consent/start/complete/postpone/site-mark/cancel; detail renders from list rows since HOSPITAL_ADMIN cannot GET-by-id)
- [x] 17. Insurance management + multi-hospital registration UI (reception + patient detail) â€” âœ… DONE (`/registrations` admin page w/ multi-hospital panel + Coverage tab on patient detail for insurance link/edit/delete and registration history; fixed the latent findActiveRegistration bare-array bug)
- [x] 18. Patient education: resource management + assignment/progress views â€” âœ… DONE (`/patient-education` page: resource library CRUD w/ category/type/search filters, evidence & warning-sign flags, view/completion/rating stats; per-patient assignment (POST progress NOT_STARTED) + progress/comprehension tracking w/ provider notes; Q&A + visit-documentation blocks deferred)
- [x] 19. Admin governance: assignment admin (CRUD, bulk-import, regenerate-code), permission-matrix snapshots/audit, org security policies/rules, super-admin governance (user import, credential health, security baselines) â€” âœ… DONE (`/admin-assignments`: paged worklist + filters, single/multi-scope create, edit, regenerate-code w/ verification-reset warning, resend, deactivate/delete, CSV bulk import; `/admin-governance` console (SUPER_ADMIN): permission-matrix snapshots w/ prefill+publish+audit trail, security policies/rules CRUD, user CSV import + force-password-reset + rotation health, credential health (read-only, MFA/recovery upserts deferred), baselines + export, rule-set templates/import/simulation)
- [ ] 20. Digital signatures: sign/verify/revoke flows; billing: invoice email + search

### Phase 4 â€” Quality floor (P3, parallelizable)

- [x] 21. Raise spec coverage: prioritize billing, encounters, admissions, imaging, consultations, scheduling, auth interceptors; enforce via `coverage-thresholds.json` ratchet â€” DONE (`feature/coverage-ratchet`): 120 new specs took billing/scheduling/imaging/consultations/encounters from 0% and interceptors from 30%â†’95%; `scripts/check-coverage.mjs` enforces global + per-module floors from `coverage-thresholds.json`; CI runs `test:coverage` + `coverage:check`. Follow-up: encounter-note-form + eligibility-check-dialog sub-components still untested (encounters floor pinned at 47%)
- [x] 22. i18n: backfill 342 ES + 13 FR keys; sweep hardcoded EN strings; add key-parity CI check â€” DONE (`feature/i18n-backfill`): FR was already 100%; ES backfilled 515 keys (NAV/PORTAL/RECEPTION/PHARMACY) to 100%, removed 1 orphan ES key; swept hardcoded EN out of `login.html`/`login.ts`, `billing.html`, checkout dialog (46 new keys Ã—3 locales); CI gate switched from 99%/89% thresholds to strict key parity (`npm run i18n:parity`)
- [x] 23. A11y pass: skip-link, `aria-live` regions, global `sr-only`, reduced-motion, focus-visible â€” DONE (`feature/a11y-pass`): skip-link + focus-visible were already shipped (row 11); this pass added live-region toasts (`role="alert"` for errors, `role="status"` otherwise, aria-hidden icons, labelled close button), a global `.sr-only` utility in `styles.scss` (local copies in stock-routing/pre-checkin removed), and a global `prefers-reduced-motion: reduce` kill-switch (0.01ms durations so end-events still fire)
- [x] 24. Migrate dashboard/nurse-station/patient-tracker polling to STOMP topics â€” DONE (`feature/live-flow-updates`): patient-tracker was already STOMP-driven (WS-gated 120s/30s poll); `PatientTrackerWsService` is now ref-counted so tracker + dashboard + nurse-station share one socket; clinician dashboard (previously manual-refresh only) and nurse-station now subscribe to `/topic/patient-tracker/{hospitalId}` and refresh their encounter-driven panels within seconds (debounced). Polling retained as heartbeat for time-driven data (vitals due, MAR) â€” no backend events exist for those domains yet (follow-up: publishers for MAR/vitals/tasks/inbox/orders/handoffs/announcements/admissions)

---
---

# Patient Tracker Board & Discharge â†’ Encounter Auto-Complete

> Two bugs reported: Patient Tracker Board ignores carry-over encounters
> from prior days, and discharging an admission doesn't auto-complete the
> linked encounter.

## User Stories

### Story 1 â€” Carry-over encounters appear on the tracker board
**As a** Doctor/Nurse viewing the Patient Tracker Board,  
**I want** active encounters from prior days (e.g., IN_PROGRESS from yesterday) to appear on today's board,  
**So that** I can see all patients still in the clinic regardless of which day their encounter started.

**Acceptance Criteria:**
- Encounters in a non-terminal status (anything except COMPLETED/CANCELLED) that started before today still appear on the tracker board
- No duplicate entries when an encounter is from today AND active
- Board count and average wait reflect all active encounters (including carry-overs)

### Story 2 â€” Discharge auto-completes the associated encounter
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
| Tracker empty for carry-overs | `PatientTrackerServiceImpl.getTrackerBoard()` queries `findAllByHospitalAndDateRange(today)` â€” encounters from yesterday are not included |
| Encounter stuck after discharge | `AdmissionServiceImpl.dischargePatient()` only sets admission status; no code touches the encounter |

---

## Task List

- [x] 1. Add `findCarryOverEncounters()` query to EncounterRepository â€” âœ… DONE (commit 31239f72)
- [x] 2. Update PatientTrackerServiceImpl to merge carry-over encounters â€” âœ… DONE (ID-deduped merge; counts/avg-wait include carry-overs)
- [x] 3. Inject EncounterRepository into AdmissionServiceImpl â€” âœ… DONE (since refactored to shared `EncounterAutoCompletionService`)
- [x] 4. Auto-complete active encounters in dischargePatient() â€” âœ… DONE (+ follow-up: discharge-approval `approve()` now also completes encounters, and both paths publish tracker WS events)
- [x] 5. Add JUnit tests for carry-over encounters (PatientTrackerServiceImplTest) â€” âœ… DONE (3 cases)
- [x] 6. Add JUnit tests for discharge auto-complete (AdmissionServiceImplTest) â€” âœ… DONE (+ EncounterAutoCompletionServiceTest, DischargeApprovalServiceImplTest coverage)
- [x] 7. Build, format, test, lint, JaCoCo â€” âœ… DONE
- [x] 8. Commit and push â€” âœ… DONE

> Follow-up (2026-08-19, `fix/tracker-discharge-residual-gaps`): today's staff-less encounters were still invisible on the board â€” `findAllByHospitalAndDateRange` used inner `JOIN FETCH e.staff` while the carry-over query used LEFT JOIN, so an unassigned walk-in only appeared the *next* day as a carry-over. Both queries now LEFT JOIN. Discharge via the approval flow now completes encounters too, and tracker boards get WebSocket pushes on discharge instead of waiting for the next poll.

---
---

# Lab Role Permission â€” Gap Analysis & Task List

> Generated from code audit against the Feature Ã— Lab Role matrix.  
> Roles: **LAB_TECHNICIAN**, **LAB_SCIENTIST**, **LAB_MANAGER**, **LAB_DIRECTOR**, **QUALITY_MANAGER**  
> **Status: âœ… ALL GAPS FIXED (controller + service layer + i18n)** â€” branch `fix/lab-role-permission-gaps`

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

## Gap 1 â€” âœ… FIXED â€” `ROLE_LAB_MANAGER` missing from frontend `permission.service.ts`

**Impact:** LAB_MANAGER sees **zero** permission-gated nav items (Patients, Staff, Lab, Departments, Scheduling, etc.) â€” the entire sidebar is gutted.

| Expected (matrix) | Actual (code) |
|---|---|
| âœ… Patients, Lab, Staff, Departments, Scheduling, etc. | âŒ No entry in `ROLE_PERMISSIONS` map |

**File:** `hospital-portal/src/app/core/permission.service.ts`  
**Fix:** Add `ROLE_LAB_MANAGER` entry with: `View Dashboard`, `View Lab`, `Process Lab Tests`, `View Patient Records`, `View Staff`, `View Staff Schedules`, `View Departments`, `View Notifications`

---

## Gap 2 â€” âœ… FIXED â€” Lab Approval Queue nav hidden from `LAB_SCIENTIST` and `LAB_MANAGER`

**Impact:** LAB_SCIENTIST and LAB_MANAGER cannot reach the Lab Approval Queue from the sidebar, even though the route guard and backend allow them.

| Expected (matrix) | Actual (shell.ts) |
|---|---|
| LAB_SCIENTIST âœ…, LAB_MANAGER âœ… | âŒ Nav only shown for `ROLE_LAB_DIRECTOR` and `ROLE_QUALITY_MANAGER` |

**File:** `hospital-portal/src/app/shell/shell.ts` (lines ~349-370)  
**Fix:** Expand the `hasAnyRole` guard for the Lab Approval Queue + QC Dashboard nav block to include `ROLE_LAB_SCIENTIST` and `ROLE_LAB_MANAGER`.

**Note:** The route guard in `app.routes.ts` already allows `ROLE_LAB_SCIENTIST` and `ROLE_LAB_MANAGER` for `lab-approval-queue`, so this is nav-only.

---

## Gap 3 â€” âœ… FIXED â€” QC Dashboard nav hidden from `LAB_MANAGER`

**Impact:** LAB_MANAGER cannot see the QC Dashboard nav link despite the route guard in `app.routes.ts` allowing `ROLE_LAB_MANAGER`.

| Expected (matrix) | Actual (shell.ts) |
|---|---|
| LAB_MANAGER âœ… | âŒ Nav only shown for `ROLE_LAB_DIRECTOR` and `ROLE_QUALITY_MANAGER` |

**File:** `hospital-portal/src/app/shell/shell.ts` (same block as Gap 2)  
**Fix:** Already addressed by Gap 2 fix â€” expanding the `hasAnyRole` guard.

---

## Gap 4 â€” âœ… FIXED â€” Ops Dashboard nav missing from shell.ts entirely

**Impact:** No role can see the Ops Dashboard in the sidebar nav. The route exists in `app.routes.ts` and the component exists (`lab-ops-dashboard/`), but there is no nav item for it.

| Expected (matrix) | Actual (shell.ts) |
|---|---|
| LAB_MANAGER âœ…, LAB_DIRECTOR âœ…, QUALITY_MANAGER âœ… | âŒ No nav item exists |

**File:** `hospital-portal/src/app/shell/shell.ts`  
**Fix:** Add an Ops Dashboard nav item gated by `hasAnyRole(['ROLE_LAB_DIRECTOR', 'ROLE_LAB_MANAGER', 'ROLE_QUALITY_MANAGER'])`.

---

## Gap 5 â€” âœ… FIXED â€” `LabTestDefinitionController` MANAGE_ROLES missing `LAB_DIRECTOR`

**Impact:** LAB_DIRECTOR cannot create or edit test definitions. Matrix says âœ….

| Expected (matrix) | Actual (code) |
|---|---|
| LAB_DIRECTOR âœ… for create/edit | âŒ `MANAGE_ROLES = "hasAnyRole('HOSPITAL_ADMIN', 'LAB_MANAGER', 'LAB_SCIENTIST', 'SUPER_ADMIN')"` |

**File:** `hospital-core/src/main/java/com/example/hms/controller/LabTestDefinitionController.java` (line 47)  
**Fix:** Add `'LAB_DIRECTOR'` to the `MANAGE_ROLES` constant.

---

## Gap 6 â€” âœ… FIXED â€” `LabTestDefinitionController` MANAGE_ROLES missing `QUALITY_MANAGER`

**Impact:** QUALITY_MANAGER cannot create or edit test definitions. Matrix says âœ….

| Expected (matrix) | Actual (code) |
|---|---|
| QUALITY_MANAGER âœ… for create/edit | âŒ Not in `MANAGE_ROLES` |

**File:** `hospital-core/src/main/java/com/example/hms/controller/LabTestDefinitionController.java` (line 47)  
**Fix:** Add `'QUALITY_MANAGER'` to the `MANAGE_ROLES` constant. *(Can combine with Gap 5 fix)*

---

## Gap 7 â€” âœ… FIXED â€” `LabResultController` create/update missing `LAB_DIRECTOR` and `QUALITY_MANAGER`

**Impact:** LAB_DIRECTOR and QUALITY_MANAGER cannot create or update lab results. Matrix says âœ… for "Lab Results (enter/verify)".

| Endpoint | Expected | Actual |
|---|---|---|
| `POST /lab-results` (create) | LAB_DIRECTOR âœ…, QM âœ… | âŒ Both missing |
| `PUT /lab-results/{id}` (update) | LAB_DIRECTOR âœ…, QM âœ… | âŒ Both missing |

**File:** `hospital-core/src/main/java/com/example/hms/controller/LabResultController.java` (lines 49, 86)  
**Fix:** Add `'LAB_DIRECTOR'`, `'QUALITY_MANAGER'` to the `@PreAuthorize` for create and update endpoints.

---

## Gap 8 â€” `LabTestDefinitionController` view route guard missing `QUALITY_MANAGER` in `app.routes.ts`

**Impact:** The `lab-test-config` frontend route allows `LAB_DIRECTOR`, `LAB_MANAGER`, `LAB_SCIENTIST` â€” but NOT `QUALITY_MANAGER`. Matrix says QUALITY_MANAGER gets âŒ for "Lab Test Config", so this is **actually correct per the matrix** and NOT a gap.

**Status:** âœ… No action needed â€” matrix confirms QUALITY_MANAGER should NOT have Lab Test Config access.

---

## Gap 9 â€” âœ… FIXED â€” Backend `DashboardConfigService` missing default permissions for 3 roles

**Impact:** When the backend merges persisted permissions with defaults, `LAB_TECHNICIAN`, `LAB_DIRECTOR`, and `QUALITY_MANAGER` get zero defaults. If those roles have no DB-persisted permissions, they have an empty permission set on the API side.

| Role | In `DashboardConfigService.createDefaultPermissions()` |
|---|---|
| LAB_TECHNICIAN | âŒ Missing |
| LAB_DIRECTOR | âŒ Missing |
| QUALITY_MANAGER | âŒ Missing |
| LAB_SCIENTIST | âœ… Present |
| LAB_MANAGER | âœ… Present |

**File:** `hospital-core/src/main/java/com/example/hms/service/DashboardConfigService.java`  
**Fix:** Add default permissions for `ROLE_LAB_TECHNICIAN`, `ROLE_LAB_DIRECTOR`, and `ROLE_QUALITY_MANAGER` mirroring the frontend `permission.service.ts` entries.

---

## Gap 10 â€” âœ… FIXED â€” `LabResultController` pending-review missing `LAB_TECHNICIAN` and `LAB_MANAGER`

**Impact:** LAB_TECHNICIAN and LAB_MANAGER cannot view lab results pending review. This seems intentional per the matrix (Lab Approval Queue is âŒ for Technician, âœ… for Manager).

| Role | Expected (matrix) | Actual |
|---|---|---|
| LAB_TECHNICIAN | âŒ Lab Approval Queue | âŒ Not in pending-review â€” **Correct** |
| LAB_MANAGER | âœ… Lab Approval Queue | âŒ Not in pending-review `@PreAuthorize` |

**File:** `hospital-core/src/main/java/com/example/hms/controller/LabResultController.java` (line 77)  
**Fix:** Add `'LAB_MANAGER'` to the `pending-review` endpoint `@PreAuthorize`. LAB_TECHNICIAN exclusion is correct.

---

## Gap 11 â€” `LabResultController` release missing `LAB_TECHNICIAN`

**Impact:** LAB_TECHNICIAN cannot release lab results. Matrix says âœ… for "Lab Results (enter/verify)" â€” however, "release" is a supervisory action distinct from "enter/verify".

| Expected (matrix) | Actual (code) |
|---|---|
| Ambiguous â€” matrix says "enter/verify" âœ… | âŒ LAB_TECHNICIAN not in release `@PreAuthorize` |

**Status:** âš ï¸ Intentional â€” release is an elevated action. LAB_TECHNICIAN should enter/verify but not release. **No action needed** unless matrix explicitly requires release for Technician.

---

## Gap 12 â€” `LabQcEventController` summary missing `LAB_SCIENTIST`

**Impact:** LAB_SCIENTIST cannot view QC summary (aggregated stats). Matrix says QC Events review/approve âŒ for LAB_SCIENTIST, so this is **correct per the matrix**.

**Status:** âœ… No action needed.

---

## Confirmed Correct (No Gaps)

These were investigated and confirmed matching the matrix:

| Feature | Status |
|---|---|
| Lab Orders (view/enter) â€” all 5 roles | âœ… Correct |
| QC Events (record) â€” all 5 roles | âœ… Correct |
| QC Events (review/approve) â€” only MANAGER, DIRECTOR, QM | âœ… Correct |
| Validation Studies â€” excludes LAB_TECHNICIAN | âœ… Correct |
| Lab Instruments â€” TECH, MANAGER, DIRECTOR (not SCIENTIST, QM) | âœ… Correct |
| Lab Inventory â€” TECH, MANAGER, DIRECTOR (not SCIENTIST, QM) | âœ… Correct |
| Consent Management â€” only DIRECTOR, QM | âœ… Correct |
| Staff Scheduling (view) â€” all 5 roles | âœ… Correct |
| Staff (create/update) â€” MANAGER, DIRECTOR | âœ… Correct |
| Staff (delete) â€” DIRECTOR only | âœ… Correct |
| Lab Test Config route â€” DIRECTOR, MANAGER, SCIENTIST (not QM, TECH) | âœ… Correct |
| Test Definitions (approve) â€” DIRECTOR, MANAGER, SCIENTIST, QM (not TECH) | âœ… Correct |
| Test Definitions (view) â€” all 5 roles | âœ… Correct |

---

## Actionable Fix Tasks (Ordered by Dependency)

### Backend

| # | Task | File | Roles to Add |
|---|---|---|---|
| 1 | Add `LAB_DIRECTOR`, `QUALITY_MANAGER` to `MANAGE_ROLES` | `LabTestDefinitionController.java:47` | `LAB_DIRECTOR`, `QUALITY_MANAGER` |
| 2 | Add `LAB_DIRECTOR`, `QUALITY_MANAGER` to create `@PreAuthorize` | `LabResultController.java:49` | `LAB_DIRECTOR`, `QUALITY_MANAGER` |
| 3 | Add `LAB_DIRECTOR`, `QUALITY_MANAGER` to update `@PreAuthorize` | `LabResultController.java:86` | `LAB_DIRECTOR`, `QUALITY_MANAGER` |
| 4 | Add `LAB_MANAGER` to pending-review `@PreAuthorize` | `LabResultController.java:77` | `LAB_MANAGER` |
| 5 | Add default permissions for `LAB_TECHNICIAN`, `LAB_DIRECTOR`, `QUALITY_MANAGER` | `DashboardConfigService.java` | â€” |

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

## Patient Access Analysis â€” Which Lab Roles Don't Need Patient Records?

| Role | Needs Patient Records? | Reason |
|---|---|---|
| **LAB_TECHNICIAN** | âš ï¸ Debatable | Technicians process specimens, not patients directly. They need the **lab order** (which references a patient) but rarely need to browse the full patient chart. Current access: âœ… â€” could be **downgraded to read-only lab-order context** if PHI minimization is a priority. |
| **LAB_SCIENTIST** | âœ… Yes | Scientists verify results in clinical context â€” they need patient history, allergies, and prior results to validate abnormal findings. |
| **LAB_MANAGER** | âœ… Yes | Managers oversee lab operations and occasionally review patient-related quality issues. |
| **LAB_DIRECTOR** | âœ… Yes | Directors are responsible for lab compliance and need access for audits and escalations. |
| **QUALITY_MANAGER** | âš ï¸ Debatable | QMs focus on process quality (QC events, SOPs, accreditation) not individual patient care. They may need **aggregate/anonymized** data rather than individual patient records. Current access: âœ… â€” could be reviewed for PHI minimization. |

### Recommendation

- **Keep access** for LAB_SCIENTIST, LAB_MANAGER, LAB_DIRECTOR â€” clinical and operational need.
- **Review for restriction** on LAB_TECHNICIAN and QUALITY_MANAGER â€” they could potentially work with lab-order-scoped views instead of full patient records. However, this would be a future enhancement, not a bug fix.

---

## Service-Layer Alignment (PR Review Follow-up)

Copilot PR review identified that controller-level `@PreAuthorize` expansions were ineffective because **service-layer enforcement** was more restrictive. Fixed:

### Fix A â€” `LabTestDefinitionServiceImpl.assertUserCanManageHospital()`

**Problem:** Role set only included `HOSPITAL_ADMIN`, `LAB_MANAGER`, `SUPER_ADMIN`, `LAB_SCIENTIST`. LAB_DIRECTOR and QUALITY_MANAGER would get `AccessDeniedException` on create/update/delete despite passing the controller gate.

**Fix:** Added `ROLE_LAB_DIRECTOR` and `ROLE_QUALITY_MANAGER` to the `Set.of(...)` in `assertUserCanManageHospital()`.

### Fix B â€” `LabResultServiceImpl.validateLabScientistOrMidwife()`

**Problem:** Only allowed `ROLE_LAB_SCIENTIST` or midwife. All other roles in the controller `@PreAuthorize` (DOCTOR, LAB_TECHNICIAN, LAB_MANAGER, LAB_DIRECTOR, QUALITY_MANAGER, NURSE) would get `BusinessException` on create/update.

**Fix:** Renamed to `validateLabResultAuthor()` and expanded to all roles from the controller gate: LAB_SCIENTIST, MIDWIFE, DOCTOR, NURSE, LAB_TECHNICIAN, LAB_MANAGER, LAB_DIRECTOR, QUALITY_MANAGER.

### Fix C â€” Missing i18n key `NAV.OPS_DASHBOARD`

**Problem:** The new Ops Dashboard nav item used `translationKey: 'NAV.OPS_DASHBOARD'` but the key was missing from all locale files, rendering the raw key in the UI.

**Fix:** Added `OPS_DASHBOARD` to `en.json` ("Ops Dashboard"), `fr.json` ("Tableau de Bord OpÃ©rationnel"), `es.json` ("Panel de Operaciones").

---

*Last updated: 2026-04-07 â€” all gaps fixed (controller + service layer + i18n) on `fix/lab-role-permission-gaps`*

---

# Epic-Alignment P0 â€” Shipped (2026-04-28)

> All four interoperability blockers (FHIR R4, HL7 v2 MLLP, CDS Hooks,
> SMART-on-FHIR) landed in PR #139, promoted to `main` via #140 (developâ†’uat)
> and #141 (uatâ†’main). See `claude/finding-gaps.md` for the full audit.

## Done

- [x] **P0.1 â€” FHIR R4 read API** at `/api/fhir/*` (HAPI 7.4.5). Patient, Encounter, Observation (vitals + labs), Condition, MedicationRequest, Immunization. â€” `docs/fhir.md`
- [x] **P0.2 â€” HL7 v2 MLLP TCP listener** (off by default, bounded thread pool, ORU^R01 + ADT^A01/A04/A08, framed AA/AE/AR ACK). â€” `docs/hl7-mllp.md`
- [x] **P0.3 â€” CDS Hooks 1.0** services at `/api/cds-services` (`hms-patient-view`, `hms-medication-allergy-check`). â€” `docs/cds-hooks.md`
- [x] **P0.4 â€” SMART-on-FHIR App Launch 1.0** discovery + CapabilityStatement OAuth security extension. â€” `docs/smart-on-fhir.md`

Quality gates (PR #139): 13/13 GitHub CI checks, SonarCloud gate clean (0 PR issues after 26 â†’ 2 â†’ 0 cleanup), JaCoCo 80% threshold satisfied, 17 new backend tests.

---

# Epic-Alignment P1 â€” Active queue

Top-down priority. Each item ships as one PR per the foundation-pass pattern in [`.claude/skills/pr-review-response/SKILL.md`](.claude/skills/pr-review-response/SKILL.md) + [`.claude/skills/liquibase-migration/SKILL.md`](.claude/skills/liquibase-migration/SKILL.md) (backend + tests + Liquibase + frontend in the same PR). West-Africa context lives in `claude/finding-gaps.md`.

- [ ] **P1.1 â€” Terminology binding** (gap #5)
  - [ ] LOINC on `LabTestDefinition` (column + DTO + Liquibase + UI)
  - [ ] ICD-10/11 on `PatientProblem` (already has `icdVersion` â€” wire validation + admin curation)
  - [ ] WHO ATC + RxNorm on `MedicationCatalogItem`
  - [ ] Update FHIR mappers to advertise the bound systems (`http://loinc.org`, `http://hl7.org/fhir/sid/icd-10`, `http://www.nlm.nih.gov/research/umls/rxnorm`, WHO ATC)

- [ ] **P1.2 â€” MLLP / FHIR persistence**
  - [ ] Resolve OBR-3 â†’ `LabOrder.id` from analyzer messages (with allowlisted facility mapping)
  - [ ] Persist ORU^R01 results as `LabResult` rows via the existing `LabResultService`
  - [ ] Project ADT^A01/A04/A08 into `Patient` + `Encounter` via the EMPI service
  - [ ] Per-facility allowlist (sending facility â†’ hospital)

- [ ] **P1.3 â€” CDS rule engine** (gap #3 expanded)
  - [ ] Drug-drug interaction check on `order-sign` (depends on P1.1 RxNorm)
  - [ ] Duplicate-order detection on `order-sign`
  - [ ] Pediatric dose check (uses `Patient.dateOfBirth` + bound dose)
  - [ ] BPA scaffolding for protocol cards (malaria, sepsis, OB hemorrhage)

- [ ] **P1.4 â€” CPOE order-set builder** (gap #6) â€” versioned templates, search-driven picker

- [ ] **P1.5 â€” Storyboard patient banner** (gap #15) â€” persistent allergy / problem / encounter / code-status header on every chart route

- [ ] **P1.6 â€” Chart Review tabbed viewer** (gap #16) â€” Encounters / Notes / Results / Meds / Imaging / Procedures with timeline

- [ ] **P1.7 â€” Cadence visual scheduling grid** (gap #17) â€” FullCalendar multi-resource block view

- [ ] **P1.8 â€” Inpatient eMAR** (gap #10) â€” barcode-scan administration loop on top of pharmacy + MAR entities

- [ ] **P1.9 â€” Break-the-glass workflow** (gap #21) + **granular consent scopes** (gap #22)

- [ ] **P1.10 â€” Telehealth low-bandwidth** (gap #12) â€” audio + photo + chat reusing the chat module

- [ ] **P1.11 â€” DHIS2 ADX export** (gap #14) â€” immunization, ANC, malaria reporting tied to FHIR `Immunization`

- [ ] **P1.12 â€” Referral lifecycle** (gap #13) â€” accept / decline / complete states on `GeneralReferral`

P2 backlog (gaps #9, #11, #18, #19, #20, #23, #24) tracked in `claude/finding-gaps.md`.

*Last updated: 2026-04-28 â€” P0 shipped to main via #139/#140/#141*

---
---

# Epic Parity Gap Tasklist â€” 2026-08-20

> Derived from the **HMS Ã— Epic Parity Ledger** audit (13 inspection agents, develop @ 63ddd536,
> 197 present / 113 absent capabilities verified with file-level evidence):
> <https://claude.ai/code/artifact/a2d071f3-8b46-49a6-b2f1-10ad85ae87f2>
> Ranked by risk-to-clinical-truth first, then leverage. One item = one PR into develop unless noted.
> Deliberate LMIC non-goals (NOT tasks): Surescripts/NCPDP, X12 837/835, US registries, SNOMED,
> video visits, full revenue cycle (roadmap: partner with a billing vendor).

## P0 â€” Clinical-truth risks (fabricated or dead-end data in production UI)

- [x] 1. **Nurse handoffs: real entity or removal** â€” âœ… DONE (PR #431 `feature/nurse-station-real-data`, V108 + V110): â€” `NurseTaskServiceImpl` fabricates handoff rows ("still synthetic, entity arrives in MVP 2"); completion buttons operate on generated data. Build the handoff entity (SBAR/I-PASS fields) + endpoints + wire the existing UI, or pull the surface until it's real.
- [x] 2. **Nurse order-task queue: real entity or removal** â€” âœ… DONE (PR #431): queue and dashboard counts now derive from real lab/imaging/procedure orders. â€” same file, "Orders â€” still synthetic (MVP 3)"; dashboard counts derive from fabricated lists. Same treatment as #1 (can share a PR if the entity work overlaps).
- [x] 3. **Lab pending-review: delete the hardcoded endpoint** â€” âœ… DONE (PR #432 `fix/lab-pending-review-synthetic`): â€” `LabResultServiceImpl.getPendingReviewResults` returns invented patients ("Ava Johnson", "Michael Chen"); the real queue is `/me/results/review-queue`. Remove or redirect the fake path and any consumer.
- [x] 4. **Bed/ward decision** â€” âœ… DONE (PR #433 `feature/bed-management`), built rather than dropped: â€” schema (V25), repositories, and an occupancy dashboard exist with **zero writers** (`bed_id` never populated; admissions use free-text `roomBed`). Either build bed CRUD + assignment workflow (unlocks census/bed board, in-app transfers) or drop the tables + occupancy tiles. Decide before any inpatient-logistics work.
- [x] 5. **Critical-value escalation chain** â€” âœ… CLOSED 2026-08-22 (PR #462 `fix/reassessment-p0-p1`): the mismatch read-back now persists through a `REQUIRES_NEW` transaction that reloads the row inside the inner transaction (a `BusinessException` in the caller no longer rolls it back); `acknowledgeResult` refuses a critical result with no read-back; `signPrescription`-style bypass closed on `signLabResult` too (lab attestation no longer auto-silences the ordering-clinician receipt); `criticalReadBackValue` surfaced through mapper â†’ DTO â†’ portal. Was reopened 2026-08-21: a mismatched read-back is *never persisted* â€” `recordReadBack` is `@Transactional` and throws `BusinessException` (a `RuntimeException`), so the row it just wrote rolls back, while `CriticalValueNotificationService.java:217-218` and `V116â€¦sql:41-42` both assert the mismatch is stored "verbatim so a mismatch is auditable". `critical_readback_value` is therefore write-only. Read-back is also never *required*: `acknowledgeResult` sets `acknowledged=true` with no critical-value guard, and the sweep exits on `acknowledged=false`, so acknowledging still silences a critical result without any read-back. Previously recorded as: âœ… DONE (PR #434, completed by PR #451 `feature/critical-value-readback-escalation`): â€” results are flagged + acknowledgeable but nothing notifies the ordering provider; add the notify â†’ read-back â†’ timer/escalation loop (IKODDI SMS + in-app; PR #430 gives the transport).

## P1 â€” Complete the specialty core + turn on what's already built

- [x] 6. **Labor & delivery: partograph + delivery record** â€” âœ… DONE (`feature/labor-delivery-partograph`): `clinical.labor_episodes` + `labor_partograph_entries` + `delivery_records` (V111), WHO alert/action-line evaluation with URGENT notifications, PPH/APGAR/stillbirth alerts, Labor & Delivery tab with SVG partograph chart, `NewbornAssessment.delivery_record_id` back-link (the audit's deferred pregnancy FK), episode outcome = the audit's missing `Pregnancy.outcome`.
- [x] 7. **Appointment reminders over SMS** â€” âœ… DONE (`feature/appointment-sms-reminders`): 15-min sweep reminds patients of appointments starting within 24 h â€” in-app push + IKODDI SMS (`deliversRealSms()` guard), `reminder_sent_at` exactly-once stamp (V112), first dispatch path to honour the stored `APPOINTMENT_REMINDER` notification preferences, FR-default localized message, manual trigger `POST /appointments/reminders/run`.
- [x] 8. **EMPI: finish probabilistic matching + merge** â€” âœ… DONE (`feature/empi-confirm-merge`): `/empi` admin controller (merge-by-patient w/ identity provisioning, identity merge, by-patient lookup); merge now reassigns aliases (fixes post-merge MRN lookups resolving to the dead identity), emits `PATIENT_MERGE` audit, rejects cross-tenant merges; matcher candidates scoped to the caller's hospital (tenant leak fixed); confirm-match navigates to the existing patient + `(confirm)` output; admin two-click merge mode in the panel; three stale "matcher returns empty" docs corrected. Out of scope (stated): clinical deep-merge, undo, HL7 A40 inbound.
- [x] 9. **Web parity: cancel/reschedule + proxy views** â€” âœ… CLOSED 2026-08-22 (PR #462): proxy appointments now flow through a new `AppointmentService.getAppointmentsForVerifiedPatient(patientId)` (no username resolution â€” the proxy layer has already verified access), the `isNull()`-stubbed test re-written against the real method; grant form gained the missing `expiresOn` date input (end-of-day, min today). Was reopened 2026-08-21: the proxy viewer's Appointments tab throws at runtime. `PatientPortalServiceImpl.java:1259` passes a null username into `AppointmentServiceImpl.java:755`, which calls `getUserOrThrow(username)` unconditionally. `PatientPortalServiceImplProxyTest.java:114` stubs that call with `isNull()`, so the contract is *asserted* rather than exercised and no test fails. Cancel/reschedule themselves verify clean. Previously recorded as: âœ… DONE (`feature/web-appointment-proxy-parity`): patient-portal cancel + reschedule modals wired to the previously dead `PUT /me/patient/appointments/{cancel,reschedule}` service methods (all four reschedule fields sent â€” Android omits `newEndTime` and silently 400s); cancelled appointments now stay visible in Past instead of vanishing; new proxy data viewer (`/my-family-access/:patientId`) consuming all five `proxy-access/{patientId}/â€¦` endpoints with permission-driven tabs. Two bugs fixed: the web grant form emitted a scope vocabulary (`APPOINTMENTS`) the backend never matches (`VIEW_APPOINTMENTS`) so every non-`ALL` web grant 403'd â€” form corrected + legacy tokens normalized server-side; and `expiresAt`/`EXPIRED` were stored but never enforced, so expired grants still read PHI.
- [x] 10. **Patient-facing education delivery** â€” âœ… DONE (`feature/patient-education-delivery`): new `/me/patient/education*` self-service API (list assigned + read one + record progress/rating/understanding + ask & list questions), all IDOR-safe via `resolvePatientId`; a `PatientEducationProgress` row IS the assignment record, so no entity or migration was needed. New `/my-education` portal page: to-read / completed / questions tabs, warning-sign safety banner, reader with mark-read â†’ confirm-understanding â†’ rate, and patient-authored questions (the entity, service method and Swagger description existed but no patient could reach them). Security fix: `GET /patient-education/progress/{id}` and `/questions/{id}` were `isAuthenticated()` with no ownership check â€” an IDOR the moment patients hold tokens; now staff-only. Dead columns `completionCount`/`ratingCount` (always 0) are now written on patient completion/rating.

### Field-reported fixes (not numbered â€” found in production, outside the audit's scope)

- [x] **Refill approval queue was unreachable** â€” âœ… DONE (`fix/refill-approval-reachability`): the whole chain existed and worked â€” patient submits, `notifyCareTeamForRefillRequest` emails and in-app-notifies the prescriber, `GET /refills` returns their queue, approve/reject enforce prescriber + status â€” but no click anywhere in the portal reached `/refills`. No sidebar entry, both dashboard "Refills" tiles pointed at `/prescriptions`, and the one `router.navigate(['/refills'])` fired on an inbox category `ResultReviewServiceImpl` never emitted (its `RefillRequestRepository` was injected and unused). Added the sidebar entry, corrected both tile routes, added a doctor tile with the pending count, and wrote the missing `REFILL_REQUEST` inbox emitter. Also added the missing **pause/hold** state (`RefillStatus.PAUSED`, `PUT /refills/{id}/pause`, reason mandatory since the patient is told): a held request stays actionable, the patient can still cancel it, and it drops out of the inbox so the queue clears. The `pharmacy-refill.spec.ts` E2E masked all of this â€” it deep-links to `/refills`, and asserted a `PENDING` filter that neither the component nor the enum has.
  - **Still open, deliberately:** approval is terminal â€” it writes `APPROVED` and creates no downstream prescription or pharmacy order â€” and `DISPENSED` is declared but nothing ever writes it. That's a workflow decision, not a wiring bug.

### Verification pass â€” 2026-08-21

All ten P0/P1 items re-verified against develop with code evidence rather than
checkboxes (the P0 boxes above were stale: every one of items 1â€“5 had shipped in
PRs #431â€“#434 and none had been ticked). Seven were genuinely closed. Three were
partial and have been completed:

- **#5 critical-value escalation** â€” read-back did not exist (the acknowledge
  endpoint takes no body, so nothing recorded *what* the clinician was told), and
  the escalation re-notified the same provider once then stamped a flag the sweep
  excluded on, so an unacknowledged critical result went permanently silent after
  two alerts to one person. Closed by PR #451 (V116).
- **#6 labor & delivery** â€” the `NewbornAssessment.delivery_record_id` back-link
  was persistence-only: column, FK and `@ManyToOne` with nothing setting or
  reading them. Closed by PR #450. Note the remaining half-bridge:
  `DeliveryRecord` holds no reference to a newborn patient at all, so nothing can
  populate the link automatically yet â€” that is a maternity-workflow design
  decision, not wiring.
- **#8 EMPI** â€” the merge panel shipped unclickable (no nav entry anywhere, the
  same defect class as the refill queue), and the merge endpoints checked the two
  identities against each other but never against the caller, so a hospital-A
  admin could merge hospital-B patients. Closed by PR #449.

Also landed alongside: PR #447 (one orphaned `patient_id` returned 500 for the
entire registrations desk) and PR #448 (the foreign key that would have prevented
it â€” the schema has none at all, because V1 came from Hibernate SchemaExport).

Open decisions left for a human, all stated in the relevant PR bodies:
`app.empi.probabilistic.enabled` still defaults to false; the surviving orphaned
registration row is untouched; FK coverage beyond `patient_id` needs a call on
what a hospital delete should do to its registrations.

## P2 â€” Structural gaps with high leverage

- [x] 11. **Slot inventory for scheduling** â€” âœ… CLOSED 2026-08-22 (PR #467 `feature/slot-inventory-population`, no migration): the model can now be populated end-to-end â€” visit-type + session-template CRUD (`/visit-types`, `/session-templates`: duplicate code refused pointing at reactivate, retired visit type refused, window-shorter-than-one-slot refused because generation would silently produce nothing, unscoped super-admin refused, foreign staff/department/rows 404) and the `/slot-admin` portal page (HOSPITAL_ADMIN/SUPER_ADMIN), first caller of all five `/slots` endpoints (generate, search, hold, release, block-with-reason). `capacity_per_slot` deliberately not exposed (unimplementable under `uq_slot_staff_start`); booking an Appointment from a slot still open (which model owns the time â€” blocks #22). Was âš  REOPENED 2026-08-21 (see Reassessment): the model cannot be populated. There is no CRUD, service or seed for `visit_types` or `session_templates`, and `VisitTypeRepository` has zero callers â€” so no row can enter either parent table, `POST /slots/generate` can only ever return `slotsCreated=0`, and `GET /slots/search` only ever returns empty. All five `/slots` endpoints have no portal caller and no nav entry. `appointment_slots.appointment_id`, `SlotStatus.BOOKED`, `session_templates.capacity_per_slot` (unimplementable under `uq_slot_staff_start`) and `visit_types.patient_bookable` all ship dead. Previously recorded as: âœ… FOUNDATION DONE (PR #459 `feature/slot-inventory-foundation`, V121): visit types â†’ session templates â†’ generated slots, idempotent generation, open-slot search, hold/release/block, expired-hold reclaim. Real FKs (new tables). Deliberately deferred to follow-ups: booking an Appointment from a slot (the two models need reconciling on which owns the time), patient self-scheduling, waitlist auto-offer (#22), utilisation reporting. â€” visit types â†’ session templates â†’ searchable open slots. Unlocks real self-scheduling, waitlist auto-offer, and utilization reporting in one model. (Biggest single build in this list â€” consider a foundation-pass PR series.)
- [x] 12. **Referral â†’ appointment linkage** â€” âœ… DONE (PR #453, V117): scheduling a referral now creates and links a real Appointment. Null when the referral targets an external facility with no receiving provider or department â€” Appointment requires staff, department AND assignment. â€” referral completion stores a timestamp + free-text location but never creates the Appointment row; create + link it.
- [x] 13. **Orphan-read writers** â€” âœ… DONE (PR #456): `/on-call` CRUD (overlap-refusing, HOSPITAL_ADMIN writes) and `/advance-directives` CRUD (revoke, never delete). No migration â€” both tables existed; only the writers were missing. Portal surfaces landed 2026-08-22 (PR #465): `/on-call` page (route + nav roles mirror backend READ_ROLES exactly; writes gated in-component to WRITE_ROLES; overlap refusal surfaced verbatim) and an Advance Directives tab on patient detail (revoke ceremony, never delete; only ACTIVE/PENDING editable). Also fixed the storyboard banner, which rendered REVOKED/EXPIRED directives as an active code status â€” it now filters to active ones and marks PENDING. â€” on-call schedule (read by `GET /me/on-call-status`, written by nothing) and advance directives (read by storyboard/record-sharing, no controller): add minimal CRUD for each.
- [x] 14. **Drug-interaction KB expansion** â€” âœ… DONE (PR #458, V120): 12 â†’ 29 pairs covering the warfarin, rifampicin-induction, QT, statin-myopathy, electrolyte and serotonergic sets, each citing BNF 86 / WHO Model Formulary 2024 / NICE. âš  THE SEED NEEDS A PHARMACIST'S SIGN-OFF. The durable half is the new `/drug-interactions` admin API so pharmacy can curate without a migration. Admin UI landed 2026-08-22 (PR #466): `/pharmacy/drug-interactions` page (RxCUI-validated create, deactivate with a confirm that owns the blast radius, reactivate on retired rows, platform-scope banner) plus backend fixes â€” `PUT /{id}/reactivate`, a duplicate guard that now also catches retired pairs ("reactivate it instead of re-creating it"), the severity+includeInactive list no longer returning only-inactive rows, and notes/monitoring-interval actually persisted. Closes the one-tenant-can-permanently-silence-a-MAJOR-interaction finding; platform-vs-tenant scoping itself is still an open decision. â€” checking pipeline is real at prescribe/dispense/CDS-Hooks layers but the local KB is a 12-pair seed; curate a WHO-essential-medicines-scale interaction set.
- [x] 15. **Controlled-substance enforcement** â€” âœ… CLOSED 2026-08-22 (PR #463 `fix/reassessment-p2-security`): `controlledSubstance`/`requiresCosign` got writers (request DTO fields, set-only mapping â€” a flag once true cannot be edited off; cancel and rewrite instead); `POST /prescriptions/{id}/cosign` second-prescriber ceremony added (DOCTOR/NURSE_PRACTITIONER, cosigner must differ from prescriber); the duplicated rule folded into one `ControlledSubstanceGuard` component used by both prescribe and dispense paths, with the gate test pinning the component directly. Two-factor transport still an open decision â€” ships fail-closed; the portal deliberately offers only `requiresCosign` so no UI path can brick a prescription. Was reopened 2026-08-21 â€” the gate can never fire: nothing in `src/main` ever calls `setControlledSubstance` or `setRequiresCosign`. `PrescriptionRequestDTO` has no such field and `PrescriptionMapper` never maps one, so no API path can flag a prescription as controlled; the gate's condition is unreachable by construction. There is also no writer for `twoFactorVerifiedAt`/`cosignedAt`/`cosignedBy` and no cosign or two-factor endpoint in any controller, so a row that *is* flagged (legacy or hand-edited) can never be cleared and is permanently unsignable. The gates themselves are correct â€” they simply guard a flag nothing can set. Previously recorded as: âœ… DONE (PR #454): gates at prescribe (status-keyed) and dispense (irreversible step). Both needed â€” RefillApprovalServiceImpl writes SIGNED directly, bypassing the prescribe path. â€” flags, two-factor and co-sign columns exist; nothing enforces them. Add the prescribe/dispense gates.
- [x] 16. **Server-side prescription signing ceremony** â€” âœ… CLOSED 2026-08-22 (PR #463): client-assertable statuses whitelisted to `DRAFT / PENDING_SIGNATURE / PENDING_CLARIFICATION / CANCELLED / DISCONTINUED` â€” `TRANSMITTED` (dispensable, and reachable only through the hole) and every other workflow status now refuse with a message naming the owning endpoint; signature evidence (signedAt / cosign state / two-factor state / pre-V118 marker) rendered in the portal detail panel; `TRANSMITTED` removed from the edit dropdown. Was reopened 2026-08-21: three separate comments claim this is "the only path" to a dispensable state, and it is not. `rejectClientAssertedSignature` blocks only `SIGNED`, while a client-asserted `TRANSMITTED` is still copied onto the entity by `PrescriptionMapper.java:96-98` â€” and `TRANSMITTED` is dispensable. So the ceremony is bypassable for the exact purpose it exists to serve. A prescription created as `TRANSMITTED` and later refill-approved also reaches `SIGNED` via `RefillApprovalServiceImpl.java:199` with `signature_value` NULL, making it indistinguishable from the documented "signed before V118, unverifiable" case. Signature evidence is dead on the wire (the mapper emits it; no portal file reads it). Previously recorded as: âœ… DONE (PR #455, V118): `POST /prescriptions/{id}/sign` is the only path to SIGNED; records signer, instant and a SHA-256 digest. Create/update now REFUSE a client-asserted SIGNED. Pre-V118 rows deliberately NOT backfilled â€” `signature_value IS NULL` on a SIGNED row means "signed before V118, unverifiable". â€” "signed" is currently a client-supplied status; require an authenticated server-side sign action (reuse the hash-based e-signature layer).
- [x] 17. **HL7 outbound transport** â€” âœ… DONE (PR #457, V119): MllpOutboundSender + dispatch sweep, mirroring the inbound listener and reusing MllpFrameCodec. MSA-1 parsed (AA/CA only); negative ACK terminal, transport failure retried to a ceiling. Off by default. Observability landed 2026-08-22 (PR #464): `GET /lab-instrument-outbox` scoped page + per-status counts, `GET /{id}` full payload, retry-from-ERROR (resets attempts, keeps lastError), transport-config endpoint, and the `/lab-outbox` portal page â€” first reader of `last_error`/`attempts`; `GET /orders/{id}` no longer filters to PENDING (it silently absorbed ERROR rows), and the read-role list now matches the security filter (LAB_DIRECTOR/QUALITY_MANAGER were 403'd by the method guard the filter admitted them past). â€” OML/ORU messages are built and queued in the instrument outbox but never transmitted; add the MLLP sender (mirror of the inbound listener).

### P2 execution â€” 2026-08-21

All seven P2 items landed as PRs #453â€“#459. Notes that outlive the tickets:

- **#11 is a foundation pass, not the whole feature** â€” the tasklist anticipated
  a series. What exists is the model and its inventory operations; what does not
  is booking an Appointment from a slot, which needs a decision on whether the
  slot or the appointment owns the time. Everything in #22 waits on that.
- **#14's seed needs a pharmacist's sign-off** before anyone relies on it. The
  rows are transcribed from standard references and each names its source, but
  clinical content belongs to a pharmacist rather than to whoever wrote the
  migration. The admin API is the part that makes the KB maintainable.
- **#15 and #16 each carry a copy of the controlled-substance rule.** Neither
  path covers the other â€” a prescription signed through #16's endpoint never
  passes #15's create/update gate â€” so both are needed until they merge and can
  be folded into one helper.
- **New tables carry real foreign keys.** V117, V118, V119 and V121 all add
  constraints, which the tables V1 generated cannot (V1 came from Hibernate
  SchemaExport, which emits none). Every new migration is verified against a
  real postgres:16-alpine in `LiquibaseSchemaIT` â€” the H2 suite builds tables
  FROM the entities, so it can never catch a column a migration forgot, and prod
  runs ddl-auto=validate against the Liquibase-built schema.

### Deploy incident â€” 2026-08-21

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

which fails `FhirConfig` â†’ the application context â†’ Tomcat. A total outage from
three files that were present in the diff, in the directory listing and in the
review. #457 carried the identical bug with V119.

**2. `develop` did not compile.** PR #455 shipped `signPrescription` on the
interface, the controller, the entity columns, the DTO, the mapper and six tests
â€” but never `PrescriptionServiceImpl.signPrescription`. Every branch cut from
develop failed the same way until PR #460 restored it, written against the tests
that #455 had shipped, which were the real specification.

**The structural cause, which is not fixed.** Every migration's `<changeSet>` is
appended immediately before the closing tag of one ~1800-line `changelog.xml`, so
any two branches that add a migration edit the same lines. Resolving that overlap
by taking develop drops the branch's own changeset while leaving its `.sql` on
disk â€” the file is plainly there and the diff looks untouched, so nothing reads as
wrong. GitHub's **"Update branch" button resolves it exactly that way**: V120 was
lost once and V121 twice, the second time minutes after being restored.

> **Avoid "Update branch" on any branch carrying a migration.** Merge develop
> locally and confirm the registered set afterwards.

**The guard.** `hospital-core/src/test/java/com/example/hms/db/MigrationRegistrationTest.java`
fails on any migration that is unregistered, referenced-but-absent, empty, or
misnamed. Nothing else can catch this: the H2 test profile builds its schema FROM
the entities with `create-drop`, so the columns always exist there regardless of
what the changelog says. It caught V120 on #458 and V121 on #459 â€” in both cases
converting what would have been a silent production defect into a red build. On
PR #458 the loss would have left the drug-interaction checker running against
V63's 12-pair seed while reporting green â€” worse than no checker at all.

**Deferred, needs a decision:** the durable fix is to move *new* changesets into
per-file fragments under an `includeAll` directory so two branches never touch the
same lines. It is not done here because Liquibase identifies a changeset by
`(id, author, file path)` â€” relocating the existing 119 would make it treat them
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
  and fail where the roles do not exist â€” an operational call.
- **Only `ROLE_DOCTOR` can sign a prescription.** `ROLE_NURSE_PRACTITIONER`
  appears in four `@PreAuthorize` expressions on `PrescriptionController` and
  behind `RoleValidator.isNursePractitioner`, but that role is never seeded â€” it
  exists only as a `JobTitle`. `ROLE_MIDWIFE` *is* seeded and midwives prescribe
  throughout the OB module, but is not on the sign annotation. Doctors-only may be
  correct; it should be a decision rather than an accident of an unseeded name.

**Portal side of #16.** The prescription edit form offered `SIGNED` in its status
`<select>` â€” which is how "signed" came to mean a clinician picked a word from a
dropdown. With the backend now refusing a client-asserted signature, that option
was a control that always fails; it is removed, and a sign action added, because
the endpoint otherwise had no caller anywhere.

### Reassessment â€” 2026-08-21 (code evidence, 23 agents)

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

**The pattern is not that the work was not done â€” it is that the last mile was
not.** Ten of the eleven partials are the same three shapes this list has been
tracking all along: a surface with no caller, a column with no reader, and a
comment asserting behaviour the code does not implement. The features are real;
what is missing is the wiring that lets anyone use them.

**Unreachable from the portal** â€” built, tested, merged, no way in:

- `/on-call` and `/advance-directives` (#13) â€” no nav entry, no route, no portal
  service. A HOSPITAL_ADMIN cannot create a rota entry and a clinician cannot
  record a DNR from the UI; the tables are still read-only in practice, which is
  the exact condition #13 existed to fix.
- `/drug-interactions` admin API (#14) â€” the "durable half" of that PR, per its
  own body, has no caller anywhere.
- All five `/slots` endpoints (#11), `GET /lab-instrument-outbox/orders/{id}`
  (#17), `GET /empi/identities/by-patient/{id}` and `POST
  /empi/identities/{id}/merge` (#8), and `POST /appointments/reminders/run` (#7,
  which the sweep otherwise passed clean).

**Dead columns** â€” written, never read: `critical_readback_value` (#5),
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
  otherwise be a cross-tenant write vector" â€” a clinician at hospital A can
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
- **No new P0â€“P2 write surface emits an audit event** â€” signing, read-back,
  directives, on-call, slot holds, KB edits. EMPI merge is the sole exception,
  and this list called that convention out at the time.
- **Three check-then-act races.** Slot hold reads `isOfferable` then saves with
  no `@Version` and no lock, so two receptionists both win. The reminder sweep
  and its manual trigger have the same shape, under a javadoc asserting "each
  appointment is stamped exactly once". There is no ShedLock anywhere.
- **`LiquibaseSchemaIT` does not do what I wrote on it.** Its comment claims it
  catches columns a migration forgot "because prod runs ddl-auto=validate while
  H2 builds tables FROM the entities". It never boots Hibernate â€” it applies the
  changelog and hand-checks five named objects. Every test profile is
  `create-drop`; `validate` appears only in dev/local/prod/uat. **Nothing in CI
  compares entities to the migrated schema**, which is precisely the second half
  of the outage recorded above. `MigrationRegistrationTest` proves a file is
  *listed*, never that its contents match the model. Defect class 3, on the file
  written to prevent the recurrence.
- **`docs/roadmap.csv` / `roadmap.md` were last touched 2026-05-17**, three
  months before PRs #431â€“#460. The entire P0â€“P2 tier is absent from the file the
  `roadmap-sync-workflow` skill designates as the status source of truth, and
  `roadmap.csv:25` still records the stale "EMPI matcher returns empty" claim.
- **Backend i18n is broadly unkeyed.** 27 of the 53 dotted keys thrown as
  exception messages are missing from `messages.properties`, so
  `GlobalExceptionHandler` returns the raw key to the client. The missing
  `prescription.sign.ceremony.required` is the house norm, not a #16 regression.
  The portal, by contrast, is clean: 6755 keys Ã— 3 locales, zero drift.

### Promotion â€” 2026-08-21

`develop dfc01f9e` â†’ `uat 313b3e82` â†’ `main 4952824c`, carrying #448â€“#460.

Verified **before** promoting, not after: V115â€“V121 all registered in
`changelog.xml`, `MigrationRegistrationTest` green, and `LiquibaseSchemaIT`
applying the full changelog to a real `postgres:16-alpine`.

### Post-reassessment fixes â€” 2026-08-22

Every reassessment finding with a **REOPENED** checkbox is closed, in two PRs:

- **PR #462 `fix/reassessment-p0-p1`** â€” all P0/P1 findings: critical read-back
  persists (REQUIRES_NEW) and is *required* to acknowledge or sign-silence a
  critical result; newborn `deliveryRecordId` got its first writer (delivery
  select + single-delivery preselect on the postpartum tab); EMPI nav hoisted out
  of the receptionist-only gate (NURSE/DOCTOR reachability specs); proxy
  appointments no longer throw (`getAppointmentsForVerifiedPatient`); proxy grant
  expiry input; education library staff-only (incl. MIDWIFE + RECEPTIONIST);
  completed handoffs readable via `?status=`; stale `roadmap.csv:25` corrected.
- **PR #463 `fix/reassessment-p2-security`** â€” #15/#16 closures above, plus the
  two cross-cutting security findings: `AdvanceDirectiveServiceImpl.create` now
  requires the patient registered at the caller's hospital (404 idiom), and
  `/lookup` is staff-only (was `isAuthenticated()` returning patient
  name/email/phone to any token-holder, including patients).

Promoted: `develop 0ef4e9ef` â†’ `uat 7d72a4a5` â†’ `main 4ce54e64`
(MigrationRegistrationTest + LiquibaseSchemaIT verified on develop first).

**SonarCloud is green again (2026-08-22).** `SONAR_TOKEN` was regenerated and
stored as a repo secret; the `Build and analyze` job on develop (run 32545107632)
completed **success** â€” first green Sonar since May. Expect a backlog of real
findings on the next few analyses rather than a clean pass. âš  The token was
pasted in a chat transcript during rotation â€” revoke it in SonarCloud (My
Account â†’ Security), issue a fresh one, and update the secret.

The second adversarial re-verification of the seven #462 fix claims (13 agents,
verify â†’ refute â†’ coverage-critic) completed 2026-08-22: **five DONE and
challenge-survived** (newborn-delivery link, EMPI nav, proxy appointments, grant
expiry, education gates) and **two PARTIAL** â€” critical read-back evidence was
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
population (#11) â€” evidence recorded on the items above. Promoted to uat/main
2026-08-22 (second sync of the day; MigrationRegistrationTest +
LiquibaseSchemaIT verified green on develop `b1e26153` first).

## P3 â€” Broader parity, pick by demand

With P0â€“P2 closed end-to-end (promoted 2026-08-22), this tier is the working
list. All P3 items are now closed: the final batch (22, 24, 25) shipped
2026-08-22 as the stacked PRs #476 â†’ #477 â†’ #478 (V128â€“V130).

- [x] 18. Growth charts (needs a height column on vitals) + flowsheets/I&O grids â€” âœ… DONE 2026-08-22, two PRs. **PR #469** (V122): `height_cm` + `head_circumference_cm` on the vitals bundle, wired through all four write paths incl. the triage form's `heightCm` (accepted and silently DISCARDED since it shipped â€” first reader); FHIR LOINC 8302-2/9843-4; weight floor 1.0â†’0.2 kg (NICU); `GET /patients/{id}/growth-chart` (ageDays server-side; birth-weight seed from single-infant deliveries only, hospital-scoped) + pediatric-only Growth tab (inline-SVG trajectory, months/years axis). âš  WHO percentile curves DELIBERATELY deferred: LMS reference tables must be imported from a verified source with clinical sign-off (the V120 seed precedent) â€” never fabricated. Also fixed en passant: the patient-detail vitals tab rendered NO metric in production (portal interface invented field names the wire never carried; specs mocked the wrong shape and stayed green) â€” `VitalSignService` now mirrors the response DTO field-for-field. **PR #470** (V123, was stacked on #469): `clinical.intake_output_entries`, the first NUMERIC I&O surface (the old INTAKE_OUTPUT was a free-text task category with no volumes); route enum carries its category so intake/output mismatch is unrepresentable; `POST`/`GET /patients/{id}/intake-output` with server-computed window totals + balance (default 24 h); I&O patient-detail tab, modal-per-timepoint (labor/postpartum pattern). Deferred: per-shift totals (no shift-boundary concept exists anywhere), consuming the still-dead `RECORD_INTAKE_OUTPUT` catalog permission (role-gates kept for consistency â€” permission-model decision flagged, not made), entry amendment/retraction.
- [x] 19. Microbiology (cultures, susceptibilities) â€” âœ… DONE 2026-08-22 (**PR #472**, V124: `lab.micro_culture_results` / `micro_isolates` / `micro_susceptibilities`, real FKs into lab_orders/lab_specimens). Culture report on a lab order with PRELIMINARYâ†’FINALâ†’CORRECTED lifecycle (ImagingReportStatus naming): finalize requires a growth result, GROWTH requires â‰¥1 isolate, post-FINAL mutations demand a correction reason and stamp CORRECTED permanently. S/I/R susceptibility rows per isolate (duplicate antibiotic per isolate unrepresentable). Finalized GROWTH notifies the ordering provider best-effort â€” non-numeric "Positive" values are invisible to the numeric critical chain. New `/micro-cultures` root deliberately NOT under `/lab-orders/**` (that POST matcher 403s all lab roles first-match â€” live defect on transition/specimen endpoints, documented not fixed); 404-not-403 on reads AND writes (the lab-results acknowledge/read-back cross-tenant hole was NOT copied). Portal: `/microbiology` workbench (first caller of every endpoint) + patient-detail Micro tab (roles mirror controller; PHARMACIST included for stewardship); MICRO i18n 93 keys Ã—3. âš  Deferred: micro HL7 ingest (its prerequisite â€” the ORU parser reading only the FIRST OBX/OBR and ACKing AA while dropping the rest â€” was FIXED 2026-08-23 in the V131 multi-OBX PR; the micro-specific ingest mapping itself remains open), FHIR DiagnosticReport (none exists anywhere), organism/antibiotic coded vocabularies.
- [x] 20. Note co-sign workflow (student/resident attestation) â€” âœ… DONE 2026-08-22 (**PR #473**, V125, was stacked on #472; GitHub did not retarget after #472 merged so the merge landed on the feature branch â€” carried into develop by merge `a56c0bc0`). EncounterNote signature was fully client-asserted (applySignature copied signedAt/signedByName from the request; the form stamped a browser time and promised a lock that didn't exist). Now: `POST /encounters/{id}/notes/sign` (author-only 403, server identity, SHA-256 digest, re-sign refused) + `/notes/cosign` (DOCTOR; requires declared requiresCosign + signed note; self-cosign refused; co-signer staff resolved AT THE NOTE'S HOSPITAL â€” the prescription-cosign anywhere-lookup gap fixed rather than copied). Signed note LOCKS the upsert (incl. pre-V125 asserted rows); client-asserted signature refused loudly naming the endpoint; requiresCosign set-only; addendum signedAt server-stamped. Pending co-signs feed the Clinical Inbox `DOCUMENT_TO_SIGN` category (existing SIGN routing = zero new queue UI), minus the viewer's own. `signature_value IS NULL` on a signed row = "asserted pre-V125, unverifiable" (V118 stance, no backfill). Portal: form's free-text Signer fieldset â†’ set-only requires-cosign checkbox; sign/cosign buttons + evidence on the encounter panel; chart-review co-sign pill. âš  Deferred: STUDENT/RESIDENT roles (none exist â€” role-model decision, the NP-unseeded precedent), staff-to-staff supervision relation (queue is hospital-wide role-based like DischargeApproval), NursingNote co-sign, AuditEventLog integration (EncounterNoteHistory NOTE_SIGNED/NOTE_COSIGNED rows are the audit substrate).
- [x] 21. Registration extras: patient photo capture, consent-to-treat e-sign at check-in, guarantor accounts â€” âœ… DONE 2026-08-22 (**PR #474**, V126: photo columns on clinical.patients + `patient_treatment_consents` + `patient_guarantors`, real FKs). Photo binaries live OUTSIDE the upload tree and stream only through authenticated `GET /patients/{id}/photo` â€” because `GET /uploads/**` is permitAll and serves the whole upload dir statically (âš  existing profile images + patient documents are downloadable UNAUTHENTICATED with semi-guessable names â€” flagged, separate security decision, NOT fixed here). LIVE BUG FIXED: pre-check-in `consentAcknowledged` was REQUIRED by the portal form and silently DISCARDED by the backend â€” now persisted, idempotent per appointment. Consent is a RECORD, not a gate (blocking check-in/walk-ins on missing consent = open clinical decision); revoke-never-delete + SHA-256 digest on ELECTRONIC captures; recorded from check-in dialog (best-effort, never blocks the desk), pre-check-in, and manual endpoints. Guarantors: deactivate-never-delete, one primary per patient+hospital (invoice linkage deferred). Portal: photo component (authenticated blob fetch â€” `<img src>` carries no bearer token; file upload + webcam frame-grab), check-in consent step, coverage-tab guarantor+consent panels; +58 i18n keys Ã—3. Guarantor PUT deliberately omits SUPER_ADMIN (the `PUT /patients/**` chain matcher hard-denies it). Sonar round: `PhotoPayload` recordâ†’class (S6218) + coverage top-up.
- [x] 22. Recall lists + waitlist auto-offer (depends on #11) â€” âœ… DONE 2026-08-22 (**PR #476**, V128), built on the same-day decision (user): **the Appointment owns the time**. Slot booking: `POST /slots/{id}/book` creates a normal Appointment (mandatory-assignment refusal per the AppointmentServiceImpl contract, referral-recipe builder) and stamps the slot BOOKED + appointment_id â€” first writer of the dead column and `SlotStatus.BOOKED`; new `@Version` column turns the double-book race into "That slot was just taken" (saveAndFlush INSIDE the tx so the rollback takes the appointment insert with it); free-on-cancel wired into BOTH cancel paths (confirmOrCancelAppointment CANCELLED/RESCHEDULED + patient-portal cancelMyAppointment); a freed slot whose time already passed re-enters as BLOCKED, never OPEN (searchOpen would hide it but the rota must read honestly). Waitlist offers grew teeth: offer now takes a CONCRETE slot (picker = searchOpen filtered by the entry's dept/provider/date window), HOLDs it for the offer window (default 48 h, capped at slot start â€” a stale accept must not book the past), stamps offered_slot_id/offer_expires_at and notifies the patient via new `PatientOutreachNotifier` (AppointmentReminderService channel contract: in-app needs portal account, SMS only over `deliversRealSms()` â€” mock transport logs bodies, never PHI â€” both honouring APPOINTMENT_REMINDER preferences, 320-char cap); desk accept releases-the-hold-then-books and closes the entry, decline frees + rewaits, and a reconcile sweep returns lapsed offers to WAITING (SlotHoldReclaimScheduler already frees the slot side; nothing flipped the entry). No inbound SMS reply channel exists â†’ v1 is offer + expiry + desk confirm, by design. Recalls: `scheduling.patient_recalls` (+ named FKs, partial idx_recall_due) + `/recalls` endpoints (close/cancel-never-delete; link-appointmentâ†’SCHEDULED with same-patient guard; CLASS-level @PreAuthorize â€” /recalls rides anyRequest().authenticated() so a forgotten method annotation fails OPEN incl. ROLE_PATIENT) + RecallReminderService sweep (V112 exactly-once notifiedAt stamp â€” stamped even when every channel skipped, so it converges; no clinical reason in the SMS body) + reception-cockpit Recalls tab (overdue flags, manual add, status filters). LIVE BUG FIXED: checkout's FollowUpAppointmentRequest (reason/preferredDate/notes) was REQUIRED by the AVS flow since MVP 6 and silently DISCARDED at EncounterServiceImpl â€” now feeds a CHECKOUT-source recall (best-effort try/catch, never fails the checkout â€” the referral-appointment precedent). +40 i18n keys Ã—3. âš  Deferred: patient self-service accept/decline (needs an inbound reply channel), automatic offer matching (v1 is desk-driven; the sweep only reconciles), recallâ†’booking one-click shortcut (book + link-appointment are two calls today).
- [x] 23. Downtime/read-only continuity mode; wristband & label printing â€” âœ… DONE 2026-08-22 (**PR #475**, V127, was stacked on #474; the base branch was not deleted before merging so GitHub again did not retarget â€” carried into develop by merge `20ea151c`; NEXT TIME: delete the base branch immediately after merging the bottom PR). Downtime: `platform.platform_downtime_state` singleton (V80 pattern, seeded, fail-open) + `ReadOnlyModeFilter` blocking POST/PUT/PATCH/DELETE with 503 + `X-Readonly-Mode` discriminator; deliberately HTTP-layer, NOT DB-level read-only (login writes lastLoginAt; the audit logger swallows failures â€” DB-level would break auth and silently drop the compliance trail); allowlist is /api-PREFIXED (servlet filters see the context path â€” pinned by test) and keeps auth/telemetry/the toggle open. Registered via FilterRegistrationBean in a @Configuration, NOT @Component â€” a Filter @Component with a service dependency breaks every @WebMvcTest slice (found on the first full-suite run). Portal: persistent non-dismissible shell banner (60s poll â€” survives login, unlike the STOMP broadcast), error interceptor insta-marks the banner on a readonly 503, offline-dispense interceptor now REFUSES to queue X-Readonly-Mode 503s (bare 503 would silently queue pharmacy dispenses for doomed replay), super-admin Emergency page toggle card. Printing: `WristbandPdfService` (pdfbox+zxing already on classpath â€” zero new deps); wristband QR = BARE patient UUID (FiveRightsVerificationService does UUID.fromString on the raw scan â€” pinned by test; MRN human-readable only); specimen label QR = `barcode_value` ("LAB-"+accession) â€” that column's first reader since V27. Print buttons on patient-detail header + lab specimen table. âš  Deferred: downtime packet PDF (census/MAR aggregation), PlatformReleaseWindow.freeze_changes enforcement (still decorative), scan-to-receive lookup endpoints. Sonar round: volatileâ†’AtomicReference (S3077).
- [x] 24. FHIR bulk `$export` completion; FHIR enable-runbook â€” âœ… DONE 2026-08-22 (**PR #477**, V129, stacked on #476). The foundation pass queued jobs into a ConcurrentHashMap that nothing ever ran: statuses IN_PROGRESS/COMPLETED/FAILED were unreachable, the poll endpoint returned 202 forever without reading job state, and a restart forgot every job. Now: `platform.fhir_bulk_export_jobs` + `fhir_bulk_export_files` (V129, platform-schema infra precedent) + `FhirBulkExportRunner` â€” a `@Scheduled` sweep (house pattern; deliberately NOT `@Async`, which would require `@EnableAsync` and silently activate the codebase's one dormant `@Async` method) claiming QUEUED jobs via atomic conditional UPDATE (safe without ShedLock), streaming NDJSON write-as-you-page through the same five hospital-scoped queries + FHIR mappers `$everything` uses, one file per resource type, empty types leaving neither files nor manifest rows. Output on LOCAL DISK under `app.fhir.operations.bulk-export.storage-dir` â€” a SIBLING of the upload tree (V126 photo precedent), never under permitAll `/uploads/**`; no S3 client exists on the classpath, so S3 stays deferred honestly instead of implied. Poll endpoint finally has all spec branches: 202+X-Progress (real N/M patient counts), **200+manifest** (`transactionTime`/`request`/`requiresAccessToken:true`/`output[]`), 500+OperationOutcome on FAILED, 404 for cancelled (row KEPT â€” deactivate-never-delete â€” but polls 404 per the spec's post-DELETE contract), plus the authenticated download endpoint (file name resolved from the DB row, never raw client input). Kickoff hardened: requires an active hospital (the null-tenant dead-job hole closed with a 400), rejects unsupported `_type` and `_outputFormat` at kickoff (400 â€” silent dropping would fake completeness), and **requires SUPER_ADMIN/HOSPITAL_ADMIN** (mass PHI extract; `/fhir/**` has no role gate of its own â€” checked in the service; the status controller mirrors it class-level, since `/fhir-bulk-status/**` otherwise rode `anyRequest().authenticated()` down to ROLE_PATIENT). Flag-off flipped 405â†’**501** exactly as the foundation pass documented it would ("ships with the async runner"); ITs updated. Cancel mid-run aborts at the next patient page and discards partial output; failure marks FAILED + message + cleanup, no auto-retry. docs/fhir-bulk.md rewritten with the **enable runbook** (env-var flags + restart, NOT DB flags; storage-dir rules; role requirements); stale "V103 in the next free slot" and fhir.md's "not yet" fixed. âš  Deferred: Group-level $export (no Group resource), canonical poll-URL mounting under /api/fhir/*, S3 target, retry/DLQ, output retention sweep.
- [x] 25. Analytics: report builder / scheduled reports; NEWS2/MEWS early-warning scores â€” âœ… DONE 2026-08-22 (**PR #478**, V130, stacked on #477). **25a Scheduled reports**: no report concept existed. `platform.report_definitions` (per-hospital config, Dhis2FacilityConfig shape) + `report_runs` with **UNIQUE (definition, period_token)** â€” the run row is inserted-and-flushed as GENERATING BEFORE generation, so the constraint IS the claim and two sweep instances (no ShedLock) can never email one period twice (deliberately NOT the check-then-act reminder-stamp idiom; deliberately no outer tx so the claim commits before the slow work). Two canned types v1 (ENCOUNTER_ACTIVITY, APPOINTMENT_ACTIVITY), **AGGREGATE-ONLY by design** â€” counts per day, never patient rows, because the delivery channel is an email attachment and email must not carry PHI (the recall-SMS stance applied to the second untrusted channel). commons-csv (on classpath since ever, first use) + EmailService.sendWithAttachment; hourly sweep emits the prior CLOSED period (DAILY yyyy-MM-dd / WEEKLY ISO yyyy-Www / MONTHLY yyyyMM, DHIS token idiom); manual run-now may RETRY a FAILED period (row reuse) but REFUSES a duplicate of a SUCCEEDED one; unparseable token = refusal not guess. `/reports` controller class-level @PreAuthorize(HOSPITAL_ADMIN, SUPER_ADMIN) â€” no SecurityConfig matcher, would fail OPEN â€” + `/reports` portal page (first caller of every endpoint: create modal, run history, run-now, stop/resume) + nav. **25b NEWS2**: patient_vital_signs had 5 of 7 parameters â€” no supplemental-oxygen flag, no consciousness. V130 adds `on_oxygen` + `consciousness_level` (ACVPU incl. NEW_CONFUSION), wired through ALL clinical write paths (vitals mapper, nurse capture, triage DTO+builder â€” the item-18 heightCm lesson applied preemptively) and portal capture (triage form + nurse-station dialog: ACVPU select + Oâ‚‚ checkbox). `NewsScoreCalculator` (RCP 2017 tables, boundary-pinned tests): **partial scores are explicit, never silent** â€” missing parameters are NAMED and flagged incomplete ("the true score can only be equal or higher"), because silent partial under-scores exactly the deteriorating patients the score exists to catch, while refusing to score would hide what IS known. Score computed on READ (mapper â†’ response DTO), never stored â€” no staleness. Write paths auto-flag `clinicallySignificant` at NEWS2 â‰¥5 (the aggregate catches multi-parameter deterioration the legacy per-vital thresholds miss). `NewsScoreProtocolRule` BPA card (V130-seeded NEWS2_EWS protocol row â€” a missing row would silently no-op the rule) fires at MEDIUM/any-single-3, WARNING only (BPA contract forbids CRITICAL), **excludes PATIENT_REPORTED home readings**; patient-detail vitals cards get a band-coloured NEWS2 chip with an explicit Incomplete marker. âš  Deferred: SpOâ‚‚ scale 2 (needs a per-patient hypercapnic care-plan flag that doesn't exist), tiered escalation of NEWSâ‰¥7 through the CriticalValueNotificationService chain (its stamps/rounds live on lab_results â€” a vitals-side escalation ledger is its own migration; v1 surfaces via significant-flag worklists + BPA card), custom report query builder (v1 is canned types), report output download endpoint (email is the delivery channel; no CSV is stored).

*Source audit + full evidence: artifact above. Related work already landed: PR #429 (cross-hospital
link-at-registration), PR #430 (phone-first registration + IKODDI SMS OTP).*

---

# Role-Base Consistency Audit â€” 2026-08-23

Source: 14-agent adversarially-verified audit (workflow `wf_d102eb4a`) run after
the ACCOUNTANT fix (PR #485) â€” every role checked for the same defect classes:
dead nav (visible entry whose route guard 403s), guaranteed-403 dashboard HTTP
calls, matcherâ†”annotation drift, unreachable shipped features. All `broken`
findings were CONFIRMED with file:line evidence on both sides. Verdicts:
clean = RECEPTIONIST, PATIENT Â· degraded = SUPER_ADMIN, HOSPITAL_ADMIN, DOCTOR,
NURSE, LAB_SCIENTIST, LAB_DIRECTOR, QUALITY_MANAGER, STAFF Â· **broken** =
ADMIN, PHYSICIAN, SURGEON, MIDWIFE, LAB_TECHNICIAN, LAB_MANAGER, RADIOLOGIST,
PHARMACIST, PHARMACY_VERIFIER, CLAIMS_REVIEWER, ANESTHESIOLOGIST,
PHYSIOTHERAPIST.

## A. Portal mechanical consistency (one PR, no product decisions) â€” PRIORITY 1

- [x] A1. Guard-mirroring `roles` lists on every permission-only nav item
  (Appointments, Encounters, Admissions, Prescriptions, Consultations,
  Treatment Plans, Referrals, Imaging, Laboratory, Lab Results, Audit Logs) â€”
  generalizes the PR #485 Patients/Billing pattern; kills every dead sidebar
  entry for PHYSICIAN/SURGEON (9 each), RADIOLOGIST (3), ANESTHESIOLOGIST (5),
  PHYSIOTHERAPIST (3), PHARMACY_VERIFIER (1), LAB_DIRECTOR/QUALITY_MANAGER
  (audit-logs) without changing anyone's access.
- [x] A2. Gate the Messages nav item on the backend chat role set (today's
  CHAT_ROLES) â€” 8 staff roles currently open a chat page whose every API call
  403s. (Widening chat itself is decision D4.)
- [x] A3. `canAccessRoute('/appointments')` gate on the dashboard appointments
  fetch (mirror of the PR #485 recent-patients fix) â€” removes the guaranteed
  403 fired on every load for RADIOLOGIST, ANESTHESIOLOGIST, PHYSIOTHERAPIST,
  PHYSICIAN, SURGEON.
- [x] A4. Filter dashboard workflow tiles through `canAccessRoute` (lab view's
  Encounters tile, pharmacist/radiologist Patients+Encounters tiles) and fix
  the pharmacist tiles' stale routes (Dispense â†’ /pharmacy/dispensing,
  Interactions â†’ /pharmacy/drug-interactions, Inventory â†’ /pharmacy/inventory;
  Reports tile removed â€” no page exists).
- [x] A5. Route-guard additions where the BACKEND already admits the role (the
  guard is the only broken layer): /prescriptions + /imaging +
  /treatment-plans + /referrals for ROLE_MIDWIFE; /appointments for ROLE_STAFF;
  /staff for ROLE_QUALITY_MANAGER; /patients (guard + nav mirror) for
  ROLE_LAB_TECHNICIAN. Also drop 'Create Prescriptions' from the midwife static
  map (POST /prescriptions excludes midwife â€” avoid trading dead nav for a
  dead button).
- [x] A6. LAB_TECHNICIAN lands on the lab view: `isLabScientist` includes
  ROLE_LAB_TECHNICIAN (dashboard.ts one-liner; spec drove the flag by hand and
  masked it). LAB_MANAGER gets a flag + lands on the lab view + its own label.
- [x] A7. Missing nav for shipped features: eMAR (roles mirror /emar guard â€”
  the bedside five-rights loop is URL-only today), Pharmacy Claims + Checkout +
  MTM Review (unlocks CLAIMS_REVIEWER's entire purpose), Dispensing + Stock
  Routing for PHARMACY_VERIFIER, super-admin Integration Messages + Cost/
  Chargeback console entries, Scheduling for LAB_SCIENTIST ('View Staff
  Schedules' grant), Imaging for DOCTOR + NURSE ('View Imaging Studies' grant).
- [x] A8. Role labels instead of generic "Staff": LAB_TECHNICIAN, LAB_MANAGER,
  PHARMACY_VERIFIER, CLAIMS_REVIEWER, ANESTHESIOLOGIST, PHYSIOTHERAPIST
  (EN/FR/ES). Remove the duplicate patient "Documents" nav entry.

## B. Backend matcher/annotation alignments (one PR, PR #483 class) â€” PRIORITY 2

- [x] B1. Narrow `GET /staff/scheduling/**` matcher above `/staff/**` carrying
  StaffSchedulingController's role union (STAFF, PHARMACIST, RADIOLOGIST are
  stranded by first-match-wins today).
- [x] B2. DepartmentController GET annotations aligned with the /departments
  matcher's role list (lab roles admitted by the matcher 403 at the
  annotation â€” LAB_MANAGER's Departments page is fully dead).
- [x] B3. `GET /staff/{id}/active` widened to the same read roles as GET /staff
  (staff detail is a dead click for every non-admin role).
- [x] B4. POST /me/alerts/{id}/acknowledge admits NURSE + MIDWIFE (they receive
  actionRequired alerts; the nurse view renders the Ack button for them and it
  silently 403s) + error toast on failure.

## C. Decisions required (blocked on user) â€” PRIORITY 3

- [x] C1. RESOLVED (real-world): ADMIN = back-office ops. ROLE_ADMIN contract: the role is seeded and real but has zero
  permissions in both maps, its /admin landing fires a SUPER_ADMIN-only
  endpoint, and SecurityConfig has no ADMIN matcher anywhere. Define what an
  ADMIN operates (or retire the role).
- [x] C2. RESOLVED (real-world): doctor-equivalent via authority expansion. PHYSICIAN / SURGEON role model: treat as DOCTOR everywhere (shared
  DOCTOR_LIKE_ROLES constant across guards, nav, matchers, annotations) or
  retire/alias to ROLE_DOCTOR at assignment time.
- [x] C3. RESOLVED (real-world): chat open to all authenticated users. Chat scope: open CHAT_ROLES + matcher to all staff roles (pharmacist,
  radiologist, physician, surgeon, anesthesiologist, physiotherapist,
  verifier, claims reviewer) or keep the current list (A2 hides the nav
  either way).
- [x] C4. RESOLVED (real-world): physiotherapist admitted end-to-end. PHYSIOTHERAPIST treatment plans: its core duty is rejected by the
  guard AND every backend endpoint while both permission maps grant it â€”
  admit the role end-to-end or strip the grant.
- [x] C5. RESOLVED (real-world): nurse station is clinical-only, grant removed. Nurse Station for HOSPITAL_ADMIN: nav+guard admit, /nurse/** matcher
  rejects â€” read-only oversight (add matcher roles) or drop the permission.
- [x] C6. RESOLVED (real-world): lab leadership reads audit trail; controller gained its FIRST @PreAuthorize. Audit-log access for LAB_DIRECTOR / QUALITY_MANAGER: guard rejects,
  static maps grant â€” widen the guard + add real @PreAuthorize to
  AuditEventLogController, or remove the grant (A1 hides the entry meanwhile).
- [x] C7. RESOLVED (real-world): ROLE_STAFF (+PHARMACY_VERIFIER, CLAIMS_REVIEWER) seeded. ROLE_STAFF: not seeded, no backend defaults â€” seed it or document as
  inheritance-only pseudo-role. Its lab-order read grant is portal-unreachable.

## D. Follow-ups (after Aâ€“C)

- [x] D1. Fallback-view work cards: dispense queue for PHARMACY_VERIFIER,
  claims list for CLAIMS_REVIEWER. DONE 2026-08-23 (**PR #496**). Both
  endpoints already admitted the role (DispenseController work-queue lists
  PHARMACY_VERIFIER, PharmacyClaimController's CLAIMS_ROLES lists
  CLAIMS_REVIEWER) - the roles simply had a sidebar entry and nothing to start
  from on the dashboard. Counts read with size=1 so a total costs one row, not
  a page of rows nothing renders.
- [x] D2. Pharmacist / radiologist / lab stat strips. DONE 2026-08-23
  (**PR #496**). NINE hardcoded em-dashes across the three views; seven now
  carry real numbers and TWO CARDS WERE REMOVED rather than left as permanent
  placeholders - DISPENSED_TODAY and REPORTED_TODAY have no data source (no
  endpoint returns dispenses for today, and the imaging order projection
  carries no report timestamp). A dash that can never fill is a claim the
  product makes and cannot keep; deleting it is the honest fix, and the
  numbers can come back with the endpoints that would feed them.
  Lab strip needed a backend change: /dashboard/lab-ops/summary excluded
  LAB_SCIENTIST and LAB_TECHNICIAN - the exact roles the lab VIEW is built
  for - so the only summary endpoint available to that view 403'd its own
  audience. Widened (aggregate counts for the caller's hospital, no PHI).
  Radiologist counts are derived client-side from the hospital-wide imaging
  order list: pending = ORDERED+SCHEDULED, awaiting-report = COMPLETED (the
  gap before RESULTS_AVAILABLE). No imaging dashboard summary endpoint exists
  and two honest counts beat three invented ones.
- [x] D3. `findRouteRecursive` cannot resolve nested paths, so canAccessRoute
  passes stale nested links (the dead PHYSICIAN 'Register Patient' hero
  action) â€” walk path segments/children. DONE 2026-08-23 (**PR #489**).
  Replaced with `collectRouteRoles`, which consumes path SEGMENTS and handles
  all three shapes the table uses: multi-segment entries declared in one route
  ('pharmacy/dispensing'), empty-path wrappers that consume none, and ':param'
  segments. It returns the `data.roles` of EVERY route on the matched chain,
  not just the leaf - Angular activates a child only after each ancestor guard
  passes, so `/patients/new` inherits the `/patients` gate. An unresolvable
  route is now reported INACCESSIBLE rather than open: a tile pointing at a
  route the table cannot match is a dead click, and the old permissive default
  is exactly what let stale links through. Specs now run against the REAL route
  table (a stub config proved nothing), plus a guard test asserting every route
  literal the dashboard links to still resolves - so a renamed route fails
  loudly here instead of silently emptying a role's dashboard.

- [x] D4. Reconcile DashboardConfigService per-role defaults with SecurityConfig
  matchers (roles granted 'View Lab'/'View Patient Records' the matchers 403).
  DONE 2026-08-23 (**PR #489**), resolved real-world: where the product backs
  the claim, fix the enforcement; where it does not, drop the claim.
  * FIXED (real breakage): the shared `<app-patient-picker>` lives under
    `/patients/**`, and RADIOLOGIST (/imaging) + PHYSIOTHERAPIST
    (/treatment-plans) reach those pages through their route guards while
    `/patients/search` + `/patients/lookup` rejected them - a dead search box on
    two shipped pages. Both now admitted at the matcher and in a new
    PATIENT_PICKER_ROLES constant, joining PHARMACIST who was already there for
    exactly this reason. The physiotherapist case means audit decision C4 had
    admitted the role to treatment plans without the picker it needs.
  * DROPPED (false claim): 'View Patient Records' for RADIOLOGIST,
    ANESTHESIOLOGIST and PHYSIOTHERAPIST in BOTH permission maps, plus
    'Update Patient Records' for the latter two. The /patients page is not
    wired for these roles - its vitals, encounters, appointments and
    record-sharing panels each 403 - so admitting them to the chart would have
    traded one 403 for four. Mirrors the ACCOUNTANT / BILLING_SPECIALIST
    precedent. Giving them the chart properly is D7.
  * The four patient-read annotations are folded into PATIENT_READ_ROLES /
    PATIENT_PICKER_ROLES (S1192); list and detail had drifted into two
    hand-maintained copies of one set. A new drift guard pins both, and pins
    that picker-only roles do NOT get the chart.
  DEFERRED, deliberately: lab read (D5), imaging read (D6), chart access (D7).
- [x] D5. Lab read for RADIOLOGIST + ANESTHESIOLOGIST.
  DONE 2026-08-23 (**PR #495**), resolved real-world: they read labs from the
  PATIENT'S RECORD, not from /lab. The open question was whether read-only
  clinicians belong in an order-entry workbench; the answer is no. /lab is the
  queue the lab team works, and a radiologist checking renal function before
  contrast opens the patient, not the lab's worklist. Chart Review already
  carries a RESULTS section fed by the same LabResult queries, hospital-scoped
  with no per-endpoint role gate - so the fix was ChartReviewController's role
  list, not the ~7 LabOrder/LabResult annotations D4 feared. The /lab and
  /lab-results guards are deliberately unchanged.
- [x] D6. Imaging read for ANESTHESIOLOGIST.
  DONE 2026-08-23 (**PR #495**), same resolution and same one-line surface:
  Chart Review's IMAGING section. ImagingOrderController and the /imaging
  route guard are deliberately unchanged - /imaging is the radiology
  worklist, not a chart view.

  D5+D6 also exposed a LOOSE END IN D7: widening the five backend reads was
  necessary but not sufficient, because the chart TAB gates use a different
  vocabulary. Two of them gated a READ tab on a WRITE permission -
  canViewVitals on 'Update Vital Signs' and canViewEncounters on 'Create
  Encounters' - which is the exact trap canViewGrowth's own comment documents.
  So D7 widened the vitals endpoints while the vitals TAB stayed hidden from
  every read-only role. Both now mirror their controller's read list
  (VITALS_VIEW_ROLES / ENCOUNTER_VIEW_ROLES in chart-access.ts), and Chart
  Review got its own CHART_REVIEW_VIEW_ROLES because its backend list is
  broader than the encounters one - sharing a flag hid that the lab and
  pharmacy roles read the record without being able to open encounters.
  LESSON: when widening a backend read, grep the PORTAL gate too; a role list
  and a permission string are different vocabularies and drift silently.
- [x] D7. Patient chart for the treating clinicians who are NOT on the care
  team's core loop: RADIOLOGIST, ANESTHESIOLOGIST, PHYSIOTHERAPIST.
  DONE 2026-08-23 (**PR #492**), resolved real-world: in a real hospital all
  three read the chart, so the product now lets them.
  The reason D4 refused to do it in one line: the chart page is not one
  endpoint. Opening it fans out to FIVE independently guarded reads - the
  patient, vitals, encounters, appointments, and the caller's hospital scope
  (/me/hospital, which every authenticated page resolves). Admitting the route
  alone would have moved the 403 from the route to four empty panels, which is
  worse because the page then LOOKS available. All five moved together.
  Read-only, deliberately: recording vitals, opening encounters and booking
  appointments stay with the treating team. Two of those writes were sharing a
  role literal with a read endpoint, so widening the read would have silently
  handed them the write - each now carries its own named constant
  (ENCOUNTER_CREATE_ROLES, APPOINTMENT_BOOK_ROLES) and a test asserts the
  separation holds. `ConsultingClinicianChartAccessTest` pins all five layers
  as a set so a future change cannot widen one and forget the rest.
  'View Patient Records' is restored in both permission maps - D4 had removed
  it precisely because the grant was a promise the panels could not keep.

---

# Epic Parity Tier 2 — 2026-08-24

> Successor to the **Epic Parity Gap Tasklist — 2026-08-20** (items 1–25, all closed
> and promoted). Source audit: <https://claude.ai/code/artifact/a2d071f3-8b46-49a6-b2f1-10ad85ae87f2>
> — but that artifact is now **stale as a document**, so every item below was
> re-verified by search against `develop @ 30f07e8f` rather than taken from it.
> One item = one PR into develop unless noted. Next free migration: **V139**
> (V132–V138 consumed by items 26, 27, 28, 29, 32, 30 and 34 in that order).
> **Never stack PRs**: branch every one off develop and pre-allocate the
> migration number. Four strands were lost to stacking; #509 had to be
> re-cut as #512. And after ANY merge into a branch carrying a migration or
> i18n, diff registered-vs-disk changesets and count keys per locale by hand
> — `changelog.xml` and `assets/i18n/*.json` are pure append points, so git
> resolves a collision as EITHER/OR and a clean auto-merge is not proof.

**What the ledger's own "if the goal is closing the gaps that matter" top-8 became:**
all eight shipped — synthetic surfaces (#431/#432), bed decision (#433),
L&D (#437), turn-on-what's-built (#438/#439/#457), slot inventory (#459/#467),
critical values (#434/#451/#462), web cancel/reschedule + proxy (#440),
patient education (#441). Since then PRs #472 microbiology, #473 note co-sign,
#474 registration extras, #475 downtime + printing, #476 recalls/waitlist,
#477 bulk export and #478 report builder + NEWS2 closed most of what the
ledger listed as absent. **Anything the artifact still shows red should be
checked against this list before it is believed.**

## E0 — A loop that cannot close (clinical truth)

- [x] 26. **Radiology reading room — imaging reports can never be created.**
  ✅ DONE 2026-08-24 (**PR #508** `feature/imaging-report-authoring`, V132).
  `POST /imaging/results` + `PUT /{id}` (content only) + `POST /{id}/sign`
  (the only path to FINAL: server identity, server clock, SHA-256 digest,
  re-sign refused, locks the report) + a study with a signed read accepting
  only ADDENDUM/CORRECTED/AMENDED. Signing promotes the order to
  RESULTS_AVAILABLE. The request DTO lost `signedByStaffId`, `signedAt`,
  `criticalResultAckByStaffId`, `criticalResultAcknowledgedAt`,
  `reportVersion`, `latestVersion` and `lockedForEditing` — all
  client-assertable before. FOUR defects fixed in the same surface:
  (1) `acknowledge-critical` could NEVER succeed (forwarded a status payload
  with no status → 400 every time; never touched the acknowledgement columns;
  took the clinician as a query param); (2) NO tenant guard anywhere — bare
  `findById` on every read and write, and `getReportsByHospital` trusted a
  path variable, so any authenticated caller could read or rewrite a foreign
  hospital's report by UUID (now 404-not-403, #483's stance); (3)
  `ResourceNotFoundException` was passed prose instead of a message key, so
  every 404 rendered as `[Missing translation] ...` (#490's defect class);
  (4) the mapper blind-copied `signedAt`/`criticalResultAcknowledgedAt` from
  the request, so an update omitting them NULLED a server-stamped signature.
  `updateReportStatus` is now administrative only (CANCELLED/ERROR, reason
  mandatory, signed reports refused) — FINAL there would have been #463's
  TRANSMITTED hole again. Permissions `CREATE_RADIOLOGY_REPORTS` /
  `SIGN_IMAGING_REPORTS` already existed in PermissionCatalog, granted to
  RADIOLOGY_OPERATIONS, never built against; class-level `@PreAuthorize`
  because there is no `/imaging/**` matcher. Portal: authoring modal, sign +
  digest chip, read-only once signed, 23 i18n keys ×3. Original finding:
  ⚠ NEW FINDING, not in the 2026-08-20 audit (which recorded only "no
  report-authoring UI"). The truth is worse: `ImagingReportService.createReport`
  and `updateReport` have **zero production callers**. The only references
  anywhere are the interface, `ImagingReportServiceImpl`, and
  `ImagingReportServiceImplTest`. `ImagingResultController` exposes reads,
  `PUT /{reportId}/status`, and `acknowledge-critical` — no create, no update.
  `ImagingReportUpsertRequestDTO` is referenced by nothing but its own mapper
  and those same files. So an imaging order is placed, the radiologist has no
  path to enter findings, and `/imaging`'s Results view is a worklist over rows
  that cannot exist. This is the built-but-unreachable defect class in its
  purest form and it breaks a whole clinical loop.
  Scope: `POST`/`PUT` endpoints (mirror the microbiology root-path decision —
  a `POST /imaging/**` matcher may 403 the radiology roles first-match, check
  before choosing the path), a radiologist authoring surface (technique /
  comparison / findings / impression), and the PRELIMINARY→FINAL→ADDENDUM
  lifecycle plus version demotion the impl **already implements** and nothing
  can reach. Attachments go through the authenticated document path (#482
  precedent), never `/uploads/**`.
- [x] 27. **Critical imaging findings have no escalation loop.**
  ✅ DONE 2026-08-25 (**PR #512** `feature/imaging-critical-escalation`, V133).
  Originally opened as #509 stacked on #508; it merged into the feature base
  AFTER #508 had already reached develop, so none of its content landed. #512
  is the re-target into develop. Fourth strand of its kind — stacking is now
  banned outright rather than mitigated.
  `ImagingCriticalNotificationService` + `ImagingCriticalEscalationScheduler`,
  modelled directly on the lab pair so the two behave identically when a
  clinician meets them. Notify-on-flag hooks into all three authoring paths
  (create / revise / sign), idempotent on `criticalNotifiedAt` so repeated
  saves raise exactly one first alert. The sweep **repeats** (no round cap)
  and **widens** to hospital admins from round 2 with the ordering provider
  staying on the list — both copied deliberately, because the lab loop only
  acquired them after the 2026-08-21 reassessment found a one-shot escalation
  that re-notified the same person and then went permanently silent. Manual
  trigger `POST /imaging/results/critical-escalation/run` (the lab twin).
  V133 gives imaging its OWN ledger columns — the lab stamps live on
  `lab.lab_results`, the same reason V130 deferred tiering vitals through
  that service. NO read-back column, deliberately: a read-back catches
  mis-transcription of a NUMBER relayed by phone, and an imaging impression
  is prose read in the chart — the radiology analogue ("communicated to Dr X
  at HH:MM") is already `criticalResultAcknowledgedBy`/`...At` from item 26.
  SMS carries a truncated impression, never full findings (the recall-SMS
  stance). Portal: the critical banner now shows who was called and how many
  rounds have fired. Original finding:
  `acknowledgeCriticalResult` exists; nothing notifies anyone that there is
  something to acknowledge. Lab has the full notify → read-back → timer →
  widen chain (V109/V116, `CriticalValueNotificationService`); imaging has the
  flag and the acknowledge button and nothing between them. Note the shape
  problem the NEWS2 deferral already hit: the escalation stamps/rounds live on
  `lab.lab_results`, so imaging needs its own ledger columns rather than a
  reused one. Depends on #26 (a report has to exist before a finding can).

## E1 — The specialty's own emergencies

The ledger's own verdict is that this is "a maternal-newborn EHR with
generalist modules". Both items below are the missing halves of workflows the
OB suite already models.

- [x] 28. **Blood bank / transfusion.**
  ✅ DONE 2026-08-25 (**PR #510** `feature/blood-bank-transfusion`, V134).
  Six tables, the type & screen → request → crossmatch → issue →
  administration → reaction chain, and `AboGroup.isCompatible` as the safety
  core with 43 tests covering all 16 ABO pairs in **both** directions —
  because the rule inverts between components: red cells carry antigens (O
  universal donor) while plasma carries antibodies (AB universal donor).
  ⚠️ **Platelet compatibility is a POLICY CHOICE awaiting a haematologist's
  sign-off** — the conservative reading (plasma-side ABO + Rh) is implemented;
  it is not a clinical fact and should be confirmed before go-live.
  Original finding: verified zero code: `Transfusion`,
  `BloodBank`, `CrossMatch` return nothing; `bloodProductsRequired` is one
  boolean on `ProcedureOrder` and that is the entire footprint. #437 shipped
  the partograph with PPH alerts — and postpartum haemorrhage is the leading
  cause of maternal death — so the product raises the alarm and then has
  nowhere to record the intervention. Scope v1: ABO/Rh + antibody screen on the
  patient record, transfusion request → crossmatch → unit issue →
  administration verified through the **existing** five-rights barcode service
  → transfusion-reaction report. Deliberately NOT donor recruitment or a donor
  inventory chain: that is a blood-bank LIS, not an EHR.
- [x] 29. **Death & mortality workflow.**
  ✅ DONE 2026-08-25 (**PR #511** `feature/death-mortality`, V135).
  `clinical.patients.deceased_at` (the flag every sweep reads) plus
  `clinical.death_records` (the certificate, one per patient). Recording a
  death cascades: closes admissions — the first-ever writer of
  `AdmissionStatus.DECEASED`, which had existed since V1 with no writer —
  closes encounters, cancels **future-only** appointments (a past one the
  patient attended is history), and closes recalls. The reminder and recall
  sweeps now exclude the deceased, which is what stops the SMS to the family.
  `isWhoMaternalDeath()` excludes LATE_MATERNAL both in Java and in the JPQL
  feeding DHIS2: 42 days to a year falls OUTSIDE the WHO definition, and
  counting it in would overstate the ratio the facility is judged on.
  Deferred: deceased banner on the chart, certificate PDF, DHIS2 export
  mapping. Original finding: verified zero code: no `dateOfDeath`,
  no `DeathRecord`, no deceased state anywhere. A patient who dies stays ACTIVE
  — open encounters, live appointments, and recall/reminder sweeps that will
  cheerfully SMS the family. It also blocks the maternal and perinatal
  mortality indicators the DHIS2 ADX export otherwise exists to report. Scope:
  death record (datetime, place, immediate + underlying cause ICD-10,
  certifier), a state transition that closes admissions/encounters/appointments
  and stops every outreach sweep, and maternal/perinatal death flags feeding the
  reporting tier.

## E2 — Inpatient logistics remainder (unblocked by #433)

- [x] 30. **In-app transfer orders (bed→bed, ward→ward).**
  ✅ DONE 2026-08-25 (**PR #515** `feature/transfer-orders`, V137;
  corrected by **PR #516**).
  `clinical.transfer_orders` + `/transfers` (request / complete / cancel /
  pending / per-admission history). **Two steps, because the person who
  orders a move is not the person who makes it**: `REQUESTED` holds the
  destination as `RESERVED` so the ward clerk cannot allocate it to somebody
  else in the interval — that hold is the entire reason the order is worth
  having rather than a log written afterwards. Two partial unique indexes
  (`WHERE status = 'REQUESTED'`) stop a bed being promised twice and stop one
  admission having two live orders. `from_bed_id`/`from_ward_id` are a
  **snapshot, not a join**: read back off the admission later they would show
  where the patient is NOW, so for a completed transfer "where did they come
  from" would answer itself with the destination. Isolation is **recorded,
  not prevented** — airborne (V136) into a non-isolation ward is refused
  unless overridden with a reason, because refusing outright pushes the
  decision outside the system where nothing records it. Every bed-status
  write still goes through `BedAssignmentService`, which gained `reserveBed`
  / `releaseReservation` and a `Set<BedStatus>` overload so the completing
  transfer can claim the bed it reserved while ordinary assignment still
  refuses a RESERVED one. Two defects found after merge and fixed in #516:
  (1) `.formatted` binds tighter than `+`, so it applied to the SECOND
  literal of a concatenated refusal and the clinician was told "bed %s is not
  in an isolation ward" — the test asserted on text from the first literal
  and missed it; (2) both `assignBed` overloads called each other through
  `this`, so the `@Transactional` proxy was bypassed on the delegating path
  (now a private `doAssign`). #516 also added the seven `BedAssignmentService`
  tests that should have existed with #515: `TransferServiceImplTest` mocks
  that class, so the reservation transitions had **no direct test at all** —
  including that cancelling a stale transfer must not evict a patient who has
  since been put in the destination. Original finding: verified zero code:
  `TransferOrder`, `transferPatient`, `InternalTransfer` all empty. Transfers
  exist ONLY as inbound HL7 A02. `BedAssignmentService` already owns the
  `Admission.bed` ↔ `Bed.status` invariant, so this is an orchestration +
  audit layer over an invariant that already holds — not new schema risk.
- [x] 31. **Bed board / census.**
  ✅ DONE 2026-08-25 (**PR #514** `feature/isolation-and-bed-board`, no
  migration).
  `GET /bed-board` → ward → room → bed with occupant, length of stay,
  expected discharge, attending, and the isolation flags from #32. Three
  queries joined in memory rather than one wide join, so a ward with no
  admissions still renders its empty beds. **The census reports its own
  disagreement instead of hiding it.** Inpatients are counted from
  `admissions`, occupied beds from `Bed.status` — two independently written
  numbers that should agree — and `orphanedOccupiedBeds` surfaces
  `max(0, occupied − inpatients)` on the board rather than silently
  preferring one source. A bed left OCCUPIED after a discharge is a bed the
  clerk cannot allocate and nobody can see is wrong; that was exactly the
  pre-#433 failure and the board now names it. `isolationMismatch` marks a
  patient on airborne precautions sitting in a ward that cannot contain
  them. Sonar caught a real MAJOR here (`java:S8700`): length of stay used
  `Duration.between` on two `LocalDateTime`s, which ignores DST and could
  report a 3-day stay as 2 — the house `utility/ElapsedTime` exists for
  precisely this and I had reached past it. Portal: `/bed-board` with ward
  and availability filters, guarded **wider** than `/bed-management` because
  the people who need to read a board are not the people who administer
  wards. 71 BED_BOARD i18n keys ×3. Original finding:
  #433 gave beds real writers; what is still
  missing is the ward-level board (grid by ward → room → bed with occupant,
  isolation flag, expected discharge) and a census number the admin dashboard
  can trust. Check what the existing occupancy tiles show post-#433 before
  scoping — they were built against the orphan schema.
- [x] 32. **Isolation precautions.**
  ✅ DONE 2026-08-25 (**PR #514** `feature/isolation-and-bed-board`, V136).
  `clinical.isolation_precautions` + `/isolation/precautions` (order /
  discontinue / active / history). CONTACT, DROPLET, AIRBORNE, PROTECTIVE.
  **Its own table, not `Admission.metadata`** — whose column comment
  suggested exactly this use and is the wrong home: a precaution has to be
  queryable (the board filters on it), indexable (the board loads a whole
  ward at once), constrained (a closed clinical vocabulary, not free text)
  and auditable (who ordered it, who stopped it, when). **A child table, not
  an enum column**, because concurrent precautions are normal — a viral
  haemorrhagic fever is contact AND droplet, a neutropenic patient on
  protective isolation may also be on contact for a colonising organism, and
  collapsing that to one value forces a clinician to choose which risk to
  under-communicate. Active is `ended_at IS NULL`; precautions are
  discontinued, never deleted, so partial indexes carry the active reads and
  a unique partial index stops the same type being ordered twice on one
  patient. Only `AIRBORNE` returns true from `requiresIsolationWard()` —
  contact and droplet are practice, not placement — and PROTECTIVE inverts
  the direction entirely: it shields the patient FROM the ward, so it must
  never be read as a containment requirement. That asymmetry is why the check
  is a method on the enum rather than a set membership test at each call
  site. Original finding: verified zero code (every `Isolation` hit
  is `TenantIsolationMode` or social history). For TB, cholera, Lassa and
  measles this is a storyboard banner + bed-board flag + a nursing-task
  modifier — a cross-cutting attribute, not a module. Pairs naturally with #31.

## E3 — Safety gates that are absent, not merely unenforced

- [x] 33. **Pharmacist verification gate before eMAR.**
  ✅ DONE 2026-08-26 (**PR #524** `feature/pharmacist-verification`, V139).
  Verified zero code before starting (`pharmacistVerified`,
  `verifiedByPharmacist` — nothing), so a SIGNED prescription was immediately
  administrable and the step between prescriber and nurse did not exist.
  **The decision the ⚠ asked for was made and it is SCOPED, not universal:**
  the gate blocks only controlled substances and prescriptions already flagged
  `requiresCosign`; everything else administers exactly as before with an
  advisory marker. A universal gate needs a dispensary pharmacist reachable
  whenever a dose falls due, and nothing here models dispensary staffing or
  cover — so it could never fail open, and with one dispensary it would block
  every night-time dose. (Community *pharmacies de garde* run a real rotating
  roster, but they serve the public and do not verify an inpatient MAR — noted
  in the migration so a later reader does not mistake them for a reason to
  widen the gate.) **Verification does not survive an edit** — `update` has no
  status guard, so drug/dose/frequency stay mutable on a SIGNED row, and a
  stamp that outlived that would assert a check nobody performed. **Only GIVEN
  is blocked**: HELD, REFUSED and MISSED always record, because they are facts
  about the patient and holding the dose is the right action when it cannot be
  verified. Portal half: verify ceremony on the prescriptions page (modal with
  the optional note, so the note is reachable rather than a field nothing
  sends), pending/verified state in the detail panel, and the eMAR refuses
  GIVEN with the reason stated **before** the scan steps and a chip in the
  queue. **No override on the eMAR side** — the server refuses regardless, so
  an override box would only manufacture a reason for a certain refusal.
  ⚠ Found on the way in: `ROLE_PHARMACY_VERIFIER` is on the backend endpoint
  and seeded since V43, but the `prescriptions` route guard rejected it — the
  role that exists to do this job could not reach the page it is done on.
  Fixed in the same PR.
- [x] 34. **Dispense-time barcode verification.**
  ✅ DONE 2026-08-25 (**PR #518** `feature/dispense-verification`, V138).
  `DispenseVerificationService` mirroring `FiveRightsVerificationService`, so
  the two ends of one medication chain read the same way to whoever audits
  them. **Three checks, not five** — dose, route and time are administration
  questions the eMAR already owns; what a pharmacist can verify handing a
  pack across a counter is who it is for, that it is the right drug, and
  that it is fit to give. **The scan is optional, the server checks are
  not**: requiring a scanner would take every paper-fallback site offline
  rather than make it safer, so expiry and drug-match — both answerable from
  the lot the pharmacist already named — run on every dispense regardless.
  Overrides: EXPIRY and PATIENT never; DRUG only as a recorded substitution,
  reusing the `substitution`/`substitutionReason` the request already models
  rather than inventing a second override with its own audit event (a bare
  `substitution=true` with no reason is not an override). Load is split from
  consume so a refusal happens BEFORE stock moves. VERIFIED is keyed on a
  scan having HAPPENED, not on the checks having passed — the checks run
  every time, so letting them alone stamp VERIFIED would put it on nearly
  every row and it would stop meaning anything.
  ⚠️ **The original finding understated this badly. FOUR defects, three of
  them pre-existing:** (1) `StockLot.expiryDate` was written at goods-in and
  **read by nothing anywhere in the application** — `findAvailableLotsByFEFO`
  filters `expiry_date >= CURRENT_DATE` and the dispense path called
  `findById` straight past it, so a lot two years out of date dispensed
  normally; (2) the lot's drug was never compared to the prescription's, only
  its pharmacy; (3) `medicationName` was free text on the request flowing
  into the row, the audit entry and the patient's ready-for-pickup SMS; and
  (4) **the lot picker never worked** — it listed inventory items and bound
  their ids to `stockLotId`, so the backend 404'd every time. That fourth one
  is why the first three went unnoticed: in practice every dispense went
  through with no lot, so no stock decrement either, and the code path that
  would have exposed them was unreachable. Also **the claimed scan target
  only half existed**: #475's wristband is the patient half, but there is no
  medication barcode anywhere and no medication label printed. V138 mints one
  per LOT (not per catalogue item — the lot is what gets picked up and what
  carries the expiry), server-side rather than a manufacturer GTIN, because
  stock arrives from government allocation, donation and local purchase so
  barcodes are inconsistent where present at all. Same shape as
  `lab.lab_specimens.barcode_value`, through the same label renderer;
  `GET /pharmacy/stock-lots/{id}/label.pdf` backfills pre-V138 lots on first
  print. Original finding: The five-rights scan is
  server-authoritative and fail-closed at the MAR; dispensing has no scan at
  all. #475's wristband and specimen-label printing means the scan targets now
  physically exist.

## E4 — Population health, on this product's terms

Epic calls it Healthy Planet; here it is defaulter tracing and programme
cohorts. The DHIS2 ADX export is already wired, so these feed a reporting path
that exists rather than inventing one.

- [ ] 35. **Disease registries / cohorts** — HIV, TB, malaria, hypertension,
  diabetes, ANC. Enrolment + status + programme visit cadence.
- [ ] 36. **Care-gap worklist + defaulter tracing.** A care gap is structurally
  the same row as a recall with a rule behind it instead of a clinician, and
  `PatientOutreachNotifier` (#476) is already the transport with preference
  and SMS-guard handling solved.
- [ ] 37. **Panel management** — provider / CHW panels, empanelment.
  Verified zero code for all three (`CareGap`, `QualityMeasure`,
  `PanelManagement`, `Cohort`, `Readmission` all empty).

## E5 — Records, identity, HIM

- [x] 38. **Demographics depth** — ethnicity, preferred language, address
  history. ✅ DONE 2026-09-03 (`feat/demographics-depth`, V150).
  The entry was one-third stale (the item-39 lesson again): the LANGUAGE
  third had already shipped — `PatientLanguage` (fr/en/es + bm/dyu/mos with
  `hasMessageBundle`) and `PatientLocaleResolver`, read by the appointment,
  recall and reception dispatch paths; a Mooré/Dioula/Bambara SMS still
  needs a commissioned bundle, which is a translation task, not code.
  What V150 added: `clinical.patients.ethnicity` — self-reported FREE TEXT
  on purpose (an enum of ethnic groups would be the schema inventing a
  taxonomy; V146 cadence stance) — and `clinical.patient_address_history`,
  written by both patient-update paths only when the address components
  actually change (first fill-in of a blank address and re-statements
  record nothing; the composed `address` string is derived formatting and
  deliberately not part of the comparison). Read surface:
  GET /patients/{id}/address-history + a lazy expander on the chart's
  demographics card; ethnicity on the registration form and chart.
- [x] 39. **Disclosure accounting.**
  ✅ DONE 2026-08-26 (**PR #528** `feature/disclosure-accounting`, V141).
  The entry said "the patient-facing report over that existing ledger" and
  "verified zero code". Both were wrong, and in the direction that matters:
  **the report already shipped and was already reachable** —
  `/me/patient/access-log` → `my-sharing` → "Who Viewed My Records", behind
  a `ROLE_PATIENT` guard. It was broken in four separate ways at once, and
  each one on its own would have been enough to make it untrustworthy.
  ⚠ **THE LEDGER COULD NOT BE QUERIED BY PATIENT.** The list asked for
  `(entityType='PATIENT', resourceId=patientId)` — a *convention*, which
  only three of the six emitters that write patient-related audit rows
  follow. Break-the-glass keys on the **session id**; eligibility checks key
  on the **check id**. So the page a patient opens to find out who read
  their chart **omitted every emergency override** — the one category it
  exists for — and every disclosure to an insurer, with nothing saying the
  list was partial. V141 adds the `patient_id` column the query should
  always have had, and backfills the three derivable shapes (including
  historic break-glass, by joining the session table). No FK: an audit row
  must outlive a purged patient.
  ⚠ **THE RELEASE AUDIT NAMED THE PATIENT AS THE DISCLOSER.**
  `PatientRecordSharingServiceImpl` built its `RECORD_SHARE` row with
  `.user(patient.getUser())` and had no view of the security context at all,
  so the patient's own portal told them *they* had shared their chart with
  another hospital. Where the patient has no portal account — the common
  case here — `getUser()` is null, `deriveActorFields()` stamps
  `actorType=SYSTEM`, and a cross-hospital release of a full chart recorded
  **no human at all**. Now resolved from `RoleValidator`; null stays null
  rather than being substituted, because naming the wrong person is worse
  than naming nobody — only one of the two is legible as a gap.
  ⚠ **EVERY ROW RENDERED BLANK.** The Angular `AccessLogEntry` interface
  (`accessedBy`, `accessType`, `accessedAt`…) shared **not one field name**
  with the `AccessLogEntryDTO` on the wire (`actor`, `eventType`,
  `timestamp`…), and the DTO never set `id`, so `track entry.id` tracked
  `undefined`. The sole spec stubbed `getMyAccessLog: () => of([])` — it
  only ever exercised the empty state, which is precisely why this survived.
  ⚠ **AND THE AUDIT SERVICE SILENTLY DROPPED WRITES.** Pre-existing, found
  by the new tests: `resolvePatientResourceName` called a bare
  `UUID.fromString(resourceId)`, so a `PATIENT`-typed event whose resource id
  wasn't a UUID threw — and `logEvent` catches and swallows, so **the audit
  row vanished entirely**. An audit trail that discards the writes it cannot
  label is the worst failure mode it has. Now guarded: lose the key, never
  the row.
  `DisclosureCategory` is a **whitelist, not a fallthrough** —
  `accountableEventTypes()` is what the query filters on, so keying a new
  event type by patient cannot publish it to patients without someone
  deciding to; a test pins the switch and the whitelist together in both
  directions. It takes the **entity type as well as the event type**, because
  `PATIENT_ACCESS` means a clinician opening the chart in one emitter and a
  disclosure to an insurance scheme in another, and those must not share a
  label. The portal's `catchError(() => of([]))` is gone: an outage used to
  render as *"Nobody has accessed your records yet"*, an affirmatively false
  statement about a privacy-critical fact.
- [x] 39b. **Release of information — request workflow.**
  ✅ DONE 2026-09-04 (`feat/roi-requests`, V151). The other half of the
  original #39, wired into it by construction: fulfilment emits a
  PATIENT_EXPORT audit row keyed by patient, which the V141 whitelist
  classifies as COPY_RELEASED — every fulfilled request lands on the
  patient's own "Who Viewed My Records" with no further wiring; denials
  stay off it (nothing was disclosed). `clinical.roi_requests` (encrypted
  requester/purpose/scope/decision narrative; @Version per the #549
  concurrency lesson; NO delete cascade — a request is a record of an
  exchange with an outside party and must survive scrutiny). Intake under
  /patients/{id}/roi-requests (the desk logs paper requests; RECEPTIONIST
  in), triage worklist + fulfil/deny/cancel under /roi-requests (decisions
  tightened to DOCTOR/HOSPITAL_ADMIN/SUPER_ADMIN; deny REQUIRES the
  reason — it is the outcome the requester is told). Patients file and
  track their own via /me/patient/roi-requests. Portal /roi page:
  status-tabbed queue oldest-first, intake on the shared picker, scope
  chip for super-admins.
- [x] 40. **Provider credentialing renewal.**
  ✅ DONE 2026-08-26 (**PR #525** `feature/provider-credentialing`, V140).
  The gap was worse than "nothing verifies": `license_number` and
  `license_expiry_date` have been on `hospital.staff` since V1,
  `StaffRepository` has a query literally named *"MVP 19: License expiry
  alerts"*, and `HospitalAdminDashboardServiceImpl` already graded each result
  EXPIRED / CRITICAL / WARNING. **The alert existed and had no delivery and no
  action** — an administrator learned a doctor's licence had expired by
  opening a dashboard page and noticing, which in practice means learning it
  when somebody else did. **A HISTORY TABLE, deliberately the opposite call
  from V139's three-columns-no-child-table:** a licence is renewed repeatedly,
  and the question after an incident is not "is this clinician licensed now"
  but *"was this clinician licensed on the day they prescribed that"* — only a
  history answers it, and overwriting the expiry in place destroys the
  evidence that somebody practised past theirs. **`license_alert_stage` keeps
  the alert an alert**: the nightly sweep fires only when the grade *advances*
  (nothing → WARNING → CRITICAL → EXPIRED), because a daily re-notification
  trains an administrator to filter the category and the one that mattered
  goes with it. Renewal clears the stage. Notifies the practitioner as well as
  the administrators — the practitioner is the only person who can actually
  obtain the renewal. **Scheduler defaults ON** (`matchIfMissing = true`);
  shipping the fix off-by-default would have reproduced the exact finding one
  config key further away. ✅ **DECIDED 2026-08-26 — an expired licence does
  NOT block clinical work, and that is the chosen behaviour, not an unfinished
  edge.** It still prescribes, signs and logs in. An administrator who forgets
  to enter a renewal would otherwise take a working doctor offline mid-shift
  in a hospital that may have one, and that failure costs more than a lapsed
  date going unenforced by software. Recorded in
  `CredentialRenewalService`'s javadoc; do not add a block without a new
  decision. Thresholds moved out of the dashboard's inline if/else into
  `LicenseAlertStage` so the sweep and the screen cannot drift.
- [x] 41. **HL7 A40 patient-merge inbound.**
  ✅ DONE 2026-08-26 (**PR #527** `feature/hl7-a40-patient-merge`, no migration).
  The merge service, alias reassignment and PATIENT_MERGE audit did already
  exist from #439/#449, so this is the inbound trigger — but it is **not** the
  thin adapter the entry implied, and the reason is a security one.
  ⚠ **EVERY EMPI TENANT GUARD IS A NO-OP ON AN MLLP THREAD.**
  `EmpiServiceImpl.isVisibleToCaller` resolves the caller's hospital from the
  security context and treats a **null** active hospital as "unscoped, allow";
  `requirePatientInTenant` returns early on the same null. There is no security
  context on an MLLP worker thread, so wiring A40 straight through to
  `mergePatients` would have handed an allowlisted sender the ability to merge
  **any two patients in the system** — the allowlist decides which sender may
  connect, nothing would have decided which patients they may merge. So
  `MllpInboundMergeServiceImpl` enforces the boundary itself: **BOTH** patients
  must be registered at the receiving hospital. Both, not just the survivor —
  merging a stranger's record INTO a local patient is as damaging as the
  reverse. **Direction is the other risk:** in an A40 the PID survives and MRG-1
  is retired; backwards merges away the patient that was meant to survive, and
  it is not reversible from the receiving side. Named rather than positional in
  `ParsedMergeMessage`, and pinned by tests at both the parser and the service.
  A40 gets its **own dispatcher branch**, deliberately not another entry in
  `ACCEPTED_ADT_EVENTS`: routed through `handleAdt` it would have parsed the
  PID, never looked for MRG, and silently applied a demographic update instead
  of a merge — a quiet wrong-thing rather than a reject. Merges are
  `AUTOMATED`, never `MANUAL`: no human made the call and `mergedBy` is null on
  this path, so the sender and MSH-10 go in the notes as the row's only
  provenance. Unknown identifiers are rejected, never provisioned. A resend
  after the merge is ACCEPTED (both MRNs resolve to one patient by then) so a
  repeat does not park a permanent AE in the sender's queue.

## E6 — Interop breadth that fits this deployment

- [ ] 42. **FHIR DiagnosticReport + ServiceRequest providers.** Seven providers
  exist (Patient, Encounter, Condition, Observation, Immunization,
  MedicationRequest). Orders and reports — labs, the new microbiology cultures,
  the #26 imaging reports — have no FHIR face at all.
- [ ] 43. **FHIR Appointment + Slot providers.** Newly populatable: V121/V128
  gave slots a real inventory and a booking writer, which is why the audit
  correctly called this absent at the time and why it is now cheap.
- [ ] 44. **FHIR DocumentReference + patient record download.** The portal is
  print-only. #477's bulk exporter already streams NDJSON through
  patient-scoped queries, so a single-patient download is a narrow lift.
- [x] 45. **Outbound webhooks / API-key management** for third-party clients.
  `apiKeyReference` exists on `PlatformService` as a pointer with no issuance,
  rotation or verification behind it.
  ✔ 2026-09-04 (V152): `platform.api_keys` — issue (SHA-256 hash at rest, raw
  shown once), rotate, revoke-never-delete, optional expiry; verification via
  `ApiKeyAuthenticationFilter` (X-API-Key) gating the new `/partner/**`
  surface (ping + own delivery log — the verify path has a real caller).
  Outbound webhooks: hospital-scoped endpoints (HTTPS-public-only SSRF gate,
  encrypted HMAC secret shown once), thin id-reference payloads (no PHI in
  transit), `X-HMS-Webhook-Signature` = HMAC-SHA256 over `timestamp.body`,
  instrument-outbox retry sweep, auto-disable on consecutive failures,
  emitters on all four appointment write paths, portal `/webhooks` admin
  page. Also FIXED the pre-existing leak: `apiKeyReference` /
  `credentialsReference` now encrypted at rest, write-only in responses, and
  dropped from the Kafka event payload.

## E7 — Engagement

- [ ] 46. **Day-of self check-in / kiosk.** ⏸ **DEFERRED by the user
  2026-08-27 — not needed for now.** Not a non-goal: the reasoning below still
  holds and it can be picked up when arrival volume asks for it. Do not
  propose it as pickable work in the meantime.
  E-check-in with dynamic questionnaires covers BEFORE the visit; arrival at
  the desk has no self-service path. Verified zero code (`SelfCheckIn`,
  `Kiosk`).
- [x] 47. **Standardized PROs — starting with EPDS.** Behavioral health is
  entirely absent (no PHQ-9, no GAD-7 anywhere). The one that belongs in this
  product first is the **Edinburgh Postnatal Depression Scale** in the
  postpartum module, where there is already a care plan, an alert engine and a
  visit cadence to hang it on. PHQ-9/GAD-7 follow as generic instruments.
  ✔ 2026-09-04 (V153): `clinical.pro_instruments / _items / _options /
  _texts / pro_responses`. **The instrument is data, not code** — every
  option carries its own score, `critical_item_no` marks the item that
  escalates regardless of the total (EPDS item 10), and the engine only sums,
  compares to `positive_threshold` and checks that item. **No instrument text
  is seeded** (V120 rule): EPDS wording, option scores and cutoffs enter
  through `PUT /pro-instruments/{code}` (SUPER_ADMIN, structural validation)
  from the validated source, EN original + validated FR translation, with
  attribution; until loaded, `GET /pro-instruments` hides the instrument and
  the postpartum "Administer" button stays off. Responses stored (answers /
  notes / ack note encrypted, scores plain for the trend); self-harm-positive
  → notify recorder + panel owners on save, re-escalate by sweep until
  acknowledged (`hms.pro.critical-escalation.*`, fallback role when no
  recorder/panel resolves — patient self-report). Cadence hook on
  `PostpartumScheduleDTO.screening` (due / last result / escalation open).
  Portal: shared `<app-pro-instrument-form>` renderer (labels only, never a
  score), postpartum-tab screening card + administer/acknowledge modals,
  patient `/my-screenings` self-report — **deliberately score-free** for the
  mother (she sees "care team alerted" / "follow-up planned", never a
  number). Bambara/Dioula/Mooré texts need commissioned human translation.

## E8 — Cross-hospital record access, on Epic's treatment-relationship model

> ⚠ **The access posture below was revised on 2026-09-08.** The default is now
> *announced availability plus an explicit pull*, not the silent automatic
> merge this preamble describes; automatic merge is kept for emergency
> encounters and for hospitals that have classified their departments. The
> reasoning — the fail-open classifier default with nothing classified, the
> French-derived jurisdiction, and how Epic's two mechanisms actually differ —
> is in `docs/compliance/cross-hospital-record-access-decision-record.md`.
> Items #48, #51, #52 and #53 are unaffected; #50 is re-scoped in place.
> ⚠ **Superseded again on 2026-09-11 by E9 (#55–#69):** the default is the
> Epic same-instance model — automatic merge on registration at the acting
> hospital, no consent gate, sensitive categories behind a stated reason.

**The decision (user, 2026-09-07):** stop treating a patient's consent as the
gate on one hospital reading another's chart, and adopt the model Epic runs in
a shared instance — *the record follows the treatment relationship, and access
is controlled by role security, segmentation and audit rather than by a
facility-pairing handshake*. Epic does not ask "may hospital B see hospital A's
notes?"; it asks "is this clinician treating this patient?" and then, second,
"does this record carry a category that needs its own authorisation?"

**What is NOT being removed.** Consent stays where Epic keeps it, and deleting
it wholesale would break things that are legally load-bearing:

- `TreatmentConsent` / consent-to-treat at check-in gates **treatment**, never
  record access. Untouched.
- ROI requests (V151) stay — that is disclosure to people *outside* the
  treatment relationship (insurers, lawyers, a non-treating provider), which
  is exactly the case Epic still requires an authorisation for.
- Disclosure accounting (V141) stays and gets *more* to record, not less.
- What goes is consent as a precondition for a treating clinician to see the
  chart, and the per-facility grant idea with it. ⚠ **Size the superseded
  surface before committing:** `PatientRecordSharingServiceImpl` is **1648
  lines** with a live controller and its own DTOs. Read it first and decide
  what it becomes — the consent-grant half retires, but its cross-hospital
  *fetch* half is plausibly what #49 should be built on rather than beside.
  Retiring
  `ConsentType.REFERRAL` is NOT a code deletion: the column is
  `@Enumerated(STRING)` over `VARCHAR(50)` (V33), so existing `'REFERRAL'` rows
  fail enum conversion on load, and the portal hard-codes the value in
  `consent-management.component.ts` and `record-sharing.service.ts`. Needs a
  backfill migration plus both portal edits — or keep the value readable and
  merely stop writing it.

**Applies only to `ROW_LEVEL` tenants** — but the carve-out is a *decision*,
not a mechanism that exists yet. V97 is metadata only: the connection-provider
plumbing is gated by `app.tenancy.schema-isolation.enabled`, which is set
nowhere and defaults off (roadmap row 33 is still `started`). A SCHEMA-flagged
hospital's rows sit in the shared schema today, so #49's widened filter WOULD
return them. #49 must exclude `isolation_mode = 'SCHEMA'` explicitly; relying
on "they are in another schema" is relying on something unbuilt. For those, sharing remains an explicit export
via referral or ROI. Every item below is scoped to row-level tenancy and has
to say so in its guard.

⚠ **Legal sign-off is a prerequisite, not a follow-up.** Epic's model rests on
HIPAA's treatment-payment-operations provision, which has no Burkinabè
equivalent that I have verified. Burkina Faso's tradition is French-derived,
and France's DMP is markedly *more* patient-controlled than the US model —
document-level masking, access by a matrix of professional categories. The
opposite pole exists too (UK Summary Care Record, Australia My Health Record:
opt-out national models). **Get counsel and the CIL to confirm that
treatment-purpose access without patient authorisation is lawful here before
#49 ships.** If the answer is no, #52 is the escape hatch: it makes the
posture configurable per hospital, and an opt-in jurisdiction is one setting
rather than a rewrite. **#52 ships before #49 regardless of how the legal
answer lands** — the patient opt-out is part of the model, not a contingency
for a slow lawyer.

Per the lesson at the foot of this page: budget the first pass of each item for
finding out what already ships. `BreakGlassSession` (V67), the disclosure
whitelist (V141), `PatientAccessAuditInterceptor` and `PanelAssignment` (V149)
all exist and are reachable.

- [x] 48. **Treatment-relationship resolver.** _(shipped: `TreatmentRelationshipResolver` + `RecordAccessPolicy`, decay rule in `app.record-access.*`, probe at `GET /patients/{id}/record-access`; nothing consumes it until #49)_ The single predicate the whole
  model rests on: *does this actor have a live clinical relationship with this
  patient, at the hospital they are acting in?* Carriers already in the schema
  — an `Encounter` in a non-terminal status, an `Appointment` today or
  scheduled, an active `Admission`, a `PanelAssignment` (V149), the ordering /
  attending staff on an open order, and `PatientHospitalRegistration`.
  ⚠ The 148 sites split two ways and only the smaller half is about
  registration: ~25 `isRegisteredInHospital` calls ask "is this patient known
  at my hospital", while ~123 `findByPatient_IdAndHospital_Id` calls filter the
  **clinical row's own `hospital_id`** — row provenance, not a relationship.
  #48's predicate governs part of the first group only: roughly 13 of the ~22
  `isRegisteredInHospital` call sites are WRITE guards, which #49 leaves alone,
  so the predicate actually lands on ~9. The ~81 `findByPatient_IdAndHospital_Id`
  call sites across 35 entity repositories are what #49 has to rewrite, and no
  decision about registration changes them. Registration itself is the weakest carrier — "known here", not
  "being treated here" — so decide explicitly whether it counts, and if it
  does, make sure #52's opt-out reaches it; otherwise it is a second
  authorisation path around the switch. One injectable, one method, one cache per
  request; no controller may hand-roll it.
  ⚠ **Not quite "alone": #52's opt-out is honoured inside this predicate**,
  not bolted onto each caller, so either build the opt-out flag with #48 or
  accept reopening #48 when #52 lands. Prefer the former — the predicate is
  the one place the switch is enforceable. Decide and write down the decay
  rule — a relationship that never expires is not a relationship, and Epic's
  own answer (schedule/admission-bounded, with a tail after discharge) is the
  place to start. This is the item to build first and alone: #49 through #53
  are all consumers of it.
- [~] 49. **Replace the hospital-scoped read filter.** _(pass 1: `RecordAccessPolicy.readableHospitalIds` + the doctor timeline widened for encounters, prescriptions and lab results, behind `app.record-access.cross-hospital-reads-enabled` (OFF); 26 of 29 single-hospital finders still unwidened, ratcheted by `CrossHospitalReadFilterCoverageTest`)_ Today ~148 call sites
  gate patient reads on `isRegisteredInHospital` / `findByPatient_IdAndHospital_Id`.
  ⚠ Raw grep says 148, but ~42 of those are the repository declarations
  themselves and 3 are definitions: the real **call-site** surface is ~103, of
  which ~81 are the derived finders below. Use the smaller figure when sizing —
  the inflated one is what the "cannot be hand-edited" argument leaned on.
  The new rule: rows from the actor's own hospital as now, PLUS rows from other
  `ROW_LEVEL` hospitals when #48 says a treatment relationship exists. The
  count is the risk.
  ⚠ **Not all ~103 are reads.** Many `isRegisteredInHospital` sites guard
  WRITES — `AdvanceDirective`, `Guarantor`, `PatientRecall`, `IntakeOutput`,
  `SlotInventory`, `NurseTask` create paths among them. A blanket replacement
  widens cross-hospital *writes*, which this decision does not authorise.
  Classify the ~103 first; the write guards stay as they are.
  ⚠ **A tenant-filter framework ships, but it does not reach this population
  — and that is the crux of the item.** `TenantRepositoryConfig` sets
  `repositoryBaseClass = TenantAwareJpaRepository`, so all 35 repositories
  already extend it; opt-in is entity-level (`implements TenantScoped`, carried
  by 7 entities so far). It intercepts only the `Specification` query paths
  plus `findById` / `existsById` / `getReferenceById`. **Derived finders and
  `@Query` methods bypass it entirely** — and every `findByPatient_IdAndHospital_Id`
  is a derived finder. So "adopt the existing filter" is not a drop-in
  mitigation for the ~81 sites that matter; the real work is converting those
  finders to the Specification path (or giving them a marker the base class can
  act on) so the framework can see them at all. `PatientRepository`'s own
  reference to the class is the Javadoc on `findByIdUnscoped`, documenting a
  deliberate bypass — read that before designing around it.
  ⚠ **The guard test has no marker to scan for.** `SchedulerLockCoverageTest`
  works because `@Scheduled` is enumerable; nothing means "patient-scoped
  query". Defining that marker is part of this item, or the only stated
  mitigation for ~103 sites does not transfer. Expect the first pass to be a survey:
  some of those 148 are already correct, some are the tenant-isolation bug
  class and must be fixed *first*.
  ⚠ **Blocked on the triage-scoping entry in Standing platform debt.** That
  hole is NOT "contained pending this item" — it is already cross-tenant for
  writes today: `submitTriage` / `completeTriage` resolve by `findById` with no
  hospital check, so a nurse holding an encounter id from another hospital can
  write vitals into that chart right now, with no read access at all. Fix it on
  its own schedule, not as part of this epic.
  ⚠ **Also blocked by #51 (the withhold half) and #52 (posture + opt-out),
  both numbered after it.** Working top-down through this section ships the
  widened filter before the sensitive-category withhold exists — do not.
- [ ] 50. **Announced availability + provenance on the chart.** ⚠ **Re-scoped
  2026-09-08 — see `docs/compliance/cross-hospital-record-access-decision-record.md`.**
  The default is no longer a silent merge: the chart states that records exist
  elsewhere (hospital, count, most-recent date — no PHI) and loads them on an
  explicit click, which writes the `RECORD_SHARE` row. Emergency encounters
  merge automatically; a hospital whose departments are classified can be moved
  to automatic merge via `RecordAccessPosture`. Outside rows still land in the
  normal chart surfaces — timeline, results, medications, problems — never a
  separate "other hospitals" tab, because Epic's experience is that clinicians
  do not open the tab.
  **Row-level provenance contract (mandatory, visible without a click):**
  hospital name, staff name, date, what happened. Hospital name
  (`metadata.sourceHospitalName`), date (`occurredAt`) and summary ship
  already; staff name is present for encounters (`clinician`), imaging
  (`orderedBy` / `scanPerformedBy` / `reportFinalizedBy`) and surgical history
  (`performedBy`), and is **missing for prescriptions and lab results** — both
  fillable from `Prescription.staff` and `LabOrder.orderingStaff`, no
  migration needed. ⚠ NOT `LabResult.releasedByDisplay` — that is the
  releaser, often `"Autoverification"` or a bare email, never the treating
  clinician.
  ⚠ **Blocking defect:** the portal's `TimelineEntry`
  (`hospital-portal/src/app/services/patient.service.ts:222`) declares no
  `metadata` field, so #582's provenance reaches the wire and is discarded.
  Nothing above can render until that type carries it. The portal work is the
  larger half of this item.
- [~] 51. **Sensitive-category segmentation.** _(withhold half shipped: `SensitivityCategory` + tag on encounters/admissions/consultations/problems/nursing notes + per-department default (V158), `SensitivityClassifier` with default-withhold; ENFORCEMENT lands with #49, and granular ROI-authorised release is the later pass)_ The carve-out Epic still gates
  on explicit authorisation: substance use, behavioural health / the EPDS rows
  from #47, HIV, reproductive health. These do NOT travel on the treatment
  presumption. Needs a category tag on the clinical row (not a guess from the
  ICD code at read time), a default-withhold rule cross-hospital, and a
  patient-authorised release path. ROI (V151) is the nearest existing
  vehicle, but the preamble scopes ROI to NON-treating parties while this item
  would use it for a treating clinician at another hospital. Decide which:
  widen ROI's stated scope, or give the sensitive-category release its own
  authorisation record. Leaving both readings standing is how one gets built
  by accident. Flagged in the
  industry as incompletely solved; scope it to *withhold correctly* first and
  treat granular release as a later pass. Do not ship #49 without at least the
  withhold half, or the first cross-hospital read discloses a category that
  should never have moved.
- [x] 52. **Per-hospital access posture + patient opt-out.** _(shipped API-only in V157: `hospital.hospitals.record_access_posture` + `clinical.patient_record_sharing_optouts`, both honoured inside the predicate; portal toggle lands with #50)_ The genuinely
  Epic-shaped part: two Epic sites run different consent models because it is
  configuration. A hospital-level setting — `TREATMENT_PRESUMED` (this
  decision) vs `EXPLICIT_CONSENT` (the opt-in jurisdictions) — plus a patient
  opt-out flag that excludes their record from cross-hospital reads while
  leaving in-hospital access untouched. ⚠ "Their own hospital" is singular and
  the schema is not: a patient holds many `PatientHospitalRegistration` rows.
  Define it as *every hospital where they are registered keeps its own access;
  the treatment-relationship widening is what switches off* — the other reading
  (one home hospital) would silently cut access at facilities that legitimately
  hold their chart. Opt-out must be honoured by
  #48's predicate, not bolted onto each caller.
  **Build this before #49, unconditionally.** An earlier draft made it
  conditional on the legal answer being slow; that was wrong — the patient
  opt-out is not a legal contingency, it is part of the model. A fast legal
  yes must not let an implementer ship the widened filter with no way for a
  patient to decline it.
- [~] 53. **Disclosure accounting for every cross-hospital read.** _(pass 1: the timeline emits one RECORD_SHARE per foreign source hospital with actor, acting hospital, source hospital and rows surfaced; the other read surfaces follow as #49 widens them)_
  Record the *reach*: actor, actor's hospital, the record's hospital and the
  relationship that authorised it, so the patient's disclosure report (#39)
  answers "who outside my hospital opened my chart, and why were they
  allowed to?".
  ⚠ **The reach cannot come from `PatientAccessAuditInterceptor`.** Its
  `afterCompletion` sees the request path, never the response body — and once
  #50 merges foreign rows into the chart, a single response spans several
  facilities while the hook can stamp only the actor's own. The source has to
  be #49's filter — but note it knows which hospitals are *permitted*, not
  which ones produced rows. Recording the permitted set would over-report on a
  patient-facing legal document ("hospital C accessed your record" when nothing
  of C's was returned). The reach must be the distinct hospitals of the rows
  actually served.
  ⚠ **That interceptor is also best-effort by construction**, which this model
  cannot tolerate once audit is the ONLY remaining check on access: it skips
  principals that are not `CustomUserDetails` (OIDC / `JwtAuthenticationToken`
  — a case `ControllerAuthUtils` handles precisely because it occurs), dedupes
  on a 30-minute actor+patient window, returns silently when the audit bean is
  absent, and swallows failures to a warn. Hardening those four is part of this
  item, not an assumption behind it — see [[out-of-session-lazy-proxies]] for
  how these rows were silently dropped once already.
- [ ] 54. **Break-the-glass for restricted charts.** Distinct from everything
  above and easy to conflate — this is intra-organisational. VIPs, staff
  members, a clinician's own record or a family member's: access requires a
  stated reason, is time-boxed, and is flagged loudly. `BreakGlassSession`
  (V67) exists.
  ⚠ **The reason prompt already ships** — `BreakGlassSession.reason` is
  `nullable=false`, the controller documents the 400, and the portal banner
  carries a labelled textarea with a ≥10-character check. This entry claiming
  otherwise is the page's own closing lesson repeating itself: check the
  shipped surface before building. Genuinely missing are the
  **chart-restriction flag** (no sensitivity or restricted field exists on the
  patient row anywhere) — and, for the review side, a **portal screen and a
  reviewed / sign-off state**, not the query: `GET /break-glass/audit` ships
  admin-only and paginated, most-recent-first, with `auditCount` and revocation
  fields, documented as being for compliance review screens. That is twice in
  one item; read the endpoint list before writing the next one. Matters more once #49 widens the default reach, because
  the population of people who can technically reach a given chart grows.

## E9 — Le dossier suit le patient (one patient, one chart, many hospitals)

**Decided by the user on 2026-09-11**, after the access-model audit
(artifact: <https://claude.ai/code/artifact/edd6f774-af3b-4db9-8934-60973b0602e7>).
This section supersedes the E8 posture where the two disagree: E8's
"announced availability plus explicit pull" (#50) is withdrawn in favour of
the Epic **same-instance** model — one shared chart, access by role and
treatment relationship, a sensitive-category carve-out unlocked by a stated
reason, and audit. **There is no consent gate.** Burkina Faso has no HIPAA;
the applicable frame is the 2021 data-protection law and the CIL, with the
care-purpose basis under medical secrecy (the French *secret partagé* within
the *équipe de soins*), which counsel confirms in parallel, not as a blocker.

**What the audit found (2026-09-11, develop 17530891 = prod ee22135b):** no
clinical row crosses a hospital boundary today, for any role, consent or not —
the wall is the row's `hospital_id` (85 `findByPatient_IdAndHospital_Id`
finders in 38 services, 34 registration guards, `Patient.hospitalId` = first
hospital as tenant key for 7 entities). The E8 `RecordAccessPolicy` reaches one
read (the doctor timeline) behind a flag that is OFF on prod. The consent-grant
machinery (`PatientConsent` + `ConsentResolutionService` + `/records/*` +
Consent Management + portal Record Sharing, 2,988 lines) is a parallel path the
chart never calls, and `BreakGlassSession` unlocks only that path. 1,117
endpoints / 167 controllers; 111 guards carry permission tokens no user can
hold; 5 phantom role codes in guards; 9 seeded roles guard nothing.

**Decisions taken (D1–D8, user "ok" on the recommendations):**

- **D1** Registration at the acting hospital IS the automatic treatment
  relationship (Tier A). Reception creates it on arrival; every write already
  requires it.
- **D2** A clinician may open an unregistered patient's chart with a stated
  reason (Tier B) — `BreakGlassSession` is the grant: time-boxed, revocable,
  every read stamped.
- **D3** HIV, behavioural health, substance use and reproductive health
  (`SensitivityCategory`, V158) do NOT travel automatically; they open through
  the same reason box. Untagged rows travel (Epic behaviour). Classification
  is by department default; the English keyword heuristic is deleted.
- **D4** The patient opt-out (V157) is KEPT as the single patient control —
  objection, not consent. The portal access log / disclosure report stays.
  The patient-side "grant sharing to hospital X" flow is removed.
- **D5** HOSPITAL_ADMIN becomes administrative-only on the chart (break-glass
  available). Today it reads AND writes diagnoses and allergies.
- **D6** SUPER_ADMIN keeps inherited clinical roles for now, but ONE
  inheritance list (JWT path has 7, login path 14) and every read audited as
  cross-tenant (already the case).
- **D7** The consent-grant machinery is DELETED, not parked. Consent-to-treat
  (V126) and ROI requests (V151) stay — they gate treatment and external
  disclosure, not clinician access. Existing `REFERRAL` consent rows stay
  readable; no data is deleted.
- **D8** Counsel / CIL confirmation runs in parallel.

**Invariants:** writes stay at the acting hospital and still require
registration there; SCHEMA-isolated tenants never cross; every cross-hospital
read writes a `RECORD_SHARE` disclosure row; provenance (hospital, clinician,
date) is visible on every foreign row without a click. Every item is one PR
off develop, drafted until `/code-review` + `/security-review`, never stacked.
**Next free migration: V159.**

### Phase 0 — prerequisites (same work under any decision)

- [ ] 55. **Live hospital scope.** Permitted / primary hospital ids are baked
  into the JWT at login (`CLAIM_PERMITTED_HOSPITAL_IDS`,
  `CLAIM_PRIMARY_HOSPITAL_ID` = first row of an unordered query) and never
  refreshed; `ControllerAuthUtils.resolveHospitalScope` reads the live
  assignment table instead. The two disagree after any assignment change
  until logout — root cause of the nurse's FHIR "not registered" and "failed
  to load history" on dev (2026-09-10). Resolve the permitted set and the
  active hospital from `user_role_hospital_assignment` per request (one query,
  request-cached), keep `X-Hospital-Id` validated against that live set, make
  `resolveHospitalScope` and `HospitalContextHolder` ONE resolver, and delete
  the dead `extractHospitalIdFromJwt`. No migration. Needs `/security-review`.
  _(backend half in PR #597; the portal half is 55b)_
- [ ] 55b. **Portal rehydrates scope from the session, not the token.**
  `app.component.ts` decodes the stored JWT on every bootstrap for
  `permittedHospitalIds` / `primaryHospitalId` and, for non-admin roles,
  collapses the list to ONE hospital ("non-admin staff always get exactly one
  permitted hospital"), so the picker shows a stale or truncated set the
  server now ignores. Hydrate from `GET /auth/session/bootstrap` (live
  assignments, already exists) on bootstrap, MFA challenge, login and
  impersonation; drop the JWT-decoding fallbacks in `auth.service.ts`
  (`getHospitalId`, `getPermittedHospitalIds`) once nothing reads them.
- [ ] 56. **One allergy store.** Three stores never sync: free-text
  `patients.allergies` (Medical tab, `PatientMapper`), structured
  `patient_allergies` scoped by hospital (storyboard `loadAllergies`), and
  `PatientMedicalHistory.allergies`. "No known allergies" beside "peanuts,
  garlic" on one screen (dev, 2026-09-10). Make `patient_allergies`
  patient-wide (drop the hospital scope on READ; provenance kept as a column),
  backfill the free-text column into rows (V159, additive; the column stays
  but is no longer written), point Medical tab / storyboard / FHIR
  AllergyIntolerance / mapper at the one store. Patient-safety fix, and the
  first thing that travels cross-hospital by construction.
  _(PR #598 — NO migration after all: the column is encrypted at rest, so the
  backfill is a Java startup runner (`LegacyAllergyTextBackfill`, one
  transaction per patient, idempotent) rather than SQL; structured rows are the
  truth, the column is a derived summary kept by `PatientAllergySummarySync`;
  storyboard / GET allergies / doctor record / timeline read patient-wide with
  provenance; GET allergies writes RECORD_SHARE for foreign rows. Storyboard
  and chart-review still surface foreign allergies without a ledger row — see
  #60.)_
- [ ] 57. **Stop keying `Patient` on its first hospital.** `Patient implements
  TenantScoped` on `hospitalId` (= first registration) makes a multi-hospital
  patient vanish from the second hospital's scoped finders; #591 papered over
  the chart tabs only. Registration-based scope for `Patient` and the six
  other `TenantScoped` entities (`ImagingOrder`, `ImagingReport`,
  `UltrasoundOrder`, `UltrasoundReport`, `EmpiMasterIdentity`,
  `EmpiMergeEvent`). No migration.

### Phase 1 — flip the policy

- [ ] 58. **`RecordAccessPolicy` on the decided rule.** Relationship = active
  registration at the acting hospital, else an E8 resolver carrier, else a
  live `BreakGlassSession` for this patient+hospital. Remove
  `app.record-access.cross-hospital-reads-enabled` (the widening ships behind
  nothing; dev first, prod in a deliberate sync). Opt-out honoured. SCHEMA
  excluded. `EXPLICIT_CONSENT` stays in the enum and column, hidden from the
  portal. `PatientChartAccess.require` delegates to the policy for reads: 404
  only when there is neither registration nor session. Supersedes E8 #49's
  gating and #52's posture toggle.
- [x] 59. **Widen the read finders, one domain per PR.** ✅ DONE 2026-09-12 — all five domains shipped (a #603, b #604, c #605, d, e); the residuals are listed inline below. The 26 unwidened
  patient+hospital finders (`CrossHospitalReadFilterCoverageTest` budget → 0)
  become `…HospitalIdIn(readable)` on READ paths only: (a) chart — problems,
  allergies, vitals, notes, chart updates; (b) orders and results — lab,
  imaging, procedures, consultations, referrals; (c) medications — prescriptions,
  eMAR history, medication history; (d) maternity and registries; (e) discharge
  and admissions. Write guards untouched. Each PR: the finder, the service,
  the `RECORD_SHARE` row, a tenancy test that fails when the widening is
  reverted.
  **(a) chart shipped 2026-09-12 (PR #603)**: problems, surgical history,
  directives, nursing notes, chart updates; `CrossHospitalRows.maySurface` is
  D3 in code and `CrossHospitalReachRecorder` the one ledger writer. **(b)
  orders and results shipped 2026-09-12**: lab orders, lab results (patient
  path), imaging orders, procedure orders, consultations (D3 applied),
  referrals (originating hospital). **(c) medications shipped 2026-09-12**:
  prescription list + page, patient medications, medication timeline
  (prescriptions + pharmacy fills; the date-ranged fill query had NO hospital
  predicate at all — fixed), doctor-record medications (a foreign
  keyword-sensitive prescription is withheld and does not flag the section).
  Deliberately still local: the MTM polypharmacy count (a write-path
  derivation, no row surfaced — widening it means a RECORD_SHARE on review
  creation, decide with #60) and the CDS/BPA rule engines (#60). eMAR has no
  patient-list read to widen. **(d) maternity and immunizations shipped
  2026-09-12**: labour episodes, newborn assessments, postpartum observations
  (no local plan), birth plans, high-risk care plans, maternal history, OB/GYN
  referrals, ultrasound orders, the doctor record's and timeline's imaging,
  immunizations (list + by vaccine). Seven of those read EVERY tenant before
  (no hospital predicate at all) — brought onto the policy. Still
  patient-wide, recorded here: the immunization schedule reads (overdue,
  upcoming, reminders, incomplete series — JPQL with no hospital predicate)
  and the postpartum care-plan resolver (the plan is the acting hospital's
  object). Maternity rows carry no sensitivity tag; D3's "reproductive
  health" is #63's department classification (family planning), not
  pregnancy care as such — pregnancy care travels by design. **(e) discharge
  and admissions shipped 2026-09-12**: discharge summaries; admissions and
  encounters by patient (both read EVERY tenant before and both carry a
  sensitivity category, so D3 applies through the classifier). Deliberately
  still local: the current-admission lookup (the acting hospital's bed/tracker
  question, not a record that travels). Still unscoped, for #60: the
  storyboard's, snapshot's and mortality service's encounter reads. ⚠ The ratchet is
  a CEILING and walks the
  repository subfolders — 28 single-hospital finders remain, not the 26 this
  bullet first counted; a widened finder whose single sibling still has a
  caller (CDS hooks, FHIR `$everything`, bulk export, record sharing) stays
  until #60/#65 retire the caller. ⚠ Lab and imaging rows carry NO sensitivity
  tag (V158 tagged encounters, admissions, consultations, problems, notes
  only), so an HIV viral load travels cross-hospital today — that is #63's
  department classification, or a tag on `LabTestDefinition`, to decide.
- [x] 60. **Whole-chart surfaces on the readable set.** ✅ DONE 2026-09-12 — (a) in-app surfaces and (b) FHIR `$everything` both on the readable set with a ledger row each; residuals inline below. ⚠ Since #56 the
  storyboard and chart review already surface foreign ALLERGY rows with no
  `RECORD_SHARE` (those calls carry no requester); wiring the ledger through
  these surfaces is part of this item, not optional. FHIR `$everything` /
  `fhir-record` export (`PatientEverythingService.resolveHospitalScopeOrForbid`
  fails closed on the stale context today), storyboard, chart review, timeline
  (already), snapshot. Every one writes the disclosure row.
  **(a) in-app surfaces shipped 2026-09-12**: storyboard (now writes the
  RECORD_SHARE row #56/#59a left out — one per source hospital across
  allergies, problems, directives), chart review (every section on the
  readable set, D3 on encounters, one ledger row per review), snapshot (every
  section on the readable set when an acting hospital is given; allergies
  stay patient-wide per #56; one ledger row per snapshot). Deliberately still
  local: the storyboard's active-encounter lookup (the acting hospital's
  tracker question) and the legacy `patient_diagnoses` read (V14-era,
  read-only). **(b) FHIR `$everything` / `fhir-record` export shipped
  2026-09-12**: every section (Encounter, Observation — vitals and results,
  Condition, MedicationRequest, DocumentReference — discharge summaries) reads
  the readable set through the SectionContext; a foreign encounter or
  condition in a sensitive category is withheld (D3); one RECORD_SHARE per
  source hospital per page, beside the PATIENT_EXPORT audit.
  `resolveHospitalScopeOrForbid` still fails closed with no context — an
  export needs an acting hospital, by design. `$export` (bulk) is a hospital's
  own data and stays local by design; uploaded documents are patient-anchored
  (no hospital column) and stay patient-wide.
- [ ] 61. **Provenance on the row (portal).** `TimelineEntry.metadata` is not
  declared in `patient.service.ts`, so #582's provenance reaches the wire and
  is discarded — the blocking defect from the E8 record. Type it, render
  hospital · clinician · date on every foreign row in timeline, results,
  medications, problems; fill the two missing staff names (prescription from
  `Prescription.staff`, lab from `LabOrder.orderingStaff`, NOT
  `releasedByDisplay`). Absorbs the surviving half of E8 #50.

### Phase 2 — sensitive categories and break-the-glass

- [ ] 62. **Break-the-glass wired into the policy.** Tier B (unregistered
  patient) and the sensitive-row unlock consume `BreakGlassSession`; the
  consent resolver stops being its only consumer. Reads under a session are
  stamped with the session id in the disclosure row. Absorbs E8 #54.
  **(a) shipped 2026-09-12**: `BreakGlassGate` (one live-session question,
  request-cached, a session declared at another hospital does not count);
  `RecordAccessPolicy` grants a Tier B relationship of kind `BREAK_GLASS`
  when no registration and no carrier exist — opt-out and the staff gate
  still hold; `CrossHospitalReachRecorder` stamps `breakGlassSessionId` on
  every disclosure row written under a session; the D3 sites already on
  develop (diagnoses, timeline, doctor record, nursing notes, consultations,
  admissions, encounters) take an `unlocked` flag. **(b) open**: the same
  flag at the whole-chart sites (storyboard, chart review, snapshot, FHIR
  `$everything`) once #610/#60b land; the portal's declare flow is #64's.
- [ ] 63. **Department classification screen + heuristic deletion.**
  `PUT /departments/{id}/default-sensitivity` exists; the HOSPITAL_ADMIN
  screen does not. Add it, put it on the hospital onboarding checklist, and
  delete `PatientServiceImpl.SENSITIVE_KEYWORDS` / `SENSITIVE_DEPARTMENTS`
  (English substring matching against French text; never fired).
- [x] 64. **Restricted rows on the chart (portal).** ✅ DONE 2026-09-12.
  A read that withholds a row under D3 now says so: `WithheldRows` tallies
  the refused rows per recording hospital and department and the storyboard
  and doctor timeline responses carry `restrictedRows` (where and how many,
  never the row). The portal renders each line as *Dossier restreint
  (hôpital, département, n)* with one *Ouvrir avec motif* action that opens
  the existing break-glass banner's declaration modal and its reason
  textarea; a declared or ended session re-reads the storyboard and the
  chart. In-hospital behaviour unchanged: the tally is empty, nothing renders.
  Not counted, by design: the chart's bare-array reads (`/diagnoses`, the
  doctor record sections) have no envelope for the summary, and the
  imaging rows withheld on structured high-risk flags carry no category.
  Previously recorded as: foreign rows whose effective category is set
  render as *Dossier restreint (hôpital, département, n)* with *Ouvrir avec
  motif*, reusing the break-glass banner and its reason textarea.

### Phase 3 — retire the consent grants (D7)

- [ ] 65. **Backend removal.** `PatientConsentController` grant/revoke,
  `ConsentResolutionService` tiers, `/records/share|resolve|aggregate|export`,
  `PatientRecordSharingServiceImpl` (1,648 lines), `POST/DELETE
  /me/patient/consents`. Read endpoints for existing consent rows may stay one
  release for the disclosure report. ROI and treatment consent untouched.
  ⚠ `ConsentType.REFERRAL` rows exist; keep the enum value readable.
- [ ] 66. **Portal removal.** Consent Management becomes Release of
  Information only; patient "Record Sharing" becomes "Who accessed my record"
  plus the opt-out toggle (V157, API shipped, no UI yet); the two i18n trees
  pruned in EN/FR/ES.

### Phase 4 — role hygiene (independent of the model)

- [ ] 67. **D5 + D6.** HOSPITAL_ADMIN off the clinical chart (one constant per
  surface: `CLINICAL_CHART_ROLES`); one SUPER_ADMIN inheritance list shared by
  `JwtTokenProvider` and `SecurityConfig.authoritiesMapper`; a guard test that
  fails when the two lists diverge. Needs `/security-review`.
- [ ] 68. **Dead tokens and phantom roles.** Strip the 111 permission tokens
  from `@PreAuthorize` (wiring `PermissionCatalog` into authorities would widen
  235 guards at once — not this item); seed or remove `ROLE_STAFF` (18
  endpoints), `ROLE_IT_STAFF` (12 + webhooks route), `ROLE_NURSE_PRACTITIONER`,
  `ROLE_DENTIST`, `ROLE_ADMINISTRATIVE_STAFF`, `ROLE_CASHIER`; retire the nine
  seeded-but-unused roles; sidebar visibility decided on the same role lists
  as the API.
- [ ] 69. **Patient-safety gaps in the matrix.** Allergies readable by every
  clinical role (today DOCTOR/NURSE/MIDWIFE/HOSPITAL_ADMIN/PHARMACIST only —
  anaesthesiologists and radiologists cannot read them); PHARMACIST reads
  diagnoses, vitals and lab results (verification gate V139 without renal
  function today); MIDWIFE parity with NURSE on medications and consultations.

## Standing platform debt — owed, not parity

- **Lab results are fetched cross-tenant and filtered in memory.**
  `collectLabResultEntries` calls `findByLabOrder_Patient_Id(patientId)` with no
  hospital predicate, then discards unreadable rows in the stream — so every row
  from a hospital the caller may NOT read is still hydrated with all eight
  fetch-joins first. The `multi-tenancy-scoping` skill names this entity
  explicitly ("`LabResult` → `LabOrder.hospital_id` ... the call must scope
  through the parent"). The finder to add is
  `findByLabOrder_Patient_IdAndLabOrder_Hospital_IdIn`; the sibling
  `findByLabOrder_Hospital_IdIn` already exists with the same graph. Same call
  again in the chart-review aggregator, so the fix pays twice.
- **`messages_en.properties` silently drops the id on three keys.**
  `patient.notfound`, `staff.notfound` and `hospital.notfound` carry no `{0}`
  there, while the base, FR and ES bundles all do — and `messages_en` shadows
  the base for the default locale. So ~25 sites that pass an id render it in
  French and Spanish and swallow it in English. Not covered by
  `NotFoundMessageKeyTest.CONVERTED_KEYS`, which lists only the camelCase twins.
- **46 bundle values carry permanent mojibake** — 19 in `messages_fr`, 27 in
  `messages_es`, accents already destroyed on disk (U+FFFD), so no runtime
  charset setting recovers them. Now ratcheted by
  `NotFoundMessageKeyTest.bundleMojibakeDoesNotSpread` so the count cannot grow;
  repairing them needs a native speaker per string, not a find-and-replace.
- **`BusinessException` does not resolve message keys at all**, and ~12 tests
  pin raw dotted keys as user-visible text — several of which
  (`empi.alias.orphaned`, `empi.lookup.invalidEmpi`,
  `prescription.patient.required`) exist in no bundle. Those reach the API
  client as the error body. It should route through the same resolver as
  `ResourceNotFoundException`.
- **`hasAuthority(...)` is dead across the whole codebase — 235 clauses, 25
  distinct permission names, all permanently false.**
  `CustomUserDetailsService` builds authorities from role codes only
  (`Role::getCode` → `SimpleGrantedAuthority`); permissions never become
  authorities. So every `hasAuthority('X') or hasAnyRole(...)` guard silently
  collapses to its role list, and `VIEW_CONSULTATIONS` is not even in
  `PermissionCatalog` — a guard on a permission that does not exist. Worse,
  `PermissionCatalog` IS used, by `DashboardConfigurationServiceImpl`, to
  decide what the **dashboard shows** — so the catalogue drives the UI while
  roles drive the API, and the portal offers what the backend refuses. ⚠ **Not
  a cleanup:** wiring the catalogue into authorities would widen access across
  235 guards at once, and stripping the dead clauses discards the intent. Needs
  a decision and its own security review, not a tidy-up PR.
- **Nurse/midwife read drift beyond what `fix/nurse-role-drift` closed.**
  That PR granted NURSE `GET /consultations/overdue` and the imaging version
  history — one consultation read, not three. (This bullet said three until
  2026-09-09: the first cut widened three, its own review narrowed it to the
  one endpoint that genuinely refused nurses, and the bullet was not updated.)
  Left open, deliberately, each needing a clinical decision:
  (a) `MeController /critical-alerts` admits DOCTOR, PHYSICIAN, SURGEON and
  MIDWIFE but **not NURSE**, though nurses are usually first to a critical
  value; (b) `/consultations/hospital/{id}` and `/hospital/{id}/pending`
  exclude **both** NURSE and MIDWIFE while `/stats` and the plain list admit
  them — identical drift, not fixed because midwife scope was not authorised; (c) a **maternity cluster**
  (`UltrasoundController`, `MaternalHistoryController`, `BirthPlanController`,
  `ObgynReferralController`) admits MIDWIFE but not NURSE across ~10 reads,
  which may be a real credential boundary rather than drift. Decide (c) before
  touching it.
- **219 `ResourceNotFoundException` sites still pass prose as the message key.**
  `fix/resource-not-found-message-keys` converted 100 where an existing key
  matched; the remainder pass **no argument**, so each needs an identifier
  resolved in its own scope rather than a rewrite rule. Ratcheted by
  `NotFoundMessageKeyTest` so the surface cannot grow. Related: `messages_fr`
  is missing 77 of the 179 base keys and `messages_es` 73 — those fall back to
  English rather than showing the missing-translation marker, so they are
  invisible in testing. The backend has **no bundle-parity gate** at all,
  unlike the portal's strict `i18n:parity`.
- **The 404 disclosure policy contradicts itself.** `/fhir-record` (#577) says
  "is not registered at your active hospital — switch your hospital scope",
  while `PatientChartAccess` deliberately says the opposite in its javadoc:
  "a caller ... learns nothing, not 'exists elsewhere'". One discloses that the
  patient exists somewhere; the other refuses to. Both shipped. E8 moved the
  product toward legitimate cross-hospital reads, which argues for the helpful
  message — but it is an existence disclosure to any authenticated staff at any
  hospital, so it needs a decision, not a default.

- **Allergies, imaging and surgical history cannot cross hospitals, and the
  reason is structural.** They attach to patient + hospital with no encounter
  link (`NursingNote`, `PatientVitalSign`, `PatientAllergy`,
  `ImagingOrder`/`UltrasoundOrder`, `PatientSurgicalHistory`), so #51's
  sensitivity category cannot be resolved for them: a row whose category nobody
  can determine must not travel. **Foreign allergies are the painful one** —
  they are exactly what a clinician wants in an emergency, and they are the one
  category of outside data whose absence can hurt a patient directly. Fixing it
  means either an encounter link on those tables or their own sensitivity
  column (V158 already added one to nursing notes and problems for this reason).
  Decide per table; do not widen them until then.
- **26 of the 29 single-hospital patient finders are still unwidened.** Pass 1
  of #49 routed the doctor timeline only. `CrossHospitalReadFilterCoverageTest`
  freezes the count so a new one cannot appear unnoticed, but it proves nothing
  about the 26 — each is a per-query decision still to be made. The chart tabs,
  the FHIR `$everything` export and the record-sharing surface are the next
  three worth doing.
- **The keyword sensitivity heuristic is still live and cannot be retired yet.**
  The product owner decided (2026-09-08) it goes in #49, but `SENSITIVE_KEYWORDS`
  still drives the intra-hospital doctor-chart toggle and **nothing is tagged**:
  0 departments carry a default and 0 encounters are tagged on dev. Retiring it
  now would make every row read as non-sensitive on a screen clinicians use
  today. Retire it once the departments are classified — that is a clinical data
  task, not a code change.

- **37 more tables carry `patient_id` with no foreign key to
  `clinical.patients`.** V156 constrained the ten core clinical ones
  (consultations, admissions, encounters, prescriptions, vital signs,
  allergies, problems, imaging orders, appointments, lab orders) after a hard
  `deleteById` orphaned three consultations and an admission on dev
  (2026-09-07). Of the 82 tables declaring `patient_id`, 35 had a key before
  V156 and 10 gained one; the rest are still unconstrained — among them
  `clinical.patient_uploaded_documents`, `clinical.discharge_summaries`,
  `clinical.nursing_notes`, `clinical.encounter_notes`,
  `clinical.medication_administration_records`, `clinical.roi_requests` and
  `billing.billing_invoices`. Each needs its own judgement rather than a
  blanket sweep: the `*_v2` tables belong to the legacy `patients_v2` orphan
  stack and should not be constrained at all, and `empi.master_identities`
  spans identities across tenants by design. The app-level refusal in
  `deletePatient` covers all of them today, but only for callers that go
  through the service.
- **The orphans already on dev are not healed by V156.** NOT VALID leaves
  them in place; the deploy log names them. Decide per row whether to
  reconcile to a patient or delete, then
  `ALTER TABLE … VALIDATE CONSTRAINT …` to close the table for good.

- **Nothing enforces "every encounter write is scoped".** All ten mutating
  paths on `EncounterServiceImpl` now go through `requireEncounterInScope`
  (`deleteEncounter` is `ROLE_SUPER_ADMIN`-only and global by design), but the
  guard's javadoc is the only thing carrying the invariant — a new write added
  tomorrow with a bare `findById` reintroduces the hole silently. A marker plus
  a coverage test is what stops that, and it is the E8 #49 problem in
  miniature: there is no annotation meaning "this query is tenant-scoped" to
  scan for.
- **`getAfterVisitSummary` reads unscoped.** It resolves by bare `findById`
  with no hospital check, on an endpoint that also admits `ROLE_PATIENT` — so
  staff at one hospital can read the AVS written by another's checkout. A read,
  not a write, so it belongs with E8 #49's classification pass rather than the
  write-scoping fix.
- **`getEncounterById` keeps the null-hospital bypass on the read side.** Its
  check reads `activeHospitalId != null && e.getHospital() != null && !equals`,
  so an encounter with a null hospital passes for any caller — the same shape
  removed from the three write siblings in #575, left in place here because it
  is a read. Verified 2026-09-07 while re-landing the #575 round-2 fixes; goes
  with `getAfterVisitSummary` in E8 #49's read pass.

- **Outbound mail is sent on the request thread**, inside or just after the
  transaction. `TransactionCallbacks.afterCommit` fires before
  `cleanupAfterCompletion` releases the JDBC connection, so a stalled SMTP host
  pins a Hikari connection for the full send timeout — on registration, on
  account restore, and on the assignment notifications. Deferring to after
  commit (#573) made it strictly better than sending inside the transaction,
  but the send still owns a connection and a request thread it does not need.
  The instrument outbox is the pattern to copy: persist the message, dispatch
  it from a sweep. Surfaced by the #573 review.

- A **rename voids a live login lockout**, and carrying the throttle across a
  rename is not the fix. `LoginAttemptService` keys on `username.toLowerCase()`
  while `uq_user_username` (V1_1) indexes the username verbatim, so `Victim` and
  `victim` are two accounts sharing one throttle key: any move of state under a
  rename lets one account clear or inherit another's lockout, which is worse
  than the leak it closes. Tried and reverted in #573. Two things have to change
  first — an `@PreAuthorize` on `PUT /users/{id}` **and a uniqueness check on
  `dto.getUsername()`, which `updateUser` does not have at all** (unlike
  `changeOwnUsername`, whose `findByUsername` is already case-insensitive:
  `where lower(u.username) = lower(:username)`), and either a case-insensitive
  unique index on `username` or a throttle keyed on the user id rather than the
  name. The missing uniqueness check is the more serious half on its own: a
  rename to a case variant leaves two rows that the case-insensitive
  `findByUsername` cannot resolve, which breaks login for both accounts.
  Until then the leak stands, and it is small next to the ungated endpoint that
  already lets any authenticated caller set another user's password.

- **`/users` is largely ungated.** Only `POST /users/admin-register` and
  `PATCH /users/{id}/restore` carry `@PreAuthorize`; `GET /users`,
  `GET /users/{id}`, `GET /users/search`, `PUT /users/{id}` and
  `DELETE /users/{id}` have none, and `SecurityConfig` matches only the
  register path, so they fall through to `anyRequest().authenticated()`. Any
  authenticated user can therefore list every account and edit any one of
  them — `PUT` accepts `password` and `active`. `restoreUser` additionally
  applies no tenant check to its target. Gating this is not a one-liner: the
  reads are consumed by chat and staff-list for ordinary staff, and
  `patient-form` calls `DELETE` as a receptionist to compensate a failed
  patient create, so each verb needs its own role set. Surfaced by the #572
  review.

- `LoginAttemptService` keeps its counter in a per-JVM `ConcurrentHashMap`, so
  the login lockout and every `resetAttempts` that clears it are instance-local.
  On more than one replica a user can be locked on instance A and activate
  through instance B, and the lock survives — non-deterministically. The
  activation paths all clear the counter now (#571), which makes this correct
  on a single instance; making it correct on several needs shared state
  (the Redis token-blacklist store is already there for the taking). Same class
  of gap as the `app.redis.token-blacklist.enabled` note in the startup log.

- An **empty** activation delivery report warns nobody. `deliveryWarningKeys`
  returns `[]` for `[]` by contract ("nothing was attempted"), but on the
  creation paths that is indistinguishable from "every send failed before it
  could record an outcome" — `AssignmentCreatedEventListener` swallows a
  `sendNotifications` failure, and patient registration, creating a user that
  already exists, and `admin-assignments.submitRegen` produce no other row. All
  three then show a green success toast over an account nothing ever reached.
  The fix belongs at the call sites (a creation flow should treat an empty
  report as "nothing reached them"), not in the shared helper, whose other
  callers legitimately have nothing to send. Found by the #570 review.

- The email-activation **link** has no landing page. `sendActivationEmail`
  builds `${app.frontend.base-url}/verify?email=&token=` (public
  self-registration, and `POST /auth/resend-verification`), but `verify` is not
  a route in `app.routes.ts` — the `**` fallback redirects it to `/login`, so
  the link silently does nothing. There is also no "resend activation" control
  anywhere in the portal, so `/auth/resend-verification` is reachable only by
  hand. Self-registration itself is 410 Gone, which is why this went unnoticed:
  the only live producer of that link is the resend endpoint. Either add the
  `/verify` route (calling `GET /auth/verify-email`) plus a resend affordance
  on the login screen, or retire the link and move those accounts onto the
  confirmation-code path everyone else uses. Found by the #569 review while
  making the disabled-login message name a route that actually exists.

- Hospital scope is applied page by page: after #566, 18 of ~115 staff routes
  carry the scope chip and gate on `RoleContextService.hasHospitalScope`, while
  every other route whose backend calls `requireActiveHospitalId()` (imaging,
  discharge, nurse station, procedure orders, registrations, bed management,
  pharmacy inventory/dispensing/claims, lab QC, reports, billing, departments,
  scheduling…) still fires in global view and shows a load-error toast. The
  right depth is one route-level mechanism — `data: { requiresHospitalScope }`
  on the route consumed by a shell-level gate (chip + hint + outlet) — plus
  `AuthService.getHospitalId()` delegating to
  `roleContext.effectiveHospitalIdForRequest()` so the 22 remaining callers
  follow the chip. From the #566 self-review.
- Hospital search index: V90 indexes `LOWER(name)` with the default opclass, so
  on a non-C collation `LIKE 'x%'` is not a range scan and a one-letter prefix
  late in the alphabet walks most of the index; a `text_pattern_ops` (or partial
  `WHERE active`) index in V156 makes the typeahead's prefix a bounded scan at
  the 10k-tenant scale the design doc plans for. From the #566 self-review.
- Scope-gate boilerplate: six pages carry the same `scopeReady` alias +
  `scopeChanged$` + `takeUntil` + `applyUrlScopeSync` block (registries and
  webhooks use `load$` + `switchMap` instead); one injectable
  (`ready`, `untilScopeChange()`, `sync(route)`) or the route-level gate above
  would make it a one-liner per page. From the #566 self-review.
- `@DataJpaTest` slices repeat the same `@ActiveProfiles("test")` +
  `@Import({TenantContextAccessor, EncryptionKeyHolder})` preamble in six
  classes; a `@TenantScopedDataJpaTest` meta-annotation and a
  `HospitalFixtures` helper would make the next required bean a one-line
  change. From the #566 self-review.
- ~~UI palette migration `--primary: #2563eb` → Keneya green.~~ Closed by
  #562 (2026-09-05): the tokens in `styles.scss` (`--primary: #0e7c6b`, dark
  `#0a5f52`, light `#23b79c` for fills only), axe 6/6.
- `java:S8700` project-wide decision: 21 findings across 18 files, all
  `Duration.between(LocalDateTime, LocalDateTime)`. This is one call about
  whether clinical timestamps move to `Instant`/`OffsetDateTime` on
  `BaseEntity`, not 21 edits. It resurfaces in every Sonar run until decided.
- ~~ShedLock / `@Version` on the remaining check-then-act races.~~ V155 +
  `@SchedulerLock` on all 20 DB-touching sweeps (`SchedulerLockCoverageTest`
  keeps the per-instance evictors an explicit list); the reminder stamp is a
  conditional UPDATE committed in its own transaction before sending
  (`ReminderClaimService`); the lab/imaging escalations lock the shared service
  method so the manual triggers contend for the same lock. Slot hold/book
  had `@Version` since V128. Still owed (#563 self-review): the escalation
  sweeps run one transaction per sweep with the SMS sent inside it, so a late
  row can roll back stamps whose SMS already went out — per-row transactions
  with the send after commit, the `ReminderClaimService` shape; and
  `GeneralReferralController`'s manual trigger does not share
  `ReferralExpiryScheduler`'s lock (per-row `@Version` prevents a double
  transition, so contention, not corruption).
- ~~Audit events on the write surfaces added since #431.~~ Closed by
  `WriteAuditInterceptor`: every successful POST/PUT/PATCH/DELETE by an
  authenticated user is recorded (DATA_CREATE/UPDATE/DELETE, entity from the
  route, patient from `{patientId}`, and the actor's assignment/hospital when
  the request is hospital-scoped) unless the handler opts out with
  `@WriteAudited(skip = true, reason = …)` because its service already emits a
  specific event. #564 restored the hospital-scoped rows, dropped until then
  by a lazy-hospital read outside the session. Still owed (#564 self-review):
  `findFirstByUser_IdAndHospital_IdAndActiveTrue` has no `ORDER BY`, so an
  actor with two active roles at one hospital is anchored to either, on audit
  rows and in authorisation alike (eight callers);
  `AuditEventLogServiceImpl.logEvent` reloads the user and the assignment
  with wide graphs and runs a guaranteed-miss `patientRepository.findById` on
  sub-resource ids for every audited write; four `BaseIT` controller classes
  each hand-roll the same FK-ordered `deleteAll()` list.
- WHO LMS growth-reference import — needs a verified source + clinical
  sign-off. Never from model memory (V120 precedent).
- Drug-interaction KB seed still needs a pharmacist's sign-off.
- `R__prod_role_grants.sql` — a Flyway `R__` name in a Liquibase repo; it is
  run by hand on prod as `postgres`, never by Liquibase (registering it would
  `GRANT` on every deploy and fail where the roles don't exist). Its schema
  arrays had drifted — `scheduling` and `integration` were missing, so
  waitlist/recall/DHIS2 were unreachable by `hms_app` on prod until the
  grants were run by hand 2026-09-05 (#556 fixed the file and added a guard
  test). Still owed: re-run the full script on prod so the two schemas also
  get default privileges for future tables.
- Two-factor transport for controlled substances; `app.empi.probabilistic.enabled`
  still defaults false.

## Open clinical questions — kept open on purpose, not forgotten

These are questions only a clinician can settle. None of them blocks anything:
the software already does what the last sign-off said. They are listed here so
they stay visible instead of living in a javadoc.

- **Are group O platelets to a group B recipient intended?** The 2026-08-25
  haematologist sign-off excludes O platelets for A and AB recipients and not
  for B. That asymmetry is implemented exactly as written and the pairing is
  PERMITTED. Whether it is protocol or a transcription slip has never been
  confirmed. **Product-owner decision 2026-08-26: keep the question open and
  make it known** — so it is surfaced three ways rather than one: an advisory
  chip on the crossmatch row where the pairing actually occurs (blood-bank
  scientist sees it, transfusion is not blocked or delayed), an INFO log line
  each time it happens (so the next review knows how often it arises, which a
  javadoc cannot tell anyone), and `AboGroup.isPlateletPairingPendingConfirmation`
  as the single greppable owner. Delete that method the day it is answered — it
  carries no rule and has no reason to outlive the answer.
- **Should the system prompt for anti-D prophylaxis?** Decided as far as
  engineering can take it: it RECORDS the decision, it does not make it. The
  software surfaces that the recipient is Rh-negative and the unit Rh-positive
  and asks the clinician to record what was decided; it asserts nothing about
  dose, timing or eligibility. Making it advise needs its own sign-off with the
  protocol written out.

## Operational, open right now

- ~~Deploy prod~~ ✅ prod is on `7ff43fba` (2026-08-26), both Railway services
  SUCCESS, `/api/actuator/health` UP. `ddl-auto: validate` means that healthy
  boot proves V140 applied and matches the deployed JAR.
- ⚠ **The licence-expiry sweep is new, defaults ON, fires 06:00 UTC.** Its
  first prod run notifies on every licence inside the 90-day horizon at once
  (`license_alert_stage` starts NULL and any grade beats nothing). One-off
  backlog, not recurring. Disable with
  `hms.credentialing.expiry.enabled=false` to defer it.
- `api.dev.e-keneya.com` → DNS-only (grey cloud) in Cloudflare: Universal SSL
  covers `*.e-keneya.com` but not second-level `*.dev.e-keneya.com`.
- Keycloak `hms-portal` partial-import on the dev + prod realms before any SSO flip.
- Remove the old `*.bitnesttechs.com` custom domains from all Railway services.
- Revoke the chat-exposed SonarCloud token.
- Play Store privacy-policy URL.

## Deliberate non-goals — recorded so they stop resurfacing

Revenue cycle beyond receivables (the roadmap's own entry: partner with a
billing vendor). **All** of perioperative/OpTime — OR scheduling, anesthesia
record, WHO surgical safety checklist, PACU — verified zero-file and out of
scope for a facility without an OR programme. US exchange rails: C-CDA,
IHE XDS/PIX/PDQ, IIS VXU, Carequality/TEFCA/Direct. SNOMED CT (licensing).
Surescripts/NCPDP and EPCS. Video visits (bandwidth — audio memos and
diagnostic photos are the deliberate substitute). Specialty modules beyond OB
(oncology, cardiology, dialysis, ophthalmology, dental). Device integration
with no devices: dispensing cabinets, smart pumps, bedside monitors. Sterile
processing, patient transport, dietary/food service, research & clinical
trials, genomics/PGx, home health & hospice.

**Just outside the line:** anatomic pathology is not a non-goal — it needs a
histopathology service to exist at the facility first. Warehouse/ETL is not a
non-goal either — materialized views and read-replica routing already cover
what the reporting tier asks of it today.

## Recommended order

E0 first and alone (#26 → #27): a clinical loop that cannot close outranks
every breadth item on this page, and #26 is the same defect class the
2026-08-21 reassessment named as the recurring one. Then E1 (#28, #29) —
the emergencies belonging to the specialty this product is deepest in. Then
E2 as one batch (#30–#32, all three touch the same board). E3 is now closed —
the #33 decision was made (scoped to controlled + co-sign-required, not
universal) and both #33 and #34 shipped. **E4–E7 are the remaining Tier 2
work, all pick-by-demand: E4 (#35–#37), E5 (#38 demographics depth, #39b ROI
request workflow — #39 and #40 shipped), E6 (#42–#45), E7 (#46, #47); #41
shipped.**

**E9 (#55–#69) is the live access-model work as of 2026-09-11** and supersedes
E8 where they disagree (#49 gating, #50 announced-pull, #52 posture toggle,
#54). Order: Phase 0 (#55 → #56 → #57) is the same work under any decision
and starts now; Phase 1 (#58 → #59 a–e → #60 → #61) is the widening and goes
to prod only in a deliberate sync; Phase 2 (#62–#64) before Phase 3
(#65–#66) so no read loses its unlock path; Phase 4 (#67–#69) is independent
and can interleave.

**E8 (#48–#54) sits outside that ordering.** It is not parity breadth — it is
the cross-hospital access model, adopted by decision on 2026-09-07, and it
changes the default reach of every patient read in the product. Take #48
first and alone; #49 is blocked on three things at once (the triage-scoping
entry in Standing platform debt, plus #51 and #52 which are numbered after
it); and the legal question gates the epic, not a follow-up to it — if
treatment-purpose access without patient authorisation is not lawful here,
#52 is what makes that a per-hospital setting instead of a rewrite.

One pattern is now consistent enough across #33, #40, #41 and #39 to plan
around: **the entry describing an item as absent has been wrong every time,
and the code that existed was reachable and broken rather than missing.**
#40's alert had been graded on a dashboard since V1 with no delivery; #41's
merge guards were no-ops on the thread that would call them; #39's report was
live in the patient portal and omitted the one category it existed for.
Budget the first pass of any remaining item for *finding out what already
ships*, not for building — and check the shipped surface before trusting a
"verified zero code" note.

- **`ctx.isSuperAdmin()` is authority-derived, not JWT-claim-only.**
  `JwtTokenProvider` builds it as `claim || authorities.anyMatch(ROLE_SUPER_ADMIN)`
  and the OIDC resolver has no claim to read at all, so every cross-tenant
  carve-out keyed on it is reachable by an inflated authority list. Comments in
  `ConsultationServiceImpl` asserted the opposite for months; they are corrected,
  but the property itself is not. Making the signal genuinely claim-only would
  revoke global view from real super-admins on the OIDC path, so it needs its
  own security review rather than a quiet tightening.
- **`CrossTenantReadAudit` misses the two cases most worth tracing.**
  It returns early unless `ctx.isSuperAdmin()`, so (a) a REFUSED cross-tenant
  request from an ordinary caller records nothing — someone enumerating tenant
  UUIDs against a `?hospitalId=` param is exactly the permission-creep probe the
  audit exists to surface; and (b) worse, `RoleValidator.requireActiveHospitalId`
  step 4 grants an unscoped read to a principal whose AUTHORITIES say super-admin
  while `ctx.isSuperAdmin()` is false, and that is precisely the branch the audit
  skips. The least-trusted path to a cross-tenant read is the one that leaves no
  trace. Needs a second entry point, not a widening of this one.
  Related: `RoleValidator`'s step-1 comment claims the check is "per JWT claim,
  not authorities" — same false statement corrected in `ConsultationServiceImpl`,
  still standing there.
- **The guard drift this keeps re-finding needs a ratchet, not another hand-audit.**
  Roughly 280 guards list DOCTOR+NURSE and ~68 list DOCTOR without NURSE, with
  18 controllers carrying both shapes. `WriteAuditCoverageTest`,
  `SchedulerLockCoverageTest` and `CrossHospitalReadFilterCoverageTest` are the
  house precedents: scan every `@RestController`, freeze today's exemptions with
  reasons, and fail an un-reasoned addition. `NurseReadAccessTest` pins four
  endpoints by hand and cannot see the rest.
- **`role-context.stub.ts` is not reactive.** Its getters return plain values
  where the real service returns signals, so any `computed()` over it caches its
  first read forever. Specs pass today only because they never call
  `detectChanges()`; adding one to any of the 9 consuming specs would break tests
  with no production bug present. It also derives `activeRole` from
  `roles.length === 1`, so a multi-role user pinned to one role — the exact case
  `hasAnyActiveRole` exists for — is unrepresentable. Backing it with real
  signals touches every consumer, so it is its own change.
- **`NurseReadAccessTest.guardFor` reads the raw JDK annotation.**
  `Method.getAnnotation(GetMapping.class)` does not resolve Spring's `@AliasFor`,
  so rewriting `@GetMapping("/x")` as `@GetMapping(path = "/x")` — a no-op for
  Spring — makes the test fail with "endpoint missing". It also matches guards by
  substring, so `hasAnyRole('NURSE')` and a hypothetical `!hasRole('NURSE')` are
  indistinguishable to it. Use `AnnotatedElementUtils.findMergedAnnotation`.
- **`LocalDateTime.now()` vs Sonar S8688 — convert per aggregate, never per line.**
  The consultation SLA aggregate is DONE (`ConsultationServiceImpl` +
  `HospitalAdminDashboardServiceImpl` now take the injected `Clock` from
  `TimeConfig`, writers and readers together). ~400 sites remain elsewhere.
  The rule that makes this safe: `calculateSlaDueBy` WRITES `slaDueBy` and the
  overdue queries READ it, so converting only one side would skew every
  comparison by the offset between the two time sources. Convert both ends of
  a comparison in the same change, or leave both alone. `TimeConfig` supplies
  `Clock.systemDefaultZone()`, identical to what `LocalDateTime.now()` used, so
  a correctly-scoped conversion is behaviour-preserving and makes the aggregate
  fixable in a test.

- **Hospital claims in the JWT go stale, and nothing re-issues them.**
  `JwtAuthenticationFilter` builds `HospitalContext` from the token's claims,
  which are baked at login: `CLAIM_PERMITTED_HOSPITAL_IDS` and
  `CLAIM_PRIMARY_HOSPITAL_ID` come from the assignments that existed *then*.
  Assign a clinician to a hospital and their session keeps the old scope until
  they happen to log in again. Observed on dev 2026-09-10: a nurse assigned only
  to Hospital B read a Hospital B patient fine on every endpoint that resolves
  scope from the live assignment table, and got "not registered at your active
  hospital" from every endpoint that reads the context — same user, same patient,
  same page. Fix is to re-issue claims when an assignment changes, or to resolve
  hospital context from live data rather than from claims. Not done here: it
  changes how every request establishes scope and needs its own security review.
- **`ControllerAuthUtils.extractHospitalIdFromJwt` is dead on the primary login path.**
  It reads the hospital claim only when `auth instanceof JwtAuthenticationToken`
  — the OIDC resource-server shape. The username/password login builds a
  `UsernamePasswordAuthenticationToken` (`JwtAuthenticationFilter:139`), so for
  those callers the method always returns null and `resolveHospitalScope`
  silently falls through to `fallbackHospitalFromAssignments`, i.e. the first row
  of a query with no `ORDER BY`. The method reads as "the caller's hospital" and
  is not that, which is how the token-reading and DB-reading paths drifted apart
  without anyone noticing.
- **There is no deterministic "primary hospital".**
  `CLAIM_PRIMARY_HOSPITAL_ID` is `hospitalIds.iterator().next()` over a
  LinkedHashSet built from an unordered assignment query, and
  `UserRoleHospitalAssignment` carries no `primary` flag. For a multi-hospital
  clinician, "your hospital" is whichever row came back first, and two different
  queries can answer differently. The patient chart also has no
  `<app-hospital-scope-hint>` picker (#566 covered 7 other pages), so a clinician
  cannot correct the guess there.
- **Insurance WRITES still resolve the patient tenant-scoped; reads no longer do.**
  `PatientInsuranceServiceImpl` reads now authorize through `PatientChartAccess`
  (unscoped lookup + registration check) while `addInsuranceToPatient`,
  `updatePatientInsurance`, `linkPatientInsurance` and both `upsertAndLink*`
  keep `getPatientOrThrow`, which filters on `Patient.hospitalId`. So a
  clinician at a patient's SECOND hospital can now read coverage but still
  cannot create or relink it. That asymmetry is deliberate — putting writes on
  the registration rule is an authorization change, not the display fix — but
  it is a split a reader will trip over, and it should be resolved one way or
  the other with a decision behind it.
