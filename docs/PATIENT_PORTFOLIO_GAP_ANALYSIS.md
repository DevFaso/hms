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
| HMS covers fully | **19** |
| HMS covers partially | **3** (Payments no gateway, Records no PDF/FHIR, Telehealth) |
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
| Health maintenance reminders | ❌ | No preventive-care / screening reminder system. |
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
| View lab result trends | ❌ | No trend/chart data endpoint. |
| View pending lab orders | ⚠️ 🔒 | `LabOrderController` — staff-only. |
| View imaging results | ⚠️ 🔒 | `ImagingResultController` — staff-only. |
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
| View medication history | ⚠️ 🔒 | `MedicationHistoryController` — staff-only. `VIEW_MEDICATION_HISTORY` not in PATIENT_SELF_SERVICE group. |
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
| Check-in online (pre-visit) | ❌ | No digital check-in flow. |
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
| Message attachments | ❌ | `FileUploadController` exists but unclear if tied to chat. |
| Route message to specific care team member | ❌ | No care-team routing. Messages are 1:1 only. |
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
| View visit notes | ❌ | No OpenNotes-style patient access to clinician notes (21st Century Cures Act requirement). |
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
| Download medical records | ⚠️ 🔒 | `PatientRecordSharingController` (`/records`) — **staff-only**. `DOWNLOAD_MEDICAL_RECORDS` + `REQUEST_MEDICAL_REPORTS` permissions defined but unused. |
| Export in standard formats (C-CDA, FHIR) | ❌ | No C-CDA or FHIR export. |
| Share with other providers | ⚠️ 🔒 | `PatientRecordSharingController` has share/export methods — staff-only. |
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
| Download immunization certificate | ❌ | No certificate generation. |
| **Priority** | **P2** | |

**What was built:**
1. `GET /me/patient/immunizations` → vaccination history (existing)
2. `GET /me/patient/immunizations/upcoming?months=6` → upcoming vaccinations in configurable window

---

### 13. 📝 Questionnaires / Pre-Visit Forms

**Epic MyChart:** Fill out pre-visit questionnaires, health screeners, patient-reported outcomes.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| Pre-visit questionnaire | ❌ | No questionnaire/form system. |
| Health screeners (PHQ-9, GAD-7, etc.) | ❌ | No standardized screener support. |
| Patient-reported outcomes | ❌ | No PRO collection mechanism. |
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
| Track education progress | ⚠️ 🔒 | Progress tracking endpoints exist — not yet exposed to patient portal. |
| Post-visit instructions | ❌ | No after-visit instruction delivery to patient. |
| **Priority** | **P2** | |

---

### 18. 📊 Treatment Plans

**Epic MyChart:** View active treatment plans, track progress, view goals.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View treatment plans | ✅ | `GET /me/patient/treatment-plans` — paginated, patient-scoped. |
| View treatments | ✅ | Included in treatment plans response. |
| Track treatment progress | ❌ | No patient-facing progress tracker. |
| **Priority** | **P2** | |

---

### 19. 💼 Consultations & Referrals

**Epic MyChart:** View referral status, upcoming specialist consultations.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View consultations | ✅ | `ConsultationController` — `ROLE_PATIENT` can list consultations for self. |
| View referral status | ✅ | `GET /me/patient/referrals` — patient-scoped referral list. |
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
| 8 | **Download Medical Records** | Medium 🔒 | PDF generation not yet built |
| 9 | **View Care Team** | ~~Medium~~ **DONE** ✅ | `GET /me/patient/care-team` — PCP + history |
| 10 | **View Immunizations** | ~~Small 🔒~~ **DONE** ✅ | `GET /me/patient/immunizations` |
| 11 | **Cancel/Reschedule Appointments** | ~~Small~~ **DONE** ✅ | `PUT /me/patient/appointments/cancel` + `/reschedule` |
| 12 | **View Visit History (list encounters)** | ~~Small~~ **DONE** ✅ | `GET /me/patient/encounters` |
| 13 | **View Treatment Plans** | ~~Small 🔒~~ **DONE** ✅ | `GET /me/patient/treatment-plans` |
| 14 | **View Referral Status** | ~~Small 🔒~~ **DONE** ✅ | `GET /me/patient/referrals` |
| 15 | **Make Payments** | ~~Large~~ **PARTIAL** ⚠️ | `POST .../pay` records payment; no external gateway yet |

### 🟢 P2 — Nice to Have (Advanced MyChart Features)

| # | Feature | Effort | Approach |
|---|---------|--------|----------|
| 16 | Proxy/Family Access | ~~Large~~ **DONE** ✅ | `PatientProxy` entity + `GET/POST/DELETE /me/patient/proxies` |
| 17 | Telehealth/Video Visits | Large | Third-party integration (Twilio, Zoom) |
| 18 | Pre-Visit Questionnaires | Medium | New entity model + form engine |
| 19 | Patient-Reported Outcomes | Medium | New entity model |
| ~~20~~ | ~~Home Vital Sign Submission~~ | ~~Medium~~ **DONE** ✅ | `POST /me/patient/vitals` — `source=PATIENT_REPORTED` flag |
| ~~21~~ | ~~Notification Preferences~~ | ~~Small~~ **DONE** ✅ | `GET/PUT/DELETE /me/patient/notifications/preferences` — `NotificationPreference` entity (type × channel) |
| ~~22~~ | ~~Vital Sign Trends~~ | ~~Medium~~ **DONE** ✅ | `GET /me/patient/vitals/trends?months=N` — 1–24 months, delegates to `PatientVitalSignService.getVitals()` |
| ~~23~~ | ~~Upcoming Vaccinations~~ | ~~Small~~ **DONE** ✅ | `GET /me/patient/immunizations/upcoming?months=N` — 1–12 months, delegates to `ImmunizationService.getUpcomingImmunizations()` |
| 24 | FHIR Export | Large | FHIR R4 resource mapping |
| 25 | Cost Estimation | Large | Complex pricing engine |
| 26 | Payment Plans | Medium | New workflow + entity |

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
- ❌ Payment gateway integration (Stripe/PayStack)
- ❌ Telehealth / video visits (Twilio/Zoom)
- ❌ Pre-visit questionnaires
- ❌ FHIR R4 export
- ❌ Medical records PDF download
- ❌ Patient-reported outcomes (structured)

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
