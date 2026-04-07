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
