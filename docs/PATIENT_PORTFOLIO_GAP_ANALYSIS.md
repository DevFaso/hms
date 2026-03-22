# 🏥 Patient Portfolio Gap Analysis — HMS vs Epic MyChart

> **Date:** March 2026  
> **Scope:** Comprehensive audit of HMS patient-facing features compared to Epic MyChart  
> **Critical Finding:** The system is heavily staff-centric. Most clinical data *exists* in the backend but patients are **locked out** by `@PreAuthorize` annotations and `SecurityConfig` rules.

---

## Executive Summary

| Metric | Count |
|--------|-------|
| Total PATIENT_SELF_SERVICE permissions defined | **19** |
| Permissions actually enforced by endpoints | **~19** (all via `/me/patient/**`) |
| Permissions with **zero backing endpoints** | **~0** |
| Epic MyChart feature categories | **20** |
| HMS covers fully | **33** |
| HMS covers partially | **3** (Payments no gateway, Records no FHIR export, Telehealth) |
| HMS has **zero patient access** | **1** (Questionnaires/PRO) |

### The Core Architectural Problem

Your `PermissionCatalog` already defines `PATIENT_SELF_SERVICE` with 19 permissions:

```
VIEW_OWN_RECORDS, VIEW_OWN_APPOINTMENTS, REQUEST_APPOINTMENTS,
VIEW_OWN_LAB_RESULTS, UPDATE_CONTACT_INFO, VIEW_OWN_PRESCRIPTIONS,
VIEW_OWN_VITAL_SIGNS, DOWNLOAD_MEDICAL_RECORDS, VIEW_BILLING_STATEMENTS,
MAKE_PAYMENTS, VIEW_MEDICATION_INSTRUCTIONS, ACCESS_TREATMENT_PLANS,
VIEW_IMMUNIZATION_RECORDS, UPDATE_EMERGENCY_CONTACTS, REQUEST_MEDICAL_REPORTS,
VIEW_TEST_RESULTS, CANCEL_OWN_APPOINTMENTS, VIEW_INSURANCE_INFORMATION,
CONSENT_TO_DATA_SHARING
```

But **almost none of these permissions are checked by any controller or security rule.** The permissions are cataloged but not wired. The patient can log in, see a dashboard config, and… that's about it.

---

## Detailed Gap Analysis by Epic MyChart Category

### Legend
- ✅ **Implemented** — Patient can access this feature today
- ⚠️ **Partial** — Backend exists but patient is locked out, or only a subset works
- ❌ **Missing** — No implementation exists at all
- 🔒 **Security-Only Fix** — Backend/service layer exists, just needs `@PreAuthorize` + scoped query

---

### 1. 📋 Health Summary / My Health Record

**Epic MyChart:** Central dashboard showing active problems, current medications, allergies, immunizations, care team, and health maintenance reminders.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View own demographics | ✅ | `GET /me/patient/profile` — patient demographics, contact info, emergency contacts. |
| View active problems / diagnoses | ✅ | Returned in `GET /me/patient/health-summary` → `chronicConditions` field. |
| View allergies | ✅ | Returned in `GET /me/patient/health-summary` → `allergies` list. |
| View immunizations | ✅ | `GET /me/patient/immunizations` — full vaccine history via `ImmunizationService`. |
| View care team | ✅ | `GET /me/patient/care-team` — PCP + primary care history via `PatientPrimaryCareService`. |
| Health maintenance reminders | ✅ | `GET /me/patient/health-reminders` — list active reminders scoped to patient (`HealthMaintenanceReminder` entity, 16 types: annual physical, mammogram, colonoscopy, flu shot, etc.). `PUT /me/patient/health-reminders/{id}/complete` — mark reminder as completed. Auto-OVERDUE logic via `@PrePersist/@PreUpdate`. Angular: `my-reminders` component with mark-complete, overdue highlighting. Tests: 12. |
| **Priority** | **P0** | This is the first thing a patient sees in MyChart. |

**What to build:**
1. `GET /api/me/health-summary` → returns demographics, active diagnoses, allergies, immunizations, care team, chronic conditions in one call
2. `GET /api/me/profile` → returns patient demographics with `UPDATE_CONTACT_INFO` permission for PUT
3. Structured `CareTeam` entity (provider, role, specialty, contact) to replace free-text `careTeamNotes`

---

### 2. 🧪 Test Results / Lab Results

**Epic MyChart:** Patients view lab results with reference ranges, trends over time, and can see pending orders.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View own lab results | ✅ | `GET /me/patient/lab-results` — patient-scoped, uses existing `PatientLabResultService`. |
| View lab result trends | ✅ | `GET /me/patient/lab-results/trends` — groups by testDefinitionId, last 12 data points per test. Angular: `my-lab-trends` page. |
| View pending lab orders | ✅ | `GET /me/patient/lab-orders` — delegates to `LabOrderService.getLabOrdersByPatientId()`. Angular: `my-lab-orders` page with status/priority chips. |
| View imaging results | ✅ | `GET /me/patient/imaging/orders` — delegates to `ImagingOrderService.getOrdersByPatient()` (all statuses). Angular: `my-imaging-orders` page with modality, schedule & status details. |
| **Priority** | **P0** | #1 most-used MyChart feature by patients. |

**What to build:**
1. `GET /api/me/lab-results` → patient-scoped, uses `VIEW_OWN_LAB_RESULTS` permission
2. `GET /api/me/lab-results/{id}` → individual result with reference ranges
3. `GET /api/me/lab-orders` → pending orders status
4. `GET /api/me/imaging-results` → patient-scoped imaging

---

### 3. 💊 Medications

**Epic MyChart:** View current medications, request refills, view medication instructions, pharmacy info.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View current medications | ✅ | `GET /me/patient/medications` — patient-scoped via `PatientMedicationService`. |
| View medication instructions | ✅ | Included in `GET /me/patient/medications` response. |
| Request refills | ✅ | `POST /me/patient/refills` — creates `RefillRequest` entity, view via `GET /me/patient/refills`, cancel via `PUT /me/patient/refills/{id}/cancel`. |
| View medication history | ✅ | `GET /me/patient/medications/fills` — pharmacy fill/dispense history via `PharmacyFillRepository.findByPatient_IdOrderByFillDateDesc()`. Angular: `my-pharmacy-fills` page with controlled-substance flag & depletion date. |
| Pharmacy information | ⚠️ | `Patient.preferredPharmacy` field exists. `PharmacyDirectoryController` exists (unclear patient access). |
| **Priority** | **P0** | Patient medication safety is critical. |

**What to build:**
1. `GET /api/me/medications` → current active meds
2. `GET /api/me/medications/history` → timeline
3. `POST /api/me/medications/{id}/refill-request` → request refill (creates task for pharmacy)
4. `GET /api/me/pharmacy` → preferred pharmacy details

---

### 4. 📅 Appointments

**Epic MyChart:** Schedule, view, reschedule, cancel appointments. View visit summaries and after-visit instructions.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View own appointments | ✅ | `AppointmentController` — `ROLE_PATIENT` can view by username, search. |
| Request new appointment | ✅ | `AppointmentController.createAppointment()` includes `ROLE_PATIENT`. |
| Cancel appointment | ✅ | `PUT /me/patient/appointments/cancel` — ownership-verified, sets status to CANCELLED. |
| Reschedule appointment | ✅ | `PUT /me/patient/appointments/reschedule` — ownership-verified, updates date/time. |
| View after-visit summary | ✅ | `GET /me/patient/after-visit-summaries` — discharge summaries scoped to patient. |
| Check-in online (pre-visit) | ✅ | `POST /me/patient/appointments/{id}/check-in` — transitions SCHEDULED/CONFIRMED/PENDING → CHECKED_IN. Angular: `my-checkin` page. |
| **Priority** | **P1** (partial coverage exists) | |

**What to build:**
1. `PUT /api/me/appointments/{id}/cancel` → patient self-cancel with `CANCEL_OWN_APPOINTMENTS` permission
2. `PUT /api/me/appointments/{id}/reschedule` → propose new time
3. `GET /api/me/appointments/{id}/after-visit-summary` → visit summary + instructions
4. `POST /api/me/appointments/{id}/check-in` → online pre-check-in

---

### 5. 💬 Messaging / Secure Communication

**Epic MyChart:** Secure messaging with care team, message attachments, message routing.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| Send messages to providers | ✅ | `ChatController` — `ROLE_PATIENT` included. |
| Receive messages from providers | ✅ | `ChatController` — `ROLE_PATIENT` included. |
| View message history | ✅ | `ChatController` — `ROLE_PATIENT` included. |
| Message attachments | ✅ | `POST /files/chat-attachments` — reuses `FileUploadService` (20 MB, allowed types). `ChatMessage` entity extended with `attachmentUrl`, `attachmentName`, `attachmentContentType`, `attachmentSizeBytes`. Angular: `chat` component enhanced with paperclip button, file preview chip, attachment display in bubbles. |
| Route message to specific care team member | ✅ | `GET /me/patient/care-team/messageable` → `CareTeamContactDTO` (userId, displayName, roleLabel). Angular: care-team tab in new-conv panel with "My Care Team" / "All Staff" tabs for `ROLE_PATIENT`. |
| **Priority** | **P2** (mostly working) | |

---

### 6. 💰 Billing & Payments

**Epic MyChart:** View billing statements, make payments, set up payment plans, view insurance claims, estimate costs.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View billing statements | ✅ | `GET /me/patient/billing/invoices` — paginated patient-scoped invoices. |
| Make payments | ✅ | `POST /me/patient/billing/invoices/{id}/pay` — records payment against invoice (no external gateway yet). |
| View payment history | ✅ | Visible through `GET /me/patient/billing/invoices` invoice statuses. |
| Set up payment plans | ❌ | No payment plan entity or workflow. |
| View insurance claims | ⚠️ 🔒 | `PatientInsuranceController` — ✅ `ROLE_PATIENT` can view/link insurance. But no claims tracking. |
| Cost estimates | ❌ | No cost-estimation engine. |
| **Priority** | **P1** | Revenue cycle and patient satisfaction. |

**What to build:**
1. `GET /api/me/billing/invoices` → patient-scoped invoices
2. `GET /api/me/billing/invoices/{id}` → invoice detail with line items
3. `POST /api/me/billing/payments` → make payment (integrate payment gateway)
4. `GET /api/me/billing/payment-history` → past payments
5. `GET /api/me/insurance` → (already partially exists via PatientInsuranceController)

---

### 7. 🩺 Vital Signs

**Epic MyChart:** View vital sign trends (BP, weight, heart rate, etc.), submit home readings.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View own vital signs | ✅ | `GET /me/patient/vitals` — patient-scoped recent vitals. |
| View vital sign trends | ✅ | `GET /me/patient/vitals/trends?months=3` — full history N months back (1–24), delegates to `PatientVitalSignService.getVitals()`. Angular: `my-vital-trends` page with configurable range & per-metric sections. |
| Submit home readings (BP, glucose, weight) | ✅ | `POST /me/patient/vitals` — records vital with `source=PATIENT_REPORTED` flag. |
| **Priority** | **P1** | Chronic disease management depends on this. |

**What was built:**
1. `GET /me/patient/vitals/trends?months=3` → full vital sign history (1–24 months, clamped)
2. Angular `my-vital-trends` component — configurable month range selector, per-metric sections with reading chips

---

### 8. 📄 Visit History / Encounters

**Epic MyChart:** View past visits with summaries, diagnoses, and notes from each encounter.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View own encounters | ✅ | `GET /me/patient/encounters` — full patient encounter history. |
| View encounter details | ✅ (by ID) | Patient can view a specific encounter if they know the ID. |
| View visit notes | ✅ | `GET /me/patient/encounters/{encounterId}/note` — returns signed `EncounterNoteResponseDTO` (SOAP fields: chiefComplaint, subjective, objective, assessment, plan, patientInstructions, summary, addenda). Encounter ownership verified. Angular: `my-visits` enhanced with expandable cards showing clinical note on demand (lazy-loaded). |
| **Priority** | **P1** | Required by US federal law (21st Century Cures Act / OpenNotes). |

**What to build:**
1. `GET /api/me/encounters` → list patient's own encounters
2. `GET /api/me/encounters/{id}/notes` → clinician notes (OpenNotes compliance)
3. `GET /api/me/encounters/{id}/summary` → after-visit summary

---

### 9. 🛡️ Consent & Privacy

**Epic MyChart:** Manage data sharing consents, proxy access, privacy preferences.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View/manage consents | ✅ | `GET /me/patient/consents` — paginated list of active consents. |
| Grant/revoke data sharing | ✅ | `POST /me/patient/consents` (grant) + `DELETE /me/patient/consents` (revoke) — hospital-registration verified. |
| View who accessed records | ✅ | `GET /me/patient/access-log` — paginated audit log scoped to patient's data. |
| Proxy access (parent/guardian) | ✅ | `GET/POST/DELETE /me/patient/proxies` — full grant/revoke proxy system with `PatientProxy` entity. |
| **Priority** | **P1** | HIPAA compliance and patient trust. |

**What to build:**
1. `GET /api/me/consents` → list active consents
2. `POST /api/me/consents` → grant data-sharing consent
3. `DELETE /api/me/consents/{id}` → revoke consent
4. `GET /api/me/access-log` → who accessed my records (audit trail)
5. Proxy access system (separate feature — parent/guardian delegation)

---

### 10. 📤 Medical Records Download / Health Information Exchange

**Epic MyChart:** Download CCD/C-CDA documents, share records with other providers, Apple Health integration.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| Download medical records | ✅ | `GET /me/patient/records/download?format=pdf|csv` — patient self-download implemented via `exportSelfRecord()` with patient-owned scope. |
| Export in standard formats (C-CDA, FHIR) | ❌ | No C-CDA or FHIR export. |
| Share with other providers | ⚠️ | Staff-mediated sharing exists; patient self-initiated external share flow still missing. |
| Apple Health / Google Health integration | ❌ | No third-party health app integration. |
| **Priority** | **P1** | 21st Century Cures Act / ONC interoperability rules. |

**What to build:**
1. `GET /api/me/records/download` → download own records (PDF summary)
2. `GET /api/me/records/export?format=FHIR` → FHIR R4 Patient bundle export
3. `POST /api/me/records/share` → share with external provider

---

### 11. 🏥 Care Team

**Epic MyChart:** View assigned care team members (PCP, specialists, nurses), contact information, and specialties.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View care team | ✅ | `GET /me/patient/care-team` — returns current PCP + history via `PatientPrimaryCareService`. |
| View PCP info | ✅ | Included in `GET /me/patient/care-team` response. |
| Contact care team | ⚠️ | Via chat only — no care-team-aware routing. |
| **Priority** | **P1** | |

**What to build:**
1. `GET /api/me/care-team` → list of assigned providers (PCP, specialists, nurses)
2. `CareTeamMember` entity (or leverage existing Staff + PCP assignment)
3. `POST /api/me/care-team/{memberId}/message` → route message to specific team member

---

### 12. 💉 Immunization Records

**Epic MyChart:** View immunization history, upcoming vaccinations, immunization certificates.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View immunizations | ✅ | `GET /me/patient/immunizations` — full vaccine history via `ImmunizationService`. |
| View upcoming vaccinations | ✅ | `GET /me/patient/immunizations/upcoming?months=6` — scheduled immunizations in next N months (1–12), delegates to `ImmunizationService.getUpcomingImmunizations()`. Angular: `my-upcoming-vaccines` page with overdue indicator & dose series info. |
| Download immunization certificate | ✅ | `GET /me/patient/immunizations/certificate` — PDFBox-generated PDF (portrait A4) with full vaccine history table (vaccine name, date, dose, lot, manufacturer) via `ImmunizationCertificatePdfService`. Angular: "Download Certificate" button in `my-upcoming-vaccines` header, uses `responseType: 'blob'` download trigger. |
| **Priority** | **P2** | |

**What was built:**
1. `GET /me/patient/immunizations` → vaccination history (existing)
2. `GET /me/patient/immunizations/upcoming?months=6` → upcoming vaccinations in configurable window

---

### 13. 📝 Questionnaires / Pre-Visit Forms

**Epic MyChart:** Fill out pre-visit questionnaires, health screeners, patient-reported outcomes.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| Pre-visit questionnaire | ✅ | `GET /me/patient/questionnaires` (pending) + `POST /me/patient/questionnaires/response` (submit) + `GET /me/patient/questionnaires/submitted`. Angular: `my-questionnaires` page — tab view (Pending / Submitted), dynamic question rendering for TEXT / YES_NO / SCALE / CHOICE types, inline form with per-field validation, duplicate-submission guard. |
| Health screeners (PHQ-9, GAD-7, etc.) | ⚠️ | Backed by same `PreVisitQuestionnaire` / `QuestionType` model — staff can create SCALE / CHOICE screener templates; no hardcoded screener content. |
| Patient-reported outcomes | ✅ | `GET/POST /me/patient/outcomes` — `PatientReportedOutcome` entity with 10 types (PAIN_SCORE, MOOD, ENERGY_LEVEL, SLEEP_QUALITY, ANXIETY_LEVEL, FATIGUE, BREATHLESSNESS, NAUSEA, APPETITE, GENERAL_WELLBEING). Angular: `my-outcomes` page with score slider, badge, empty state. Tests: 11. |
| **Priority** | **P2** | |

**What to build:**
1. `Questionnaire` / `QuestionnaireResponse` entities (FHIR-aligned)
2. `GET /api/me/questionnaires/pending` → forms to fill out
3. `POST /api/me/questionnaires/{id}/response` → submit responses

---

### 14. 📱 Telehealth / Virtual Visits

**Epic MyChart:** Join video visits, virtual urgent care, e-visits (asynchronous).

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| Video visit | ❌ | No telehealth/video integration. |
| E-visit (async) | ❌ | Chat exists but not structured as clinical e-visit. |
| Virtual waiting room | ❌ | |
| **Priority** | **P2** | Post-COVID essential. |

---

### 15. 👨‍👩‍👧 Proxy Access / Family Access

**Epic MyChart:** Parents access children's records, caregivers access elderly patients, linked accounts.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| Link family members | ✅ | `POST /me/patient/proxies` — grant proxy by username with relationship + permissions. |
| Parent access to minor's record | ✅ | Covered by proxy grant system. |
| Caregiver access | ✅ | Covered by proxy grant system; `GET /me/patient/proxy-access` for delegates. |
| **Priority** | **P2** | |

---

### 16. 🔔 Notifications & Alerts

**Epic MyChart:** Push notifications for results, appointment reminders, message alerts, billing notices.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View notifications | ✅ | `NotificationController` — `isAuthenticated()`. |
| Real-time notifications | ✅ | `NotificationWebSocketController` exists. |
| Notification preferences | ✅ | `GET/PUT/DELETE /me/patient/notifications/preferences` — full upsert via `NotificationPreference` entity (type × channel matrix). Angular: `my-notifications` page with toggle matrix for 8 types × 4 channels. |
| **Priority** | **P2** (fully working) | |

---

### 17. 📚 Patient Education

**Epic MyChart:** Condition-specific education, post-visit instructions, health library.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| Browse education resources | ✅ | `PatientEducationController` — `isAuthenticated()` for resource listing. |
| Track education progress | ✅ | `GET /me/patient/education/progress` — all progress records; `GET /me/patient/education/in-progress` + `.../completed` for filtered views. Angular: `my-education-progress` page with progress bars, comprehension status, star ratings & tab filter. |
| Post-visit instructions | ✅ | `GET /me/patient/encounters/{encounterId}/instructions` — maps `DischargeSummary` to `PortalDischargeInstructionsDTO` (activity restrictions, diet, wound care, follow-up instructions, warning signs, disposition, discharge diagnosis, hospital course). Encounter ownership verified. Angular: visible in expanded encounter cards in `my-visits` page with highlighted warning signs section. |
| **Priority** | **P2** | |

---

### 18. 📊 Treatment Plans

**Epic MyChart:** View active treatment plans, track progress, view goals.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View treatment plans | ✅ | `GET /me/patient/treatment-plans` — paginated, patient-scoped. Angular: `my-treatment-plans` expandable cards with status/goals/meds/lifestyle sections. |
| View treatments | ✅ | Included in treatment plans response. |
| Track treatment progress | ✅ | `GET/POST /me/patient/treatment-plans/{planId}/progress` — `TreatmentProgressEntry` entity (progressDate, selfRating 1–10, onTrack, progressNote). Angular: inline log form + history inside expanded `my-treatment-plans` cards. Tests: 8. |
| **Priority** | **P2** | |

---

### 19. 💼 Consultations & Referrals

**Epic MyChart:** View referral status, upcoming specialist consultations.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View consultations | ✅ | `GET /me/patient/consultations` — `ROLE_PATIENT`. Angular: `my-consultations` cards with status/urgency/type chips, recommendations, follow-up badge. |
| View referral status | ✅ | `GET /me/patient/referrals` — patient-scoped. Angular: `my-referrals` cards with status/urgency/specialty/provider/date display. |
| **Priority** | **P2** | |

---

### 20. 🔐 Account & Profile Management

**Epic MyChart:** Update demographics, emergency contacts, communication preferences, linked devices.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View own profile | ✅ | `GET /me/patient/profile` — full demographics and contact info. |
| Update contact info | ✅ | `PUT /me/patient/profile` — updates phone, email, address, emergency contacts, preferred pharmacy. |
| Change password | ✅ | `PasswordResetController` exists. |
| Communication preferences | ✅ | `GET/PUT/DELETE /me/patient/notifications/preferences` — type × channel notification preference matrix. |
| **Priority** | **P0** | |

---

## 📊 Implementation Priority Matrix

### 🔴 P0 — Must Have (MyChart Core / Regulatory)

| # | Feature | Effort | Approach |
|---|---------|--------|----------|
| 1 | **Patient Profile (`/me/patient/profile`)** | ~~Small~~ **DONE** ✅ | `GET/PUT /me/patient/profile` in `PatientPortalController` |
| 2 | **View Own Lab Results** | ~~Small 🔒~~ **DONE** ✅ | `GET /me/patient/lab-results` |
| 3 | **View Own Medications** | ~~Small 🔒~~ **DONE** ✅ | `GET /me/patient/medications` |
| 4 | **Health Summary Dashboard** | ~~Medium~~ **DONE** ✅ | `GET /me/patient/health-summary` — aggregates labs, meds, vitals, immunizations |
| 5 | **View Own Vital Signs** | ~~Small 🔒~~ **DONE** ✅ | `GET /me/patient/vitals` + `POST /me/patient/vitals` (home readings) |
| 6 | **View Billing Statements** | ~~Small 🔒~~ **DONE** ✅ | `GET /me/patient/billing/invoices` + `POST .../pay` |

### 🟡 P1 — Should Have (Patient Engagement / Compliance)

| # | Feature | Effort | Approach |
|---|---------|--------|----------|
| 7 | **Manage Consents** | ~~Small 🔒~~ **DONE** ✅ | `GET/POST/DELETE /me/patient/consents` |
| 8 | **Download Medical Records** | ~~Medium~~ **DONE** ✅ | `GET /me/patient/records/download?format=pdf|csv` |
| 9 | **View Care Team** | ~~Medium~~ **DONE** ✅ | `GET /me/patient/care-team` — PCP + history |
| 10 | **View Immunizations** | ~~Small 🔒~~ **DONE** ✅ | `GET /me/patient/immunizations` |
| 11 | **Cancel/Reschedule Appointments** | ~~Small~~ **DONE** ✅ | `PUT /me/patient/appointments/cancel` + `/reschedule` |
| 12 | **View Visit History (list encounters)** | ~~Small~~ **DONE** ✅ | `GET /me/patient/encounters` |
| 13 | **View Treatment Plans** | ~~Small 🔒~~ **DONE** ✅ | `GET /me/patient/treatment-plans` |
| 14 | **View Referral Status** | ~~Small 🔒~~ **DONE** ✅ | `GET /me/patient/referrals` |
| 15 | **Make Payments** | ~~Large~~ **PARTIAL** ⚠️ | `POST .../pay` records payment; no external gateway yet |
| ~~16~~ | **~~OpenNotes~~** | ~~Medium~~ **DONE** ✅ | `GET /me/patient/encounters/{encounterId}/note` — `EncounterNoteResponseDTO` (SOAP fields, assessment, plan, instructions, addenda). Encounter ownership verified. Angular: `my-visits` expandable cards with lazy-loaded clinical note. |
| ~~17~~ | **~~Post-Visit Instructions~~** | ~~Small~~ **DONE** ✅ | `GET /me/patient/encounters/{encounterId}/instructions` — `DischargeSummary` → `PortalDischargeInstructionsDTO` (activity/diet/wound care/follow-up/warning signs). Angular: discharge instructions in expanded `my-visits` cards. |
| ~~18~~ | **~~Immunization Certificate~~** | ~~Small~~ **DONE** ✅ | `GET /me/patient/immunizations/certificate` — PDFBox A4 PDF with full vaccine history table (name/date/dose/lot/manufacturer). Angular: Download button in `my-upcoming-vaccines`. |
| ~~19~~ | **~~Health Maintenance Reminders~~** | ~~Medium~~ **DONE** ✅ | `GET /me/patient/health-reminders` + `PUT .../complete` — `HealthMaintenanceReminder` entity (16 types, auto-OVERDUE). Angular: `my-reminders` component with overdue indicators + mark-complete. Tests: 12. |

### 🟢 P2 — Nice to Have (Advanced MyChart Features)

| # | Feature | Effort | Approach |
|---|---------|--------|----------|
| 16 | Proxy/Family Access | ~~Large~~ **DONE** ✅ | `PatientProxy` entity + `GET/POST/DELETE /me/patient/proxies` |
| 17 | Telehealth/Video Visits | Large | Third-party integration (Twilio, Zoom) |
| ~~18~~ | ~~Pre-Visit Questionnaires~~ | ~~Medium~~ **DONE** ✅ | `GET/POST /me/patient/questionnaires/response` + `GET /me/patient/questionnaires/submitted` — `PreVisitQuestionnaire`/`QuestionnaireResponse` entities (already existed), Feature 15 in service impl + controller, Angular `my-questionnaires` component with Pending/Submitted tabs, dynamic TEXT/YES_NO/SCALE/CHOICE rendering, duplicate-submission guard. Tests: 12 |
| ~~19~~ | ~~Patient-Reported Outcomes~~ | ~~Medium~~ **DONE** ✅ | `GET/POST /me/patient/outcomes` — `PatientReportedOutcome` entity (10 types). Angular: `my-outcomes` component with score badge, range slider, empty state. Tests: 11. |
| ~~20~~ | ~~Home Vital Sign Submission~~ | ~~Medium~~ **DONE** ✅ | `POST /me/patient/vitals` — `source=PATIENT_REPORTED` flag |
| ~~21~~ | ~~Notification Preferences~~ | ~~Small~~ **DONE** ✅ | `GET/PUT/DELETE /me/patient/notifications/preferences` — `NotificationPreference` entity (type × channel) |
| ~~22~~ | ~~Vital Sign Trends~~ | ~~Medium~~ **DONE** ✅ | `GET /me/patient/vitals/trends?months=N` — 1–24 months, delegates to `PatientVitalSignService.getVitals()` |
| ~~23~~ | ~~Upcoming Vaccinations~~ | ~~Small~~ **DONE** ✅ | `GET /me/patient/immunizations/upcoming?months=N` — 1–12 months, delegates to `ImmunizationService.getUpcomingImmunizations()` |
| ~~24~~ | ~~Lab Orders~~ | ~~Small~~ **DONE** ✅ | `GET /me/patient/lab-orders` — delegates to `LabOrderService.getLabOrdersByPatientId()`, status/priority display |
| ~~25~~ | ~~Imaging Orders~~ | ~~Small~~ **DONE** ✅ | `GET /me/patient/imaging/orders` — delegates to `ImagingOrderService.getOrdersByPatient()`, all statuses |
| ~~26~~ | ~~Pharmacy Fill History~~ | ~~Small~~ **DONE** ✅ | `GET /me/patient/medications/fills` — `PharmacyFillRepository` ordered by fill date desc, controlled-substance flag |
| ~~27~~ | ~~FHIR Export~~ | ~~Large~~ | FHIR R4 resource mapping — deferred |
| ~~28~~ | ~~Cost Estimation~~ | ~~Large~~ | Complex pricing engine — deferred |
| ~~29~~ | ~~Payment Plans~~ | ~~Medium~~ | New workflow + entity — deferred |
| ~~30~~ | ~~Procedures~~ | ~~Small~~ **DONE** ✅ | `GET /me/patient/procedure-orders` — delegates to `ProcedureOrderService.getProcedureOrdersForPatient()`, urgency/status colouring |
| ~~31~~ | ~~Admissions~~ | ~~Small~~ **DONE** ✅ | `GET /me/patient/admissions` + `GET /me/patient/admissions/current` — delegates to `AdmissionService`, current-admission banner |
| ~~32~~ | ~~Education Progress~~ | ~~Small~~ **DONE** ✅ | `GET /me/patient/education/progress`, `.../in-progress`, `.../completed` — tab filter, progress bars, star ratings |
| ~~33~~ | ~~Treatment Plans Angular~~ | ~~Small~~ **DONE** ✅ | `my-treatment-plans` — expandable cards, status chips (approved/review/draft), goals/meds/lifestyle/follow-up sections; tests: 6 |
| ~~34~~ | ~~Referrals Angular~~ | ~~Small~~ **DONE** ✅ | `my-referrals` — status/urgency/type chips, specialty, provider names, submitted/SLA/scheduled dates; tests: 6 |
| ~~35~~ | ~~Consultations Angular~~ | ~~Small~~ **DONE** ✅ | `my-consultations` — status/urgency/type chips, recommendations section, follow-up badge; tests: 6 |
| ~~36~~ | ~~Care Team nav~~ | ~~Small~~ **DONE** ✅ | `my-care-team` route + nav item wired (component + `/me/patient/care-team` backend already existed) |
| ~~37~~ | ~~Family Access nav~~ | ~~Small~~ **DONE** ✅ | `my-family-access` route + nav item wired (component + `/me/patient/proxies` backend already existed) |
| 38 | **Refill Requests Angular** | ~~Small~~ **DONE** ✅ | `my-refills` — list pending/past requests, request new refill form, cancel pending — backend + service fully wired. Tests: 9 |
| 39 | **Appointments: Cancel + Reschedule** | ~~Small~~ **DONE** ✅ | `my-appointments` enhanced — inline cancel (reason), reschedule modal (date/time/reason), in-place status update |
| ~~40~~ | **Home Vital Recording** | ~~Small~~ **DONE** ✅ | `my-vitals` enhanced with record-home-vital modal — BP (sys/dia), HR, resp rate, SpO₂, temp, glucose, weight, body position, notes. Tests: 14 |
| ~~41~~ | **My Profile + Edit** | ~~Small~~ **DONE** ✅ | `my-profile` — view all profile sections (contact, personal, emergency, insurance, allergies) + edit modal for patient-editable fields (phone, email, address, emergency contact, pharmacy). `updateMyProfile()` service method + route + nav item. Tests: 12 |
| ~~42~~ | **Grant Consent UI** | ~~Small~~ **DONE** ✅ | `my-sharing` enhanced with Grant New Consent modal — hospital selectors (from/to), purpose, expiration date. Uses `grantConsent()` → `POST /me/patient/consents`. Tests: 15 |
| ~~43~~ | **Appointment Booking** | ~~Medium~~ **DONE** ✅ | `my-appointments` enhanced with Request New Appointment modal — department/provider cascading selectors, date/time, reason/notes. New backend: `POST /me/patient/appointments`, `GET /me/patient/departments`, `GET /me/patient/departments/{id}/providers`. Portal DTO: `PortalAppointmentRequestDTO`. Tests: 11 |

---

## 🏗️ Recommended Implementation Strategy

### Phase 1: "Unlock the Vault" (1-2 weeks) ✅ COMPLETE

~~The fastest wins — the data and services already exist, you just need to:~~

All Phase 1 endpoints are implemented and live in `PatientPortalController` at `/me/patient/**`:
- ✅ `GET/PUT /me/patient/profile`
- ✅ `GET /me/patient/lab-results`
- ✅ `GET /me/patient/medications`
- ✅ `GET /me/patient/vitals`
- ✅ `GET /me/patient/encounters`
- ✅ `GET /me/patient/consents`
- ✅ `GET /me/patient/immunizations`
- ✅ `GET /me/patient/billing/invoices`
- ✅ `GET /me/patient/care-team`
- ✅ `GET /me/patient/treatment-plans`
- ✅ All secured with `ROLE_PATIENT` in both `@PreAuthorize` and `SecurityConfig`

### Phase 2: "Close the Functional Gaps" (2-4 weeks) ✅ COMPLETE

All Phase 2 endpoints are implemented:
- ✅ `PUT /me/patient/appointments/cancel`
- ✅ `PUT /me/patient/appointments/reschedule`
- ✅ `GET /me/patient/after-visit-summaries`
- ✅ `POST /me/patient/refills` + `GET /me/patient/refills` + `PUT .../cancel`
- ✅ `POST /me/patient/vitals` (home-reported vitals with `source=PATIENT_REPORTED`)
- ✅ `POST/DELETE /me/patient/consents` (grant/revoke)

### Phase 3: "Advanced Patient Experience" (1-3 months) ✅ PARTIAL

- ✅ Proxy/family access — `GET/POST/DELETE /me/patient/proxies` + `PatientProxy` entity
- ✅ Notification preferences — `GET/PUT/DELETE /me/patient/notifications/preferences` (type × channel matrix)
- ✅ Vital sign trends — `GET /me/patient/vitals/trends?months=N` (1–24 months, configurable)
- ✅ Upcoming vaccinations — `GET /me/patient/immunizations/upcoming?months=N` (1–12 months)
- ✅ Lab orders — `GET /me/patient/lab-orders`
- ✅ Imaging orders — `GET /me/patient/imaging/orders`
- ✅ Pharmacy fill history — `GET /me/patient/medications/fills`
- ✅ Procedure orders — `GET /me/patient/procedure-orders`
- ✅ Admission history — `GET /me/patient/admissions` + `GET /me/patient/admissions/current`
- ✅ Education progress — `GET /me/patient/education/progress` + in-progress + completed
- ❌ Payment gateway integration (Stripe/PayStack)
- ❌ FHIR R4 export
- ✅ Pre-visit questionnaires — `GET/POST /me/patient/questionnaires` (see Phase 6)
- ✅ Patient-reported outcomes — `GET/POST /me/patient/outcomes` — `PatientReportedOutcome` entity (see Phase 6)

### Phase 4: "Complete the Patient Experience" ✅ COMPLETE

- ✅ Education resource library — `GET /me/patient/education/resources` + `/search` + `/by-category/{category}` (hospital-scoped via patient registration)
- ✅ Medical records self-download — `GET /me/patient/records/download?format=pdf|csv` — `exportSelfRecord()` bypasses bilateral consent using `patient.getHospitalId()`
- ✅ Lab result trends — `GET /me/patient/lab-results/trends` — groups by testDefinitionId, last 12 data points per test, alphabetically sorted
- ✅ Online check-in — `POST /me/patient/appointments/{id}/check-in` — transitions SCHEDULED/CONFIRMED/PENDING → `CHECKED_IN`; `CHECKED_IN` value added to `AppointmentStatus` enum (no migration needed — VARCHAR column + `EnumType.STRING`)

### Phase 5: "Frontend Completeness" ✅ COMPLETE

All backend endpoints from Phases 1–4 now have corresponding patient-facing Angular components. 27 components total.

- ✅ `my-treatment-plans` — expandable plan cards with status chips, therapeutic goals, medication plan, lifestyle plan, follow-up summary. Tests: 6.
- ✅ `my-referrals` — referral cards with status/urgency/type chips, specialty, reason, provider names, submitted/SLA/scheduled dates. Tests: 6.
- ✅ `my-consultations` — consultation cards with status/urgency/type chips, recommendations section, follow-up badge. Tests: 6.
- ✅ `my-care-team` — route + nav item wired (component and `/me/patient/care-team` backend already existed).
- ✅ `my-family-access` — route + nav item wired (component and `/me/patient/proxies` backend already existed).
- **Angular test suite: 84/84 passing. Build: ✅ Lint: ✅ Format: ✅**

### Phase 6: "Self-Service Actions" ✅ PARTIAL

- ✅ `my-refills` — Medication refill request management. List all requests with status chips, request new refill form (prescription selector, pharmacy, notes), cancel pending requests. `GET/POST /me/patient/refills`, `PUT /me/patient/refills/{id}/cancel`. Tests: 9. Angular test suite: 93/93 passing.
- ✅ `my-appointments` enhanced — Cancel and Reschedule actions on upcoming appointments. Inline cancel form (reason required), reschedule modal (date, start/end time, reason). Uses `cancelAppointment()`, `rescheduleAppointment()`. Status updates in-place without page reload.
- ✅ Home vital recording form — `my-vitals` enhanced with record-home-vital modal. BP (sys/dia), HR, respiratory rate, SpO₂, temperature, blood glucose, weight, body position, notes. Uses `recordHomeVital()`. Tests: 14. Angular test suite: 116/116 passing.
- ✅ `my-profile` — Full profile view + edit modal. Patient-editable fields: phone (primary/secondary), email, address (line1/line2, city, state, zip, country), emergency contact (name, phone, relationship), preferred pharmacy. Backend `PUT /me/patient/profile` fully wired. Tests: 12. Angular test suite: 128/128 passing.
- ✅ `my-sharing` grant consent — Grant New Consent modal. Hospital dropdowns (from/to), purpose, expiration date. Uses `grantConsent()` → `POST /me/patient/consents`. Tests: 15. Angular test suite: 143/143 passing.
- ✅ `my-appointments` booking — Request New Appointment modal. Department + provider dropdowns (cascading), date/time, reason. New backend: `POST /me/patient/appointments` (PENDING status), `GET /me/patient/departments`, `GET /me/patient/departments/{id}/providers`. New portal DTO: `PortalAppointmentRequestDTO`. Tests: 11. Angular test suite: 154/154 passing.
- ✅ Education endpoint wiring optimization — `my-education-progress` now uses dedicated `/education/in-progress` and `/education/completed` tab endpoints; `my-education-browse` category chips now call `/education/resources/by-category/{category}` (server-side filtering). Added/updated unit tests for both components.
- ✅ Pre-visit questionnaires — `GET/POST /me/patient/questionnaires` + `GET /me/patient/questionnaires/submitted`. Backend: Feature 15 in `PatientPortalService/Impl`, `PatientPortalController`. Angular: `my-questionnaires` component with tab view, dynamic question rendering (TEXT/YES_NO/SCALE/CHOICE), inline form, duplicate-submission guard. Tests: 12. Angular test suite: 176/176 passing.
- ✅ Health maintenance reminders — `GET /me/patient/health-reminders` + `PUT /me/patient/health-reminders/{id}/complete`. New entities: `HealthMaintenanceReminder`, `HealthMaintenanceReminderType` (16 types), `HealthMaintenanceReminderStatus`. Angular: `my-reminders` component with mark-complete, overdue indicator. Tests: 12. Angular test suite: 190/190 passing.
- ✅ OpenNotes — `GET /me/patient/encounters/{encounterId}/note` — SOAP note access (21st Century Cures Act compliance). Angular: `my-visits` enhanced with expandable encounter cards + lazy-loaded clinical notes.
- ✅ Post-visit instructions — `GET /me/patient/encounters/{encounterId}/instructions` — `DischargeSummary` → `PortalDischargeInstructionsDTO`. Angular: discharge instructions visible in expanded `my-visits` cards with warning-sign highlight.
- ✅ Immunization certificate download — `GET /me/patient/immunizations/certificate` — PDFBox A4 PDF with full vaccine table. Angular: "Download Certificate" button in `my-upcoming-vaccines` header.
- ✅ Treatment Progress Tracker — `GET/POST /me/patient/treatment-plans/{planId}/progress` — `TreatmentProgressEntry` entity (progressDate, selfRating 1–10, onTrack, progressNote). Angular: inline log form + progress history inside expanded `my-treatment-plans` cards. Tests: 8. Angular test suite: ~198/198.
- ✅ Patient-Reported Outcomes (PROs) — `GET/POST /me/patient/outcomes` — `PatientReportedOutcome` entity with 10 outcome types (PAIN_SCORE, MOOD, ENERGY_LEVEL, SLEEP_QUALITY, ANXIETY_LEVEL, FATIGUE, BREATHLESSNESS, NAUSEA, APPETITE, GENERAL_WELLBEING). Angular: `my-outcomes` standalone component with score badge, range slider, empty state. Tests: 11. Angular test suite: ~209/209.
- ✅ Chat message attachments — `POST /files/chat-attachments` — reuses `FileUploadService` (20 MB limit, same allowed types). `ChatMessage` entity extended with `attachmentUrl`, `attachmentName`, `attachmentContentType`, `attachmentSizeBytes`. `ChatMessageRequestDTO`/`ChatMessageResponseDTO` extended with same fields. `ChatMessageMapper` maps them through. Angular: `chat` component enhanced with paperclip button, upload progress indicator, pending attachment preview chip with remove button, attachment display in message bubbles (inline image preview for images, download link for documents). `ChatAttachmentUploadResponse` interface added to `chat.service.ts`. Tests: 12. Angular test suite: 220/220.

### Phase 7: "Database Infrastructure" ✅ COMPLETE

All entities created in Phases 5–6 were missing Flyway migrations. Two compile bugs were also discovered and fixed during this phase. Local profile app now boots clean.

**Migrations added:**
- ✅ **V30** — `V30__patient_portal_entities_chat_attachments.sql` — Creates `clinical.health_maintenance_reminders` (Feature 19, 11 columns + 4 indexes), `clinical.treatment_progress_entries` (Feature 20, 9 columns + 2 indexes), `clinical.patient_reported_outcomes` (Feature 21, 10 columns + 4 indexes). Alters `support.chat_messages` with `+4 chat attachment columns`: `attachment_url VARCHAR(512)`, `attachment_name VARCHAR(255)`, `attachment_content_type VARCHAR(120)`, `attachment_size_bytes BIGINT`. All DDL is idempotent (`IF NOT EXISTS`). Registered in `changelog.xml`.
- ✅ **V31** — `V31__pre_visit_questionnaires.sql` — Creates `clinical.pre_visit_questionnaires` (7 columns + 3 indexes: `hospital_id`, `department_id`, `active`) and `clinical.questionnaire_responses` (11 columns + 4 indexes: `patient_id`, `questionnaire_id`, `appointment_id`, `status`). Registered in `changelog.xml`.

**Compile bugs fixed:**
- ✅ `QuestionnaireMapper.java` — Was using MapStruct annotations (`@Mapper`, `@Mapping`) but MapStruct is not a project dependency. Converted to a plain `@Component` with manual builder-pattern mapping. All fields correctly mapped: `PreVisitQuestionnaire → PreVisitQuestionnaireDTO` (id, title, description, questions via JSON parse) and `QuestionnaireResponse → QuestionnaireResponseDTO` (id, questionnaireId, questionnaireTitle, patientId, appointmentId, status, submittedAt, answers via JSON parse).
- ✅ `ImmunizationCertificatePdfService.java` — Two `writeText()` calls for centered title lines (line 34 and 37) were missing the `text` argument. Fixed by adding the string values `"IMMUNIZATION CERTIFICATE"` and `"Official Health Record"` as the final argument.

**Result:** `Started HmsApplication in 39.316 seconds` — Tomcat on port 8081, all 32 Liquibase changeSets applied with no schema-validation errors (`ddl-auto: validate`).

---

### Phase 8: "Care Team Message Routing" ✅ COMPLETE

The last buildable feature from the §5 Messaging gap — route messages directly to a patient's care team member.

**Backend changes:**
- ✅ `CareTeamContactDTO.java` — New DTO (`userId UUID`, `displayName`, `roleLabel`) in `payload/dto/portal/`.
- ✅ `PatientPortalService.java` — Added `List<CareTeamContactDTO> getMessageableCareTeam(Authentication auth)` to the interface.
- ✅ `PatientPortalServiceImpl.java` — Implementation fetches current PCP via `primaryCareService.getCurrentPrimaryCare()` + up to 3 historical PCPs via `getPrimaryCareHistory()`, deduplicates by `doctorUserId`, maps to `CareTeamContactDTO` (current → "Primary Care Provider", historical → "Previous Provider").
- ✅ `PatientPortalController.java` — `GET /me/patient/care-team/messageable` endpoint, `@PreAuthorize("hasAuthority('ROLE_PATIENT')")`.

**Angular changes:**
- ✅ `chat.service.ts` — Added `CareTeamContact` interface + `getMessageableCareTeam()` (calls `/api/me/patient/care-team/messageable`, extracts `data` array, safe `catchError(() => of([])`).
- ✅ `chat.ts` — Added signals `careTeamContacts`, `loadingCareTeam`, `newConvTab`; new methods `loadCareTeam()`, `startConversationWithContact()`, `isPatient()`; `openNewConversation()` now loads care team and defaults to `'care-team'` tab for patients.
- ✅ `chat.html` — New-conversation panel now shows "My Care Team" / "All Staff" tabs for `ROLE_PATIENT`. Care-team tab displays provider list with `roleLabel` chip; All Staff tab is the existing user search.
- ✅ `chat.scss` — Added `.new-conv-tabs`, `.tab-btn`, `.tab-btn.active`, `.care-team-role` styles.
- ✅ `chat.spec.ts` — 4 new tests added: `careTeamContacts starts empty`, `newConvTab starts as care-team`, `loadingCareTeam starts false`, `startConversationWithContact adds conversation and selects it`. Total: **16/16 passing**.

---

## 🔑 Key Technical Notes

### SecurityConfig Changes Needed
```
Current:  /api/patients/** → ROLE_DOCTOR, ROLE_NURSE, ROLE_ADMIN, etc.
Needed:   /api/me/**       → ROLE_PATIENT (+ isAuthenticated for shared endpoints)
```

The `/me/` pattern is preferred over `/patients/{patientId}` because:
- Patient resolves their own identity from the JWT token
- Eliminates Insecure Direct Object Reference (IDOR) vulnerabilities
- Matches Epic MyChart's design pattern
- Simpler frontend — no need to know patient UUID

### Permission Wiring Pattern
```java
@PreAuthorize("hasAuthority('VIEW_OWN_LAB_RESULTS')")
@GetMapping("/me/lab-results")
public ResponseEntity<?> getMyLabResults(Authentication auth) {
    UUID patientId = resolvePatientId(auth); // from JWT → User → Patient
    return labResultService.findByPatientId(patientId);
}
```

### Existing Services That Can Be Reused
| Service | Patient Feature It Can Power |
|---------|------------------------------|
| `PatientLabResultController` service layer | `/me/lab-results` |
| `PatientMedicationController` service layer | `/me/medications` |
| `PatientVitalSignController` service layer | `/me/vitals` |
| `PatientConsentController` service layer | `/me/consents` |
| `PatientRecordSharingController` service layer | `/me/records/download` |
| `PatientPrimaryCareController` service layer | `/me/care-team` (PCP) |
| `ImmunizationService` | `/me/immunizations` |
| `BillingInvoiceController` service layer | `/me/billing/invoices` |
| `MedicalHistoryController` service layer | `/me/medical-history` |
| `MedicationHistoryController` service layer | `/me/medications/history` |
| `TreatmentPlanController` service layer | `/me/treatment-plans` |
| `EncounterController` service layer | `/me/encounters` |
| `GeneralReferralController` service layer | `/me/referrals` |

---

## 📋 Missing Entities (Need New Models)

| Entity | Purpose | Epic Equivalent |
|--------|---------|-----------------|
| `CareTeamMember` | Structured care team (provider, role, period) | Care Team |
| `ProxyAccess` / `PatientDelegate` | Parent/guardian/caregiver linkage | MyChart Proxy |
| `Questionnaire` / `QuestionnaireResponse` | Pre-visit forms, screeners | MyChart Questionnaires |
| `PatientReportedOutcome` | Home-submitted health data | Flowsheets (patient-reported) |
| `PaymentPlan` | Installment payment setup | Payment Plans |
| ~~`NotificationPreference`~~ | ~~Per-patient channel/category settings~~ | **DONE** ✅ — `security.notification_preferences` table, `NotificationPreference` entity |
| `AfterVisitSummary` | Post-encounter patient instructions | AVS |
| `RefillRequest` | Medication refill workflow | Rx Refill |
| `OnlineCheckIn` | Pre-visit digital check-in | E-Check-In |

---

## Summary

**Your HMS has an impressive backend — 80+ controllers, rich entity models, and a well-thought-out permission system.** The gap isn't in the clinical data modeling — it's that the patient portal is essentially a locked door. The `PATIENT_SELF_SERVICE` permission group defines 19 capabilities that patients *should* have, but only ~5 are actually accessible through endpoints.

**The good news:** Phase 1 is mostly a security configuration exercise. The services, repositories, and data models are already there. You're not building from scratch — you're opening the doors.
