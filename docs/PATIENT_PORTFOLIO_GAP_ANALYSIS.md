# 🏥 Patient Portfolio Gap Analysis — HMS vs Epic MyChart

> **Date:** June 2025  
> **Scope:** Comprehensive audit of HMS patient-facing features compared to Epic MyChart  
> **Critical Finding:** The system is heavily staff-centric. Most clinical data *exists* in the backend but patients are **locked out** by `@PreAuthorize` annotations and `SecurityConfig` rules.

---

## Executive Summary

| Metric | Count |
|--------|-------|
| Total PATIENT_SELF_SERVICE permissions defined | **19** |
| Permissions actually enforced by endpoints | **~5** (appointments, chat, insurance, notifications, prescriptions) |
| Permissions with **zero backing endpoints** | **~14** |
| Epic MyChart feature categories | **20** |
| HMS covers fully | **2** (Messaging, Notifications) |
| HMS covers partially | **5** (Appointments, Insurance, Prescriptions, Encounters, Education) |
| HMS has **zero patient access** | **13** categories |

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
| View own demographics | ⚠️ 🔒 | `Patient` entity has all fields. `PatientController` has GET by ID but requires `ROLE_DOCTOR/NURSE/ADMIN`. Need a `/me/profile` endpoint. |
| View active problems / diagnoses | ⚠️ 🔒 | `PatientController.getDiagnoses()` exists — staff-only. |
| View allergies | ⚠️ 🔒 | `Patient.allergyEntries` relationship exists. `PatientController.getAllergies()` — staff-only. Permission `ACCESS_PATIENT_ALLERGIES` defined but not for patient. |
| View immunizations | ⚠️ 🔒 | `PatientImmunization` entity exists. `ImmunizationService` exists. `VIEW_IMMUNIZATION_RECORDS` permission defined. No patient-facing endpoint. |
| View care team | ❌ | No `CareTeamController`. `Patient.careTeamNotes` is a free-text field, not structured. No care-team member list. |
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
| View own lab results | ⚠️ 🔒 | `PatientLabResultController` (`/patients/{patientId}/lab-results`) — **staff-only**. `LabResult` entity + service fully built. Permission `VIEW_OWN_LAB_RESULTS` defined but unused. |
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
| View current medications | ⚠️ 🔒 | `PatientMedicationController` (`/patients/{patientId}/medications`) — **staff-only**. `VIEW_OWN_PRESCRIPTIONS` + `VIEW_MEDICATION_INSTRUCTIONS` permissions defined but unused. |
| View medication instructions | ⚠️ 🔒 | Backend exists via medication entity. |
| Request refills | ❌ | No refill-request workflow. |
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
| Cancel appointment | ⚠️ | `CANCEL_OWN_APPOINTMENTS` permission defined, but controller cancel endpoint likely staff-only. |
| Reschedule appointment | ❌ | No patient-initiated reschedule. |
| View after-visit summary | ❌ | `DischargeSummaryController` exists but staff-only. No "After Visit Summary" concept. |
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
| View billing statements | ⚠️ 🔒 | `BillingInvoiceController` — **admin-only**. `Patient.billingInvoices` relationship exists. `VIEW_BILLING_STATEMENTS` permission defined but unused. |
| Make payments | ❌ | `MAKE_PAYMENTS` permission defined. No patient payment endpoint. No payment gateway integration. |
| View payment history | ⚠️ 🔒 | Invoice data exists. |
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
| View own vital signs | ⚠️ 🔒 | `PatientVitalSignController` (`/patients/{patientId}/vitals`) — **staff-only**. `Patient.vitalSignCaptures` relationship exists. `VIEW_OWN_VITAL_SIGNS` permission defined but unused. |
| View vital sign trends | ❌ | No trend/chart endpoint. |
| Submit home readings (BP, glucose, weight) | ❌ | No patient-submitted vitals workflow. |
| **Priority** | **P1** | Chronic disease management depends on this. |

**What to build:**
1. `GET /api/me/vitals` → latest vitals
2. `GET /api/me/vitals/trends?type=BLOOD_PRESSURE&months=6` → trend data
3. `POST /api/me/vitals/home-reading` → patient submits home reading (flagged as patient-reported)

---

### 8. 📄 Visit History / Encounters

**Epic MyChart:** View past visits with summaries, diagnoses, and notes from each encounter.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View own encounters | ⚠️ | `EncounterController` — `ROLE_PATIENT` can GET by ID, but **cannot list own encounters**. |
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
| View/manage consents | ⚠️ 🔒 | `PatientConsentController` (`/patient-consents`) — **staff-only**. `CONSENT_TO_DATA_SHARING` permission defined but unused by any patient endpoint. |
| Grant/revoke data sharing | ⚠️ 🔒 | Service layer exists (`grant`, `revoke` methods). Patient locked out. |
| View who accessed records | ❌ | `AuditEventLogController` exists but admin-only. No patient access audit log. |
| Proxy access (parent/guardian) | ❌ | No proxy/delegate system. |
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
| View care team | ❌ | `PatientPrimaryCareController` exists (**staff-only**) for PCP assignment. No patient-facing care team list. `Patient.careTeamNotes` is free-text, not structured. |
| View PCP info | ⚠️ 🔒 | PCP is assigned but patient can't view it. |
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
| View immunizations | ⚠️ 🔒 | `PatientImmunization` entity exists. `ImmunizationService` exists. `MedicalHistoryController` manages immunizations — **staff-only**. `VIEW_IMMUNIZATION_RECORDS` permission defined but unused. |
| View upcoming vaccinations | ❌ | No vaccination schedule / recommendation engine. |
| Download immunization certificate | ❌ | No certificate generation. |
| **Priority** | **P2** | |

**What to build:**
1. `GET /api/me/immunizations` → vaccination history
2. `GET /api/me/immunizations/upcoming` → recommended vaccinations based on age/history

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
| Link family members | ❌ | No proxy/delegate model. |
| Parent access to minor's record | ❌ | |
| Caregiver access | ❌ | |
| **Priority** | **P2** | |

---

### 16. 🔔 Notifications & Alerts

**Epic MyChart:** Push notifications for results, appointment reminders, message alerts, billing notices.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View notifications | ✅ | `NotificationController` — `isAuthenticated()`. |
| Real-time notifications | ✅ | `NotificationWebSocketController` exists. |
| Notification preferences | ❌ | No per-patient notification settings. |
| **Priority** | **P2** (mostly working) | |

---

### 17. 📚 Patient Education

**Epic MyChart:** Condition-specific education, post-visit instructions, health library.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| Browse education resources | ✅ | `PatientEducationController` — `isAuthenticated()` for resource listing. |
| Track education progress | ⚠️ 🔒 | Progress tracking endpoints exist — staff-only. |
| Post-visit instructions | ❌ | No after-visit instruction delivery to patient. |
| **Priority** | **P2** | |

---

### 18. 📊 Treatment Plans

**Epic MyChart:** View active treatment plans, track progress, view goals.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View treatment plans | ⚠️ 🔒 | `TreatmentPlanController` exists — staff-only. `ACCESS_TREATMENT_PLANS` permission defined but unused. |
| View treatments | ⚠️ 🔒 | `TreatmentController` exists — staff-only. |
| Track treatment progress | ❌ | No patient-facing progress tracker. |
| **Priority** | **P2** | |

---

### 19. 💼 Consultations & Referrals

**Epic MyChart:** View referral status, upcoming specialist consultations.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View consultations | ✅ | `ConsultationController` — `ROLE_PATIENT` can list consultations for self. |
| View referral status | ⚠️ 🔒 | `GeneralReferralController` exists — likely staff-only. |
| **Priority** | **P2** | |

---

### 20. 🔐 Account & Profile Management

**Epic MyChart:** Update demographics, emergency contacts, communication preferences, linked devices.

| Sub-feature | HMS Status | Detail |
|------------|-----------|--------|
| View own profile | ⚠️ 🔒 | Patient entity has all fields. No `/me/profile` endpoint. |
| Update contact info | ⚠️ | `UPDATE_CONTACT_INFO` + `UPDATE_EMERGENCY_CONTACTS` permissions defined. No patient-facing update endpoint. |
| Change password | ✅ | `PasswordResetController` exists. |
| Communication preferences | ❌ | No preference management. |
| **Priority** | **P0** | |

---

## 📊 Implementation Priority Matrix

### 🔴 P0 — Must Have (MyChart Core / Regulatory)

| # | Feature | Effort | Approach |
|---|---------|--------|----------|
| 1 | **Patient Profile (`/me/profile`)** | Small | New endpoint in MeController, reuse Patient entity |
| 2 | **View Own Lab Results** | Small 🔒 | Unlock `PatientLabResultController` for ROLE_PATIENT + scoped query |
| 3 | **View Own Medications** | Small 🔒 | Unlock `PatientMedicationController` + scoped query |
| 4 | **Health Summary Dashboard** | Medium | New `/me/health-summary` aggregation endpoint |
| 5 | **View Own Vital Signs** | Small 🔒 | Unlock `PatientVitalSignController` + scoped query |
| 6 | **View Billing Statements** | Small 🔒 | Add patient-scoped invoice endpoint |

### 🟡 P1 — Should Have (Patient Engagement / Compliance)

| # | Feature | Effort | Approach |
|---|---------|--------|----------|
| 7 | **Manage Consents** | Small 🔒 | Unlock `PatientConsentController` for patient |
| 8 | **Download Medical Records** | Medium 🔒 | Unlock `PatientRecordSharingController` + PDF generation |
| 9 | **View Care Team** | Medium | New endpoint + potentially structured CareTeam entity |
| 10 | **View Immunizations** | Small 🔒 | New patient-scoped endpoint using existing service |
| 11 | **Cancel/Reschedule Appointments** | Small | Add patient-facing cancel/reschedule in AppointmentController |
| 12 | **View Visit History (list encounters)** | Small | Add `/me/encounters` list endpoint |
| 13 | **View Treatment Plans** | Small 🔒 | Unlock for ROLE_PATIENT |
| 14 | **View Referral Status** | Small 🔒 | Unlock for ROLE_PATIENT |
| 15 | **Make Payments** | Large | Payment gateway integration required |

### 🟢 P2 — Nice to Have (Advanced MyChart Features)

| # | Feature | Effort | Approach |
|---|---------|--------|----------|
| 16 | Proxy/Family Access | Large | New entity model + delegation framework |
| 17 | Telehealth/Video Visits | Large | Third-party integration (Twilio, Zoom) |
| 18 | Pre-Visit Questionnaires | Medium | New entity model + form engine |
| 19 | Patient-Reported Outcomes | Medium | New entity model |
| 20 | Home Vital Sign Submission | Medium | New workflow (patient-reported flag) |
| 21 | Notification Preferences | Small | New preference entity |
| 22 | FHIR Export | Large | FHIR R4 resource mapping |
| 23 | Cost Estimation | Large | Complex pricing engine |
| 24 | Payment Plans | Medium | New workflow + entity |

---

## 🏗️ Recommended Implementation Strategy

### Phase 1: "Unlock the Vault" (1-2 weeks)
The fastest wins — the data and services already exist, you just need to:

1. **Add `ROLE_PATIENT` to `SecurityConfig.java`** for patient-scoped endpoints
2. **Create `/api/me/` patient endpoints** in `MeController` (or a new `PatientPortalController`):
   - `GET /me/profile` — own demographics
   - `PUT /me/profile` — update contact info, emergency contacts
   - `GET /me/lab-results` — own lab results
   - `GET /me/medications` — current medications
   - `GET /me/vitals` — vital signs
   - `GET /me/encounters` — visit history
   - `GET /me/consents` — manage data-sharing consents
   - `GET /me/immunizations` — vaccination history
   - `GET /me/billing/invoices` — view bills
   - `GET /me/care-team` — view assigned providers
   - `GET /me/treatment-plans` — active treatment plans
3. **Each endpoint resolves patient identity from `Authentication`** — no patient ID in URL (prevents IDOR attacks)
4. **Wire up existing `PATIENT_SELF_SERVICE` permissions** to `@PreAuthorize` checks

### Phase 2: "Close the Functional Gaps" (2-4 weeks)
5. Cancel/reschedule own appointments
6. Download medical records (PDF export)
7. After-visit summary view
8. Medication refill requests
9. Patient-submitted home vitals
10. Consent self-management (grant/revoke)

### Phase 3: "Advanced Patient Experience" (1-3 months)
11. Payment gateway integration
12. Telehealth / video visits
13. Proxy/family access
14. Pre-visit questionnaires
15. FHIR R4 export
16. Patient-reported outcomes

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
| `NotificationPreference` | Per-patient channel/category settings | Notification Settings |
| `AfterVisitSummary` | Post-encounter patient instructions | AVS |
| `RefillRequest` | Medication refill workflow | Rx Refill |
| `OnlineCheckIn` | Pre-visit digital check-in | E-Check-In |

---

## Summary

**Your HMS has an impressive backend — 80+ controllers, rich entity models, and a well-thought-out permission system.** The gap isn't in the clinical data modeling — it's that the patient portal is essentially a locked door. The `PATIENT_SELF_SERVICE` permission group defines 19 capabilities that patients *should* have, but only ~5 are actually accessible through endpoints.

**The good news:** Phase 1 is mostly a security configuration exercise. The services, repositories, and data models are already there. You're not building from scratch — you're opening the doors.
