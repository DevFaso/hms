# HMS — Active Task List

> **Branch convention:** one feature branch per epic. Update this file on every commit.
> **Statuses:** `✅ Done` | `🔄 In Progress` | `⏳ Not Started` | `⚠️ Blocked`

---

## Current Branch: `feature/patient-portal-phase3`

### Epic: Patient Portal Phase 3 — Notifications + Document Upload

**Goal:** Allow patients to upload and manage personal health documents, and view/manage their in-app notifications with per-preference controls.

---

### Completed ✅

| # | Task | Layer | Notes |
|---|---|---|---|
| 1 | DB migration V32 — `clinical.patient_uploaded_documents` | Migration | Soft-delete via `deleted_at`; 3 partial indexes |
| 2 | `PatientDocumentType` enum (9 values) | Entity | LAB_RESULT, IMAGING_REPORT, DISCHARGE_SUMMARY, REFERRAL_LETTER, PRESCRIPTION, INSURANCE_DOCUMENT, INVOICE, IMMUNIZATION_RECORD, OTHER |
| 3 | `PatientUploadedDocument` entity | Entity | Extends `BaseEntity`; FK to `patients` + `users`; `deletedAt` soft-delete |
| 4 | `PatientUploadedDocumentRepository` | Repository | `findByPatient_IdAndDeletedAtIsNull` (paged), type-filtered variant, single-doc lookup |
| 5 | `PatientDocumentRequestDTO` + `PatientDocumentResponseDTO` | DTO | Request: `documentType`, `collectionDate`, `notes`. Response: full metadata incl. `fileUrl`, `checksumSha256` |
| 6 | `PatientDocumentMapper` | Mapper | `toDto()` with null-safe uploader display name |
| 7 | `FileUploadService` — `uploadPatientDocument()` | Service | 20 MB limit; same allowed extensions as referral attachments; SHA-256 checksum |
| 8 | `PatientDocumentService` interface + `PatientDocumentServiceImpl` | Service | `uploadDocument`, `listDocuments`, `getDocument`, `deleteDocument` (soft delete) |
| 9 | `PatientPortalController` — document endpoints | Controller | `POST /me/patient/documents` (multipart), `GET`, `GET /{id}`, `DELETE /{id}` — all `ROLE_PATIENT` |
| 10 | `PatientPortalController` — notification endpoints | Controller | `GET /notifications` (paged+filter), `GET /unread-count`, `PUT /{id}/read`, `PUT /read-all`, `GET /notification-preferences`, `PUT /notification-preferences` |
| 11 | `PatientPortalService` + impl — notification preference methods | Service | Delegates to existing `NotificationService`; `getMyNotificationPreferences()`, `updateMyNotificationPreferences()` |
| 12 | `PatientDocumentServiceImplTest` — 8 unit tests | Tests | All pass; covers upload, list (all + type-filtered), get, soft-delete, error paths |
| 13 | `patient-portal.service.ts` — 5 new interfaces + 10 new methods | Frontend service | `PatientDocumentType`, `PatientDocumentResponse`, `PortalNotification`, `NotificationPreference`, `NotificationPreferenceUpdate`; all CRUD + notification methods |
| 14 | `MyDocumentsComponent` (4 files) | Frontend UI | Upload form, type filter, document grid with download/delete; `my-documents` route |
| 15 | `MyNotificationsComponent` (4 files) | Frontend UI | All/Unread tabs, mark-read on click, mark-all-read; `my-notifications` route |
| 16 | `app.routes.ts` — `my-documents` + `my-notifications` routes | Frontend routing | Lazy-loaded, `ROLE_PATIENT` guard |
| 17 | i18n keys — `PORTAL.DOCUMENTS.*` + `PORTAL.NOTIFICATIONS.*` | i18n | Added to `en.json`, `fr.json`, `es.json` (41 document keys + 10 notification keys) |
| 18 | Frontend tests — 122/122 pass | Tests | `my-documents.component.spec.ts` (10 tests), `my-notifications.component.spec.ts` (9 tests); full suite passes |
| 19 | Frontend lint — zero errors | CI | `ng lint` — all files pass linting |

---

### In Progress 🔄

*All tasks complete — ready to commit and push.*

---

### Remaining — This Epic ⏳

*None.*

---

## Backlog — Future Epics

| Epic | Description | Priority |
|---|---|---|
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
| `feature/lab-director-approval-workflow` | Lab Director Approval Workflow — CLIA/CAP/ISO 15189 compliance; approval state machine (7 states); validation studies; QC Levey-Jennings charts; Lab Director + Quality Manager dashboards | 2026-04-03 |
| `develop` | iOS App Store assets, privacy policy update | 2026-04-03 |

