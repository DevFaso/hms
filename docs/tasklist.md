# HMS — Active Task List

> **Branch convention:** one feature branch per epic. Update this file on every commit.
> **Statuses:** `✅ Done` | `🔄 In Progress` | `⏳ Not Started` | `⚠️ Blocked`

---

## Current Branch: `feature/lab-director-approval-workflow`

### Epic: Lab Director Approval Workflow (CLIA/CAP/ISO 15189 Compliance)

**Goal:** Enforce regulatory approval lifecycle on lab test definitions —
`DRAFT → PENDING_QA_REVIEW → PENDING_DIRECTOR_APPROVAL → APPROVED → ACTIVE → RETIRED`

---

### Completed ✅

| Task | Layer | Notes |
|---|---|---|
| Add `ROLE_LAB_DIRECTOR` + `ROLE_QUALITY_MANAGER` to `SecurityConstants.java` | Security | 2 new clinical authority roles |
| DB migration V29 — new roles + `approval_status` column | Migration | Grandfathers existing active definitions as `APPROVED` |
| `LabTestDefinitionApprovalStatus` enum | Entity | 7 states including `REJECTED` and `RETIRED` |
| Add approval fields to `LabTestDefinition` entity | Entity | `approvalStatus`, `approvedById`, `approvedAt`, `reviewedById`, `reviewedAt`, `rejectionReason` |
| Update `LabTestDefinitionResponseDTO` with approval fields | DTO | All new fields exposed in API response |
| Create `LabTestDefinitionApprovalRequestDTO` | DTO | `action` + optional `rejectionReason` |
| Update `LabTestDefinitionMapper.toDto()` | Mapper | Maps all approval fields; deduplicated `toResponseDTO` |
| Add `processApprovalAction()` to service interface + impl | Service | Full state machine with per-action role enforcement |
| Add `POST /lab-test-definitions/{id}/approval` endpoint | Controller | Extended `VIEW_ROLES` to include new roles |
| Extend `LabTestDefinition` TypeScript interface with approval fields | Frontend model | Typed union for `approvalStatus` |
| Add `LabTestDefinitionApprovalRequest` interface + `submitApprovalAction()` | Frontend service | Calls the new approval endpoint |
| Add approval modal signals + methods to `LabComponent` | Frontend UI | `openApprovalModal()`, `submitApprovalAction()`, `getApprovalStatusClass()` |
| Add `approvalStatus` filter to `GET /lab-test-definitions/search` endpoint | Backend | Repository + Service + Controller all updated; param is optional enum |
| `LabApprovalQueueComponent` — dedicated approval queue page | Frontend UI | `/lab-approval-queue` route; role-aware action buttons per status; modal for confirm/reject |
| Add `searchTestDefinitions()` with `approvalStatus` param to `LabService` | Frontend service | Calls paginated search endpoint |
| Add `ROLE_LAB_DIRECTOR` + `ROLE_QUALITY_MANAGER` to `lab` route | Frontend routing | Also registered new `lab-approval-queue` route |
| Backend compile check | CI | `BUILD SUCCESSFUL` — warnings only (pre-existing) |
| Frontend format
| Frontend playwright check |passed successfully
| Frontend lint check | CI | Zero ESLint errors |
| Backend unit tests — `LabTestDefinitionServiceImpl.processApprovalAction()` | Tests | 18 tests; all state transitions, role checks, edge cases |
| Controller unit tests — `LabTestDefinitionController` approval + search | Tests | 13 tests; direct invocation; all pass |
| `LabTestValidationStudy` entity + migration V30 | Entity/Migration | `lab.lab_test_validation_studies` table; 7 study types (CLIA/CLSI) |
| `ValidationStudyType` enum | Entity | PRECISION, ACCURACY, REFERENCE_RANGE, METHOD_COMPARISON, INTERFERENCE, CARRYOVER, LINEARITY |
| Validation study CRUD — Repository + Service + Controller | Backend | Nested routes `/lab-test-definitions/{id}/validation-studies`; individual `/lab-test-validation-studies/{id}` |
| `LabTestValidationStudyRequestDTO` + `LabTestValidationStudyResponseDTO` + Mapper | Backend | Full mapper with `toEntity`, `toDto`, `updateEntityFromDto` |
| Frontend — `LabTestValidationStudy` + `LabTestValidationStudyRequest` interfaces | Frontend model | Added to `lab.service.ts`; typed `ValidationStudyType` union |
| Frontend — `getValidationStudies()`, `createValidationStudy()`, `updateValidationStudy()`, `deleteValidationStudy()` | Frontend service | All methods in `LabService` |
| Validation study service unit tests (13) + controller unit tests (10) | Tests | All 23 pass |
| Validation study frontend section on test definition detail | Frontend UI | Detail slide-over panel in approval queue; study list; add-study modal (type, date, pass/fail, summary) |
| QC aggregate summary view per test definition (Levey-Jennings data) | Full-stack | SVG Levey-Jennings chart; `testDefinitionId` filter on `GET /lab-qc-events`; per-level (LOW/HIGH) charts with ±1SD/2SD/3SD zones, Westgard color coding; integrated in definition detail panel |

---

### In Progress 🔄

*Lab Director Approval Workflow epic complete.*

---

### Remaining — This Epic ⏳

*All tasks complete.*

---

## Backlog — Future Epics

| Epic | Description | Priority |
|---|---|---|
| **Patient Portal Phase 3** | Notifications, family access delegation, document upload | Medium |
| **Consent & Record Sharing enhancement** | Cross-org RBAC, audit trail UI | Medium |
| **Dashboard enrichment** | Physician cockpit, nurse station metrics | Low |

---

---

## Epic: Lab Director & Quality Manager Dashboards

**Branch:** `develop`  
**Goal:** Full role-specific dashboards for `ROLE_LAB_DIRECTOR` and `ROLE_QUALITY_MANAGER` — operational KPIs, approval queues, validation study pipelines, and quick-action tiles, matching Epic Beaker LIS patterns.

### Tasks ⏳

| # | Task | Layer | Status |
|---|---|---|---|
| 1 | `LabDirectorDashboardDTO` record | DTO | ✅ |
| 2 | `QualityManagerDashboardDTO` record | DTO | ✅ |
| 3 | Count queries — `LabTestDefinitionRepository` | Repository | ✅ |
| 4 | Count queries — `LabTestValidationStudyRepository` | Repository | ✅ |
| 5 | TAT + count queries — `LabOrderRepository` | Repository | ✅ |
| 6 | `LabDirectorDashboardService` interface + impl | Service | ✅ |
| 7 | `QualityManagerDashboardService` interface + impl | Service | ✅ |
| 8 | New endpoints in `DashboardController` | Controller | ✅ |
| 9 | `LabDirectorDashboardServiceImplTest` | Backend Test | ✅ |
| 10 | `DashboardControllerTest` — new endpoints | Backend Test | ✅ |
| 11 | DTOs + HTTP methods in `dashboard.service.ts` | Frontend service | ✅ |
| 12 | `isLabDirector` / `isQualityManager` signals, `activeView`, `roleLabel` | Frontend component | ✅ |
| 13 | `labDirectorStatCards`, `labDirectorNavTiles` computed | Frontend component | ✅ |
| 14 | `qualityManagerStatCards`, `qualityManagerNavTiles` computed | Frontend component | ✅ |
| 15 | `ngOnInit` data fetch for both roles | Frontend component | ✅ |
| 16 | Lab Director HTML dashboard section | Frontend UI | ✅ |
| 17 | Quality Manager HTML dashboard section | Frontend UI | ✅ |
| 18 | Hero gradient SCSS for both roles | Frontend SCSS | ✅ |
| 19 | i18n keys — `en.json`, `fr.json`, `es.json` | i18n | ✅ |
| 20 | `dashboard.spec.ts` — role tests for both | Frontend Test | ✅ |
| 21 | Format + Lint + Commit + Push | CI | ✅ |

---

## Done — Previous Branches

| Branch | Feature | Merged |
|---|---|---|
| `develop` | iOS App Store assets, privacy policy update | 2026-04-03 |

