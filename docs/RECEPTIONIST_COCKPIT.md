# Receptionist / Front-Desk Cockpit

> Implementation spec for MVPs 9, 10, and 11.
> Based on deep-research analysis of the DevFaso/hms codebase.

---

## Core philosophy (Epic model)

The receptionist cockpit is an **operational queue-management workspace**, not a clinical view.

It must answer:
- Who is scheduled today and what is their current status?
- Who has arrived and is waiting?
- Who has insurance issues blocking clearance?
- Who owes a copay?
- Did a walk-in come in without an appointment?

It is **not** a charting tool. It is a **patient flow + clearance + routing** tool.

---

## Data model strategy

We purposely avoid adding `ARRIVED` / `CHECKED_IN` to `AppointmentStatus` to prevent
destabilizing the scheduling and clinical modules. Instead we follow FHIR guidance:

| Front-desk status | Backed by |
|---|---|
| **Scheduled** | `Appointment.status` ∈ `{SCHEDULED, CONFIRMED, PENDING}` |
| **Arrived / Waiting** | `Encounter.status = ARRIVED` (bound to appointment via `appointmentId`) |
| **Checked In** | `Encounter.status = IN_PROGRESS` |
| **No-show** | `Appointment.status = NO_SHOW` |
| **Walk-in** | `Encounter` with `appointmentId = null`, `status = ARRIVED` |
| **Completed** | `Appointment.status = COMPLETED` or `Encounter.status = COMPLETED` |

`EncounterController` already allows `ROLE_RECEPTIONIST` to create encounters defaulted to
`ARRIVED`. "Mark Arrived" = `POST /api/encounters` with `appointmentId` binding.

---

## Screen layout

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  HEADER: "Front Desk" │  📅 Date picker │ 🏥 Dept filter │ 👨‍⚕️ Provider filter │
│                       │                │               │  + Walk-in  ⚡      │
├────────────┬───────────┬───────────┬────────────┬──────────────────────────┤
│ SCHEDULED  │  ARRIVED  │  WAITING  │ IN PROGRESS│  NO-SHOW  │  COMPLETED   │
│     42     │    11     │     7     │     4      │     3     │     8        │
├────────────┴───────────┴───────────┴────────────┴───────────┴──────────────┤
│ QUEUE TABS: All │ Insurance Issues 🔴3 │ Payment Due 🔴2 │ Waitlist         │
├─────────────────────────────────────────────────────────────────────────────┤
│ TIME  │ PATIENT         │ MRN      │ PROVIDER    │ STATUS     │ ACTIONS     │
│ 11:30 │ TIEGO B.        │ HBX1481  │ Dr. DD      │ ● ARRIVED  │ [Snapshot]  │
│ 11:45 │ Jane D.         │ HBX1900  │ Dr. DD      │ ● SCHEDULED│ [Arrived]   │
│ 12:00 │ John S. 🔴      │ HBX2100  │ Dr. CB      │ ● SCHEDULED│ [Arrived]   │
│       │  └ Insurance expired                                               │
├─────────────────────────────────────────────────────────────────────────────┤
│ PATIENT SNAPSHOT DRAWER (right slide, opens on row click)                  │
│  ┌──────────────┐  ┌───────────────────┐  ┌─────────────────┐             │
│  │ Demographics │  │ Insurance         │  │ Billing         │             │
│  │ TIEGO B.     │  │ CNSS - Active     │  │ 2 open invoices │             │
│  │ MRN HBX1481  │  │ Expires 2027-01   │  │ Balance: $150   │             │
│  │ DOB 1990-01  │  │ ✅ Primary flag   │  │ [Collect $25]   │             │
│  │ Phone +226.. │  │ [Verify Coverage] │  │                 │             │
│  └──────────────┘  └───────────────────┘  └─────────────────┘             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## API surface (all endpoints require `X-Hospital-Id` header)

### MVP 9 — Core Queue

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/reception/dashboard/summary?date=` | Summary counts strip |
| `GET` | `/api/reception/queue?date=&status=&departmentId=&providerId=` | Paginated queue |
| `GET` | `/api/reception/patients/{id}/snapshot` | Patient front-desk snapshot |
| `POST` | `/api/encounters` *(existing)* | Mark Arrived → creates ARRIVED encounter |

### MVP 10 — Clearance

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/reception/insurance/issues?date=` | Missing / expired / no-primary coverage |
| `GET` | `/api/reception/payments/pending?date=` | Invoices with `balanceDue > 0` |
| `POST` | `/api/billing-invoices/{id}/payments` | Front-desk payment collection (new) |
| `GET` | `/api/reception/flow-board?date=&departmentId=` | Kanban columns |
| `POST` | `/api/encounters` *(existing, appointmentId=null)* | Walk-in |

### MVP 11 — Advanced

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/reception/patients/duplicate-candidates?name=&dob=&phone=` | EMPI-backed dup detection |
| `POST` | `/api/reception/waitlist` | Add to waitlist |
| `GET` | `/api/reception/waitlist?departmentId=&status=` | View waitlist |
| `POST` | `/api/reception/waitlist/{id}/offer` | Convert to appointment |
| `POST` | `/api/reception/waitlist/{id}/close` | Close entry |
| `POST` | `/api/reception/insurance/{id}/attest-eligibility` | Manual verification stub |

---

## RBAC requirements

| Role | Access |
|------|--------|
| `ROLE_RECEPTIONIST` | All `/api/reception/**` endpoints (hospital-scoped) |
| `ROLE_HOSPITAL_ADMIN` | All endpoints (same hospital scope) |
| `ROLE_SUPER_ADMIN` | Unscoped cross-hospital access |

New permission string to add: `"Front Desk Access"` → controls sidebar visibility.

---

## Angular file structure

```
hospital-portal/src/app/
  reception/
    reception-cockpit.ts / .html / .scss          ← main page
    reception-queue-table.ts / .html / .scss       ← queue table + actions
    patient-snapshot-drawer.ts / .html / .scss     ← right-side detail panel
    insurance-issues-panel.ts / .html              ← MVP 10
    payment-pending-panel.ts / .html               ← MVP 10
    flow-board.ts / .html / .scss                  ← MVP 10 (static), MVP 11 (drag)
    walkin-dialog.ts / .html                       ← MVP 10
    duplicate-warning.ts / .html                   ← MVP 11
    waitlist-panel.ts / .html / .scss              ← MVP 11
    reception.service.ts                           ← HTTP calls to /api/reception/**
```

Route to add in `app.routes.ts`:
```typescript
{ path: 'reception', component: ReceptionCockpitComponent, canActivate: [AuthGuard] }
```

Nav item to add in `shell.ts` (inside `baseNavItems()`, under Appointments):
```typescript
{ icon: 'local_activity', label: 'Front Desk', route: '/reception', permission: 'Front Desk Access' }
```

---

## Backend file structure

```
hospital-core/src/main/java/com/example/hms/
  controller/
    ReceptionController.java
  service/
    ReceptionService.java
    impl/ReceptionServiceImpl.java
  dto/
    ReceptionDashboardSummaryDTO.java
    ReceptionQueueItemDTO.java
    FrontDeskPatientSnapshotDTO.java
    InsuranceIssueDTO.java          ← MVP 10
    WaitlistEntryDTO.java           ← MVP 11
    WaitlistRequestDTO.java         ← MVP 11
  model/
    AppointmentWaitlist.java        ← MVP 11 (new entity)
  repository/
    AppointmentWaitlistRepository.java  ← MVP 11
```

Liquibase migration for MVP 11:
```xml
<changeSet id="V-reception-waitlist" author="devfaso">
  <createTable tableName="appointment_waitlist" schemaName="scheduling">
    <column name="id" type="UUID"><constraints primaryKey="true"/></column>
    <column name="hospital_id" type="UUID"><constraints nullable="false"/></column>
    <column name="department_id" type="UUID"><constraints nullable="false"/></column>
    <column name="patient_id" type="UUID"><constraints nullable="false"/></column>
    <column name="preferred_provider_id" type="UUID"/>
    <column name="requested_date_from" type="DATE"/>
    <column name="requested_date_to" type="DATE"/>
    <column name="priority" type="VARCHAR(20)" defaultValue="ROUTINE"><constraints nullable="false"/></column>
    <column name="reason" type="TEXT"/>
    <column name="status" type="VARCHAR(20)" defaultValue="WAITING"><constraints nullable="false"/></column>
    <column name="created_at" type="TIMESTAMPTZ" defaultValueComputed="now()"><constraints nullable="false"/></column>
    <column name="updated_at" type="TIMESTAMPTZ" defaultValueComputed="now()"><constraints nullable="false"/></column>
    <column name="created_by" type="VARCHAR(255)"/>
  </createTable>
</changeSet>
```

Eligibility attestation columns (MVP 11 migration, on `patient_insurance`):
```sql
ALTER TABLE patient_insurance
  ADD COLUMN verified_at TIMESTAMPTZ,
  ADD COLUMN verified_by VARCHAR(255),
  ADD COLUMN eligibility_notes TEXT;
```

---

## Known risks

| Risk | Mitigation |
|------|-----------|
| `AppointmentStatus` missing `ARRIVED` | Use Encounter as arrival signal — no enum change needed |
| Billing RBAC blocks receptionist payment | New `POST /billing-invoices/{id}/payments` endpoint with controlled RBAC |
| Insurance financial fields mapped as zeros | Eligibility attestation stub in MVP 11; real payer integration deferred |
| Performance (many parallel API calls) | Reception façade aggregates data server-side; one call per panel |
| Walk-in without provider | Encounter `staffId` required — receptionist picks triage/on-call provider from dept list |
