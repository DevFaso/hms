# Hospital Management System - MVP Breakdown

## MVP Overview

| MVP | Name | Duration | Priority | Status |
|-----|------|----------|----------|--------|
| **MVP 1** | Foundation & User Management | 2-3 weeks | Critical | ✅ Done |
| **MVP 2** | Patient Management | 2-3 weeks | Critical | ✅ Done |
| **MVP 3** | Appointment Scheduling | 2-3 weeks | High | ✅ Done |
| **MVP 4** | Clinical Encounters & Documentation | 3-4 weeks | High | ✅ Done |
| **MVP 5** | Lab Orders & Results | 2-3 weeks | High | ✅ Done |
| **MVP 6** | Billing & Invoicing | 3-4 weeks | Medium | ✅ Done |
| **MVP 7** | Multi-Hospital Operations | 2-3 weeks | Medium | ✅ Done |
| **MVP 8** | Advanced Clinical Features | 4-5 weeks | Low | ✅ Done |
| **MVP 9** | Receptionist / Front-Desk Cockpit — Phase 1 (Core Queue) | 2-3 weeks | High | ✅ Done |
| **MVP 10** | Receptionist / Front-Desk Cockpit — Phase 2 (Operational Clearance) | 3-4 weeks | High | ✅ Done |
| **MVP 11** | Receptionist / Front-Desk Cockpit — Phase 3 (Advanced Workflows) | 3-4 weeks | Medium | ✅ Done |
| **MVP 12** | Nurse Station Cockpit — Phase 2 (Workboard + Flow Board + Vitals Capture) | 2-3 weeks | High | ✅ Done |
| **MVP 13** | Nurse Station Cockpit — Phase 3 (Task Board + Inbox + Care Notes) | 2-3 weeks | High | ✅ Done |
| **MVP 14** | Patient Portal Phase 3 — Proxy/Family Access + Notification Preferences | 2-3 weeks | Medium | ✅ Done |

---

## MVP 1: Foundation & User Management
- Authentication & Authorization (JWT, RBAC)
- User CRUD, Role Management, Permission Matrix
- System Administration & Audit Logging
- Internationalization (EN/FR/ES)

## MVP 2: Patient Management
- Patient Registration & Demographics
- Medical History, Insurance Management
- Patient Search & Directory

## MVP 3: Appointment Scheduling
- Staff Availability, Appointment Booking
- Calendar Views, Conflict Detection

## MVP 4: Clinical Encounters & Documentation
- Encounter Management, SOAP Notes
- Prescription Management, Clinical Workflows

## MVP 5: Lab Orders & Results
- Lab Test Catalog, Order Management
- Result Entry & Review, Lab Workspace

## MVP 6: Billing & Invoicing
- Invoice Management, Payment Processing

## MVP 7: Multi-Hospital Operations
- Organization Hierarchy, Hospital Portfolio

## MVP 8: Advanced Clinical Features
- OB/GYN, Imaging, Clinical Decision Support

---

## MVP 9: Receptionist / Front-Desk Cockpit — Phase 1 (Core Queue)

> **Scope**: Replace the thin receptionist fragment inside the generic `/dashboard` with a
> dedicated **Front Desk** workspace at `/reception`. Deliver the queue-driven cockpit, arrival
> check-in, and the patient front-desk snapshot drawer.

### Why this is needed (gap analysis)

The existing receptionist experience is a disconnected set of fragments spread across
`/dashboard`, `/patients`, `/appointments`, and `/billing`. Key deficiencies identified:

- "Checked In" metric is incorrectly computed from `AppointmentStatus.COMPLETED` — there is no
  `ARRIVED` / `CHECKED_IN` state in `AppointmentStatus`.
- No dedicated `/reception` route or nav item.
- No aggregated queue DTOs — the portal makes 4+ separate API calls to reconstruct the
  front-desk picture.
- No patient front-desk snapshot (demographics + insurance + balance + alerts on one panel).
- `AppointmentStatus` enum missing `ARRIVED`/`CHECKED_IN` states (design drift from docs).

**Data model strategy** (no new DB migration needed for MVP 9):

| Front-desk status | Backed by |
|---|---|
| Scheduled | `Appointment.status` ∈ `{SCHEDULED, CONFIRMED, PENDING}` |
| Arrived / Waiting | `Encounter.status = ARRIVED` (created by receptionist, `appointmentId` bound) |
| Checked In / Sent to Clinic | `Encounter.status = IN_PROGRESS` |
| No-show | `Appointment.status = NO_SHOW` |
| Completed | `Appointment.status = COMPLETED` or `Encounter.status = COMPLETED` |

This follows FHIR guidance: **Appointment** = planning, **Encounter** = actual arrival.
`EncounterController` already allows `ROLE_RECEPTIONIST` to create encounters defaulted to
`ARRIVED` — this is the "Mark Arrived / Check In" hook.

### Backend deliverables

#### New files
- `ReceptionController.java` — REST controller, all routes under `/api/reception/**`
- `ReceptionService.java` + `ReceptionServiceImpl.java`
- DTOs:
  - `ReceptionDashboardSummaryDTO`
  - `ReceptionQueueItemDTO`
  - `FrontDeskPatientSnapshotDTO`

#### API endpoints

| Method | Path | Auth roles | Description |
|--------|------|-----------|-------------|
| `GET` | `/api/reception/dashboard/summary?date=YYYY-MM-DD` | RECEPTIONIST, HOSPITAL_ADMIN, SUPER_ADMIN | Counts: scheduled, arrived, waiting, in-progress, no-show, completed |
| `GET` | `/api/reception/queue?date=YYYY-MM-DD&status=ALL\|SCHEDULED\|ARRIVED\|IN_PROGRESS\|NO_SHOW\|COMPLETED&departmentId=&providerId=` | RECEPTIONIST, HOSPITAL_ADMIN | Paginated queue with computed status per row |
| `GET` | `/api/reception/patients/{patientId}/snapshot` | RECEPTIONIST, HOSPITAL_ADMIN | Demographics + insurance summary + billing alerts |

All endpoints must call `RoleValidator.requireActiveHospitalId()` and scope to
`X-Hospital-Id` from the request context.

#### `ReceptionDashboardSummaryDTO` shape
```json
{
  "date": "2026-03-15",
  "hospitalId": "uuid",
  "scheduledToday": 42,
  "arrivedCount": 11,
  "waitingCount": 7,
  "inProgressCount": 4,
  "noShowCount": 3,
  "completedCount": 8
}
```

#### `ReceptionQueueItemDTO` shape (per item)
```json
{
  "appointmentId": "uuid",
  "patientId": "uuid",
  "patientName": "TIEGO B OUEDRAOGO",
  "mrn": "HBX1481",
  "dateOfBirth": "1990-01-01",
  "appointmentTime": "11:30",
  "providerName": "DoctorBF DoctorBL",
  "departmentName": "General Medicine",
  "visitType": "FOLLOW_UP",
  "status": "ARRIVED",
  "waitMinutes": 18,
  "encounterId": "uuid or null"
}
```

#### `FrontDeskPatientSnapshotDTO` shape
```json
{
  "patientId": "uuid",
  "fullName": "TIEGO B OUEDRAOGO",
  "mrn": "HBX1481",
  "dob": "1990-01-01",
  "phone": "+226...",
  "insurance": {
    "hasActiveCoverage": true,
    "primaryPayer": "CNSS",
    "expiresOn": "2027-01-01"
  },
  "billing": {
    "openInvoiceCount": 2,
    "totalBalanceDue": 150.00
  },
  "alerts": {
    "incompleteDemographics": false,
    "missingInsurance": false,
    "expiredInsurance": false
  }
}
```

#### Check-in action (no new endpoint)
"Mark Arrived" reuses the existing `POST /api/encounters` which already:
- Allows `ROLE_RECEPTIONIST`
- Defaults `status = ARRIVED` and `startTime = now()` when null
- Binds via `appointmentId` in the request body

### Frontend deliverables

#### New files (standalone Angular components)
```
hospital-portal/src/app/reception/
  reception-cockpit.ts / .html / .scss
  reception-queue-table.ts / .html / .scss
  patient-snapshot-drawer.ts / .html / .scss
  reception.service.ts
```

#### Navigation
- Add `{ icon: 'local_activity', label: 'Front Desk', route: '/reception' }` to `shell.ts`
  `baseNavItems()`, gated by role check `ROLE_RECEPTIONIST` or permission `Front Desk Access`.
- Add route `{ path: 'reception', component: ReceptionCockpitComponent, canActivate: [AuthGuard] }` to `app.routes.ts`.

#### Page layout (3-zone)

```
┌──────────────────────────────────────────────────────────────┐
│  HEADER STRIP  │ Date picker │ Dept filter │ Provider filter │
├────────────┬───────────┬───────────┬────────────┬────────────┤
│ SCHEDULED  │  ARRIVED  │  WAITING  │  IN PROGR. │  NO-SHOW   │
│    42      │    11     │     7     │     4      │     3      │
├────────────┴───────────┴───────────┴────────────┴────────────┤
│  QUEUE TABLE: Time │ Patient │ MRN │ Provider │ Status │ ⚡   │
│  11:30  TIEGO B.  HBX1481  Dr DD  ● ARRIVED  [Check In]     │
│  11:45  Jane D.   HBX1900  Dr DD  ● SCHEDULED [Mark Arrived] │
│  ...                                                          │
├──────────────────────────────────────────────────────────────┤
│  PATIENT SNAPSHOT DRAWER (slides in on row click)            │
│  Demographics │ Insurance │ Balance │ Alerts                 │
└──────────────────────────────────────────────────────────────┘
```

#### Queue table actions per status
| Status | Primary action | Secondary |
|--------|---------------|-----------|
| SCHEDULED | Mark Arrived → `POST /encounters` | Reschedule |
| ARRIVED / WAITING | View Snapshot | — |
| COMPLETED | View Encounter | — |
| NO_SHOW | — | Reschedule |

#### Patient snapshot drawer
- Opens as a right-side overlay on row click
- Calls `GET /api/reception/patients/{id}/snapshot`
- Shows: name / MRN / DOB / phone
- Insurance card: payer name, expiry, active/expired badge
- Billing card: open invoices count, total balance due
- Alert chips: `Missing Insurance`, `Expired Insurance`, `Incomplete Demographics`

### Tests (MVP 9)

**Backend (JUnit)**:
- `ReceptionServiceImpl` queue computation for all 5 status combinations
- Hospital scoping: query with no `X-Hospital-Id` returns 403 for `ROLE_RECEPTIONIST`
- `POST /api/encounters` from receptionist creates `ARRIVED` encounter bound to `appointmentId`

**Frontend (Karma)**:
- `ReceptionService` HTTP calls pass correct date/filter params
- Queue table renders correct action button per status

**E2E (Playwright)**:
- Receptionist login → sees "Front Desk" nav → cockpit loads with today's queue
- "Mark Arrived" → row moves to ARRIVED state
- Clicking row → snapshot drawer opens, insurance/billing data renders

### Acceptance criteria (MVP 9 done)
- [x] `/reception` route exists and is visible only to `ROLE_RECEPTIONIST` / `ROLE_HOSPITAL_ADMIN`
- [x] Summary strip counts are computed from Appointment + Encounter (not just AppointmentStatus)
- [x] "Mark Arrived" posts `POST /api/encounters` with correct `appointmentId` binding
- [x] Patient snapshot shows insurance + billing alerts
- [x] All API calls carry `X-Hospital-Id`; wrong hospital cannot see another hospital's queue
- [x] CI: format ✅ lint ✅ tests ✅

> ✅ **Shipped** — commit `ddbbb71` (2026-03-15)

---

## MVP 10: Receptionist / Front-Desk Cockpit — Phase 2 (Operational Clearance)

> **Scope**: Add insurance issues panel, front-desk payment collection, flow board (visual patient
> movement board), and walk-in handling. This is the "clearance before clinic" phase.

### Backend deliverables

#### Insurance issues panel

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/reception/insurance/issues?date=YYYY-MM-DD` | RECEPTIONIST, HOSPITAL_ADMIN | Patients today with missing/expired/no-primary insurance |

Logic: join today's appointments → `PatientInsurance` records → return rows where:
- no insurance record exists, **or**
- `expirationDate < today`, **or**
- no record with `primary = true`

"Verify Insurance" action reuses existing `POST /api/patient-insurances/link` (upsert).

#### Staff payment posting

New endpoint:

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/billing-invoices/{invoiceId}/payments` | RECEPTIONIST, BILLING_SPECIALIST, HOSPITAL_ADMIN | Front-desk copay / balance collection |

Request body:
```json
{ "amount": 25.00, "method": "CASH", "reference": "optional", "notes": "optional" }
```

Implementation: create `recordStaffPayment(invoiceId, amount, actorUserId, method, notes)` in
`BillingInvoiceService`. Fetch invoice, verify hospital scope, reduce `balanceDue`, update
status (`PARTIALLY_PAID` / `PAID`), log audit event. Do **not** reuse the patient-portal
`recordPayment` path (ownership check conflict).

#### Flow board

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/reception/flow-board?date=YYYY-MM-DD&departmentId=` | RECEPTIONIST, HOSPITAL_ADMIN | Kanban columns with patient cards |

Columns computed from same Appointment + Encounter state machine as MVP 9.

#### Walk-in encounter

No new endpoint: reuse `POST /api/encounters` with `appointmentId = null`.
Frontend adds a "Walk-in" button that opens a mini-form (patient search + department picker)
and posts an encounter without an appointment binding.

### Frontend deliverables

#### Insurance Issues panel
- New tab/side panel: "Insurance Issues" showing today's patients with coverage problems
- "Verify Insurance" opens a modal, calls existing insurance upsert endpoint
- Badge count on the tab title

#### Payment Pending panel
- "Payment Due" tab lists invoices with `balanceDue > 0` and status `SENT` / `PARTIALLY_PAID`
- "Collect Payment" modal: amount input + payment method select (CASH / CARD / INSURANCE)
- Posts to new `POST /api/billing-invoices/{id}/payments`

#### Visual Flow Board
- Kanban-style board: 5 columns (Scheduled → Arrived → Checked In → No-show → Completed)
- Each column shows patient cards with name, time, wait duration chip
- MVP 10: static columns (no drag/drop); drag/drop deferred to MVP 11

#### Walk-in button
- Floating action button "+ Walk-in" on cockpit header
- Opens: patient search (existing component) + department picker
- On confirm: `POST /api/encounters` with `appointmentId = null`
- Walk-in card appears in "Arrived" column of flow board

### Tests (MVP 10)

**Backend**:
- Insurance issues endpoint returns correct rows for each gap scenario
- Staff payment POST reduces `balanceDue`, updates invoice status, stores audit event
- Hospital isolation: receptionist cannot post payment on invoice from different hospital
- Walk-in encounter creation without `appointmentId` succeeds for `ROLE_RECEPTIONIST`

**Frontend**:
- Insurance issues panel renders badge count from API
- Payment modal: validates amount > 0 before submitting
- Flow board renders 5 columns with correct patient counts

**E2E**:
- Insurance issue → Verify Coverage modal → data saves
- Collect copay → invoice `balanceDue` decrements in UI
- Walk-in button → encounter created → card in Arrived column

### Acceptance criteria (MVP 10 done)
- [x] Insurance Issues panel lists all 3 gap types (missing / expired / no primary)
- [x] "Verify Insurance" saves correctly via existing upsert endpoint
- [x] Receptionist can collect a copay; invoice balance updates in real time
- [x] Payment endpoint enforces hospital scope and writes an audit event
- [x] Flow board shows columns populated from live queue data
- [x] Walk-in encounter created without appointment binding; appears in Arrived column
- [x] CI: format ✅ lint ✅ tests ✅

> ✅ **Shipped** — commit `ddbbb71` (2026-03-15)

---

## MVP 11: Receptionist / Front-Desk Cockpit — Phase 3 (Advanced Workflows)

> **Scope**: Duplicate identity warnings at registration, waitlist management, eligibility
> attestation stub, and flow-board drag-and-drop. These are the "prevent errors and reduce
> call-backs" features.

### Backend deliverables

#### Duplicate identity detection

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/reception/patients/duplicate-candidates?name=&dob=&phone=` | RECEPTIONIST | Returns possible matches with confidence score |

Uses existing EMPI subsystem (`EmpiStatus.DUPLICATE` + identity linking logic).
`POST /api/patients` registration should optionally return `warnings.possibleDuplicates[]`
when candidates exist (non-blocking by default; receptionist confirms intent).

#### Waitlist

New Liquibase migration (V-next after current max):

```sql
CREATE TABLE scheduling.appointment_waitlist (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  hospital_id     UUID NOT NULL REFERENCES hospital(id),
  department_id   UUID NOT NULL REFERENCES department(id),
  patient_id      UUID NOT NULL REFERENCES patient(id),
  preferred_provider_id UUID REFERENCES staff(id),
  requested_date_from DATE,
  requested_date_to   DATE,
  priority        VARCHAR(20) NOT NULL DEFAULT 'ROUTINE',
  reason          TEXT,
  status          VARCHAR(20) NOT NULL DEFAULT 'WAITING',
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by      VARCHAR(255)
);
```

Endpoints:

---

## MVP 12: Nurse Station Cockpit — Phase 2 (Workboard + Flow Board + Vitals Capture)

> **Scope**: Extend the nurse station `/nurse` route (built in MVP 12 Phase 1) with three new
> operational views — a patient **Workboard**, a **Flow Board** (Kanban by admission status),
> and an **Admissions panel** — plus an inline bedside **Vitals Capture** dialog that writes
> directly to `clinical.patient_vital_signs` with automatic clinical-significance flagging.

### Gaps addressed

| Gap | Solution |
|-----|---------|
| No at-a-glance view of all assigned patients | Workboard card grid with acuity badges and due-indicator chips |
| No Kanban-style flow tracking | Flow Board with 4 columns: Pending / Active / Critical / Awaiting Discharge |
| Vitals captured via a separate screen | Inline modal form reachable from any patient card in one click |
| No pending-admissions list for charge nurse | Dedicated Admissions tab showing all non-discharged admissions |

### Data model strategy

No new tables.  MVP 12 reads and writes exclusively into existing tables:

| Table | Used by |
|-------|---------|
| `admissions` | Workboard, Flow Board, Admissions tab |
| `clinical.patient_vital_signs` | Vitals Capture (write), Workboard last-vitals chip (read) |

**New Flyway migration** — `V20__mvp12_nurse_station_phase2.sql`:
- `CREATE INDEX IF NOT EXISTS idx_admissions_hospital_status` on `admissions(hospital_id, status)` — speeds up the active-admissions-by-hospital query used by all three views
- `CREATE INDEX IF NOT EXISTS idx_pvs_patient_hospital_recorded` on `clinical.patient_vital_signs(patient_id, hospital_id, recorded_at DESC)` — speeds up the latest-vitals lookup per patient

### Backend deliverables

#### New DTOs (5)

| DTO | Purpose |
|-----|---------|
| `NurseWorkboardPatientDTO` | One card on the Workboard: patient name/MRN, room-bed, acuity, vitals-due flag, meds-due count |
| `NurseFlowPatientCardDTO` | One card on the Flow Board: patient name/MRN, acuity, wait-minutes, room-bed |
| `NurseFlowBoardDTO` | Container for the four Flow Board columns (`pending`, `active`, `critical`, `awaitingDischarge`) |
| `NurseVitalCaptureRequestDTO` | Request body for recording bedside vitals (8 vital fields + free-text notes) |
| `NurseAdmissionSummaryDTO` | One row in the Admissions table: status, acuity, room, doctor, admission type |

#### New API endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/nurse/workboard` | NURSE | Patient cards for the calling nurse's assigned ward |
| `GET` | `/api/nurse/patient-flow?departmentId=` | NURSE | Flow Board DTO with 4 column lists |
| `POST` | `/api/nurse/patients/{patientId}/vitals` | NURSE | Capture bedside vitals; returns 204 No Content |
| `GET` | `/api/nurse/admissions/pending?departmentId=` | NURSE | Non-discharged admissions for the ward |

All endpoints require `X-Hospital-Id` header (hospital-scoped).

#### Service implementation highlights

**Clinical significance auto-flagging** (`isClinicallySignificant()` in `NurseTaskServiceImpl`):

| Vital | Critical low | Critical high |
|-------|-------------|--------------|
| Heart rate | < 40 bpm | > 150 bpm |
| SpO₂ | < 90 % | — |
| Respiratory rate | < 8 rpm | > 30 rpm |
| Systolic BP | < 80 mmHg | > 200 mmHg |
| Diastolic BP | — | > 120 mmHg |
| Temperature | < 35 °C | > 40 °C |
| Blood glucose | < 50 mg/dL | > 400 mg/dL |

Any flag sets `PatientVitalSign.clinicallySignificant = true` and `source = "NURSE_STATION"`.

### Frontend deliverables

#### nurse-station.ts

- Extended `SectionType` union: `'vitals' | 'medications' | 'orders' | 'handoffs' | 'workboard' | 'flowboard' | 'admissions'`
- New signals: `workboard`, `flowBoard`, `pendingAdmissions`, `vitalsCaptureFor`, `vitalsForm`, `vitalsCapturing`
- `ngOnInit` uses `forkJoin` to load Workboard + Flow Board + Pending Admissions alongside existing MAR / vitals-due / orders data in one parallel round-trip
- `recordVitals(patientId, patientName?)` opens inline vitals dialog instead of navigating away
- Helper methods: `acuityLabel(level)`, `acuityCssClass(level)`, `closeVitalsDialog()`, `updateVitalsField()`, `updateVitalsNotes()`, `submitVitals()`

#### nurse-station.html

Three new tab buttons added to the tab bar:

| Tab | Content |
|-----|---------|
| **Workboard** | Responsive card grid; each card: acuity badge, room/bed, "last vitals" or "vitals overdue" chip, meds-due count, "Record Vitals" + "Chart" action buttons |
| **Flow Board** | 4-column Kanban (Pending grey / Active blue / Critical red / Awaiting Discharge purple); each card shows wait-time pill |
| **Admissions** | Sortable table with status, acuity, room, doctor, type columns; discharge rows highlighted |

**Inline Vitals Capture modal** overlays on any patient card click:
- 8 numeric input fields (temp, HR, RR, SBP, DBP, SpO₂, glucose, weight)
- Free-text notes textarea
- Escape key closes; "Save Vitals" calls `submitVitals()` → POST → dialog auto-closes

#### nurse-station.scss

- Workboard grid layout (`auto-fill`, `minmax(280px, 1fr)`)
- Acuity badge variants: `.acuity-1` (red) through `.acuity-5` (green)
- Flow board 4-column CSS Grid with responsive collapse
- Vitals modal 2-column form grid

### Acceptance criteria (MVP 12 done)

- [x] `GET /api/nurse/workboard` returns patient cards scoped to the requesting nurse's hospital
- [x] `GET /api/nurse/patient-flow` returns four non-overlapping admission groups
- [x] `POST /api/nurse/patients/{id}/vitals` writes a `PatientVitalSign` row with `source = "NURSE_STATION"` and correctly flags `clinicallySignificant` for any out-of-range value
- [x] `GET /api/nurse/admissions/pending` returns only non-discharged admissions for the hospital
- [x] Angular Workboard tab renders card grid; "Record Vitals" button opens inline dialog
- [x] Vitals form submits to the POST endpoint and closes on 204 response
- [x] Flow Board renders 4 columns with correct patient counts
- [x] Admissions tab renders table with discharge-row highlight
- [x] V20 Flyway migration runs cleanly; composite indexes created with `IF NOT EXISTS`
- [x] Java build: `BUILD SUCCESSFUL`; Angular build: no errors

> ✅ **Shipped** — all builds passing (Java `BUILD SUCCESSFUL`, Angular bundle generated cleanly)

---

## MVP 13: Nurse Station Cockpit — Phase 3 (Task Board + Inbox + Care Notes)

> **Scope**: Complete the nurse station cockpit with three remaining features from the
> NurseDashboard spec: §7 Nursing Task Board, §9 Communication Inbox, and Quick Care Notes
> (DAR / SOAPIE documentation at the bedside).

### New entity

| Table | Schema | Purpose |
|-------|--------|---------|
| `nursing_tasks` | `clinical` | Bedside care tasks (dressing changes, IV checks, pain reassessments, etc.) |

### New endpoints (MVP 13)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/nurse/tasks?status=&hospitalId=` | NURSE | Nursing task board (filterable by status) |
| `POST` | `/api/nurse/tasks` | NURSE | Create a new nursing task |
| `PUT` | `/api/nurse/tasks/{taskId}/complete` | NURSE | Mark task as COMPLETED |
| `GET` | `/api/nurse/inbox?limit=` | NURSE | Communication inbox (notifications) |
| `PATCH` | `/api/nurse/inbox/{itemId}/read` | NURSE | Mark inbox item as read |
| `POST` | `/api/nurse/patients/{patientId}/care-note` | NURSE | Quick care note (DAR or SOAPIE) |

### New DTOs

- `NurseTaskItemDTO` — Task board card data
- `NurseTaskCreateRequestDTO` — Create task payload
- `NurseTaskCompleteRequestDTO` — Complete task payload (optional note)
- `NurseInboxItemDTO` — Inbox notification item
- `NurseCareNoteRequestDTO` — Care note with DAR/SOAPIE template
- `NurseCareNoteResponseDTO` — Saved care note summary

### Frontend additions

- **Task Board tab** — Card-based task list with priority badges (STAT/URGENT/ROUTINE), overdue indicators, complete action
- **Inbox tab** — Notification list with unread badge counter, mark-read, mark-all-read
- **Task Create modal** — Patient ID, category dropdown, description, priority, due date
- **Care Note modal** — Template toggle (DAR/SOAPIE), structured fields, narrative, title
- **SCSS** — Task cards, priority colours, inbox read/unread styles, template toggle, modal sizes

### Database migration

- `V21__mvp13_nursing_tasks.sql` — Creates `clinical.nursing_tasks` table with indexes
- Registered in `changelog.xml` as changeSet `V21-mvp13-nursing-tasks`
- Also registered previously missing V19 and V20 changeSets in changelog.xml

### Acceptance criteria (MVP 13 done)

- [x] `GET /api/nurse/tasks` returns tasks scoped to the nurse's hospital
- [x] `POST /api/nurse/tasks` creates a NursingTask with resolved nurse name
- [x] `PUT /api/nurse/tasks/{id}/complete` sets COMPLETED status + timestamp
- [x] `GET /api/nurse/inbox` returns notifications for the authenticated user
- [x] `PATCH /api/nurse/inbox/{id}/read` marks a notification as read
- [x] `POST /api/nurse/patients/{id}/care-note` creates a NursingNote with DAR or SOAPIE template
- [x] Angular Task Board tab renders task cards with priority badges and overdue indicators
- [x] Angular Inbox tab shows unread badge count; items toggle read state
- [x] Task Create modal submits and refreshes the task board
- [x] Care Note modal supports DAR/SOAPIE template switching
- [x] V21 Flyway migration creates `clinical.nursing_tasks` table with indexes
- [x] Java build: `BUILD SUCCESSFUL`; Angular build: no errors

> ✅ **Shipped** — all builds passing (Java `BUILD SUCCESSFUL`, Angular bundle generated cleanly)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/reception/waitlist` | RECEPTIONIST, HOSPITAL_ADMIN | Add patient to waitlist |
| `GET` | `/api/reception/waitlist?departmentId=&status=WAITING` | RECEPTIONIST | View waitlist |
| `POST` | `/api/reception/waitlist/{id}/offer` | RECEPTIONIST | Convert waitlist entry → appointment |
| `POST` | `/api/reception/waitlist/{id}/close` | RECEPTIONIST | Remove/close waitlist entry |

#### Eligibility attestation stub

New columns on `PatientInsurance`:
- `verified_at TIMESTAMPTZ`
- `verified_by VARCHAR(255)`
- `eligibility_notes TEXT`

Endpoint:

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/reception/insurance/{insuranceId}/attest-eligibility` | RECEPTIONIST | Record manual verification with note |

This is a placeholder until a real payer/clearinghouse integration exists.

### Frontend deliverables

#### Duplicate identity warning during registration
- Intercept new patient save in receptionist context
- Call `GET /api/reception/patients/duplicate-candidates` on name + DOB input
- Show inline warning with matches (name / MRN / DOB / score)
- "Proceed anyway" confirmation before final `POST /api/patients`

#### Waitlist management panel
- New tab "Waitlist" in Front Desk cockpit
- Add to Waitlist form: patient search + department + date range + priority + reason
- Waitlist table with "Offer Slot" action → opens appointment-form pre-filled
- "Close" removes entry

#### Eligibility attestation
- "Mark Verified" button on insurance card in snapshot drawer
- Opens notes modal → POST to attestation endpoint
- Insurance card shows "Verified by [name] on [date]" chip

#### Flow board drag-and-drop
- Upgrade MVP 10 static flow board columns with CDK drag/drop
- Dragging to "Checked In" column triggers `PATCH /api/encounters/{id}/status` (`IN_PROGRESS`)
- Dragging to "No-show" triggers appointment status action `NO_SHOW`

### Tests (MVP 11)

**Backend**:
- Duplicate candidate query returns ranked matches; non-existent name returns empty
- Waitlist CRUD: add → list → offer (creates appointment) → close
- Eligibility attestation stores `verified_at`, `verified_by`; hospital scope enforced

**Frontend**:
- Duplicate warning renders on patient name/DOB input with ≥ 1 candidate
- Waitlist table: "Offer Slot" pre-fills appointment form with patient + provider
- Flow board drag: drop on "Checked In" calls correct PATCH endpoint

**E2E**:
- Register new duplicate patient → warning modal displayed → receptionist confirms
- Add to waitlist → slot offered → appointment created
- Drag card to Checked In column → encounter status = IN_PROGRESS

### Acceptance criteria (MVP 11 done)
- [x] Duplicate warning fires for near-matches during registration (non-blocking confirmation)
- [x] Duplicate detection event is logged in audit log
- [x] Waitlist: add / view / offer → appointment / close full cycle works
- [x] Eligibility attestation records verifier + timestamp, visible in snapshot drawer
- [x] Flow board drag-and-drop updates encounter/appointment status via API
- [x] CI: format ✅ lint ✅ tests ✅

> ✅ **Shipped** — all features verified implemented (backend service + frontend components)

---

## Receptionist Cockpit — Dependency Map

```
MVP 1  ──► (Auth / RBAC / Audit)
MVP 2  ──► (Patient Registration / Insurance)
MVP 3  ──► (Appointment Scheduling)
MVP 4  ──► (Encounters / ARRIVED status hook)
MVP 6  ──► (Invoice / Payment)
         │
         ▼
MVP 9  (Core Queue + Arrival Check-in + Patient Snapshot)
         │
         ▼
MVP 10 (Insurance Clearance + Payment Collection + Flow Board + Walk-in)
         │
         ▼
MVP 11 (Duplicate Warnings + Waitlist + Eligibility + Drag-drop Board)
```

## Entity Relationships (Front-Desk Façade)

```
PATIENT ──── APPOINTMENT ────── ENCOUNTER  (arrival binding via appointmentId)
   │               │                │
   │           DEPARTMENT       BILLING_INVOICE
   │
PATIENT_INSURANCE
   │
RECEPTION_QUEUE_ITEM  (computed DTO — not persisted)
```

---

## MVP 14: Patient Portal Phase 3 — Proxy/Family Access + Notification Preferences

### P2 Proxy / Family Access ✅
- **PatientProxy entity** (`clinical.patient_proxies`) — grantor patient → proxy user with relationship, permissions, status, expiry
- **Enums**: `ProxyRelationship` (PARENT, SPOUSE, CHILD, CAREGIVER, LEGAL_GUARDIAN, SIBLING, OTHER), `ProxyStatus` (ACTIVE, EXPIRED, REVOKED)
- **Endpoints** (PatientPortalController `/me/patient`):
  - `GET /proxies` — list proxies granted by authenticated patient
  - `POST /proxies` — grant proxy access (with duplicate/self-grant prevention)
  - `DELETE /proxies/{proxyId}` — revoke proxy access (ownership verification)
  - `GET /proxy-access` — list patients whose data I can view as proxy
- **DTOs**: `ProxyGrantRequestDTO`, `ProxyResponseDTO`
- **Angular**: `MyFamilyAccessComponent` (patient-portal/my-family-access.ts) — grant form, granted-by-me table, access-I-have tab, revoke action
- **Route**: `/my-family-access` (ROLE_PATIENT)

### P2 Notification Preferences ✅
- **NotificationPreference entity** (`security.notification_preferences`) — per-user (type × channel) enabled toggle
- **Enums**: `NotificationType` (APPOINTMENT_REMINDER, LAB_RESULT, MEDICATION_REFILL, BILLING, DISCHARGE_SUMMARY, PROXY_ACCESS, SYSTEM, GENERAL), `NotificationChannel` (IN_APP, EMAIL, SMS, PUSH)
- **Notification.type column** added to existing `security.notifications` table for categorisation
- **Extended NotificationService** with `getPreferences(userId)` / `updatePreferences(userId, updates)` (delete+replace strategy)
- **Extended NotificationController** (`/notifications`):
  - `GET /preferences` — get current user's notification preferences
  - `PUT /preferences` — bulk-update preferences (type × channel matrix)
- **Angular**: `NotificationSettingsComponent` (notifications/notification-settings.ts) — type × channel toggle grid, save action
- **Route**: `/notification-settings`
- **Notification list** updated with "Preferences" link in header

### Migration
- `V22__mvp14_proxy_access_notification_preferences.sql` — Liquibase changeset V22

