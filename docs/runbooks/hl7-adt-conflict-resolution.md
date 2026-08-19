# HL7 v2 ADT — conflict-resolution rules

Roadmap row 24 — `v1.1 / Interop HL7 / ADT^A01/A04/A08 → Admission/Encounter sync`.

This document is the source of truth for **how HMS resolves disagreements**
between an inbound HL7 v2 ADT message and the existing HMS state. It is
the half of row 24 that the schema migration (`V99`) and the projection
service (`MllpInboundAdtVisitProjectionService`) gesture at but cannot
fully encode in code: the policy, not the plumbing.

This runbook is what the integration on-call references when an analyzer
or registration system claims state HMS disagrees with.

---

## Activation gate

The reconciliation behaviour described here is **disabled by default**.
Set both of the following to turn it on:

| Env / property                               | Default | Effect when `true`                                                                    |
| -------------------------------------------- | ------- | ------------------------------------------------------------------------------------- |
| `APP_HL7_MLLP_ENABLED`                       | `false` | MLLP TCP listener accepts inbound traffic at all (existing v1.1 flag).                |
| `APP_HL7_ADT_VISIT_SYNC_ENABLED`             | `false` | Visit-sync projection runs after the demographic upsert (this roadmap row).           |
| `APP_HL7_ADT_VISIT_SYNC_LOG_UNMATCHED`       | `true`  | Emit a `WARN` for inbound visit numbers that don't match any HMS Admission/Encounter. |
| `APP_HL7_ADT_VISIT_SYNC_AUTO_CREATE_ENABLED` | `false` | Cluster-wide A01 auto-create (row-24 follow-on). Three-layer gate; see below.         |

**Three-layer gate for the row-24 auto-create follow-on.** All three
must be true for the projection service to actually provision an
Admission on an A01 with an unmatched visit-number triplet:

1. `APP_HL7_ADT_VISIT_SYNC_ENABLED=true` — master ADT projection on.
2. `APP_HL7_ADT_VISIT_SYNC_AUTO_CREATE_ENABLED=true` — cluster-wide
   auto-create on.
3. `platform.adt_intake_provider_configs.enabled = true` for the
   receiving hospital row — per-tenant opt-in.

Any single layer flipping off short-circuits back to the existing
`NO_MATCH` log-and-skip flow. Operators can stage a rollout one
hospital at a time by leaving layers 1+2 on cluster-wide and
flipping layer 3 per hospital.

When `APP_HL7_ADT_VISIT_SYNC_ENABLED=false` the pre-row-24
demographics-only flow is bit-for-bit unchanged. **The schema
changes in `V99` and `V103` are strictly additive** (nullable
columns + partial unique indexes + new config table) and run
whether or not any flag is on.

### Per-hospital intake config

Populate one row per hospital that should auto-create from ADT:

```sql
INSERT INTO platform.adt_intake_provider_configs (
    id, hospital_id, admitting_provider_id, department_id,
    default_admission_type, default_acuity_level,
    default_encounter_type, default_chief_complaint, enabled
) VALUES (
    gen_random_uuid(),
    '<hospital-uuid>',
    '<staff-uuid — provider on duty for ADT-driven intake>',
    '<department-uuid OR NULL>',
    'EMERGENCY',        -- one of: EMERGENCY, ELECTIVE, URGENT, OBSERVATION, ...
    'LEVEL_2_MODERATE', -- one of: LEVEL_1_MINIMAL, LEVEL_2_MODERATE, LEVEL_3_MAJOR, LEVEL_4_SEVERE, LEVEL_5_CRITICAL
    'INPATIENT',        -- one of: CONSULTATION, INPATIENT, EMERGENCY, FOLLOW_UP, ...
    'Auto-created from ADT^A01',
    true
);
```

For hospitals that also want A04 (Encounter) auto-create, set the
`default_assignment_id` column from V104:

```sql
UPDATE platform.adt_intake_provider_configs
   SET default_assignment_id = '<security.user_role_hospital_assignment.id>'
 WHERE hospital_id = '<hospital-uuid>';
```

The chosen assignment row's `hospital_id` MUST equal the config
row's `hospital_id` — the service-layer guard refuses A04
auto-create when they diverge (defence in depth on
`Encounter#validate`, which also rejects the write at JPA
`@PrePersist`). Hospitals that only want A01 auto-create leave
this column NULL; an A04 arrival logs WARN + falls through to
`NO_MATCH`.

The `admitting_provider_id`, `department_id`, and `default_assignment_id`
are stored as raw UUIDs (no DB FK) so an operator can rebuild
`hospital.staff`, `hospital.departments`, or
`security.user_role_hospital_assignment` without dropping the
config. The application-layer lookup dereferences them on each
auto-create and rejects gracefully (`NO_MATCH` + WARN line) when
a referent is missing or belongs to a different hospital — there's
no half-populated row failure mode.

---

## Status — 2026-05-17

| Phase | Status | Branch |
| --- | --- | --- |
| Foundation pass — reconcile-only | ✅ Shipped | `feat/v1.1-adt-admission-encounter-sync` (V99) |
| Follow-on — A01 Admission auto-create | ✅ Shipped | `feat/v1.1-adt-auto-create` (V103) |
| Follow-on (this revision) — A04 Encounter-only auto-create | ✅ Shipped | `feat/v1.1-adt-auto-create-encounter` (V104) |
| Follow-on — discharge / transfer triggers (A02, A03) | ⏳ Next | Builds on A01 + A04 |

The auto-create path ships behind the three-layer flag stack
described under "Activation gate". With auto-create off (the
production default) the runbook below behaves exactly as the
foundation pass did.

## Foundation-pass scope

The foundation pass reconciles inbound ADT messages to existing
Admission / Encounter rows by the HL7 visit-number key. The A01
follow-on extended that with Admission auto-create. The A04
follow-on (this revision) extends it further with **Encounter
auto-create** — the design problem flagged in the foundation-pass
notes (Encounter's `staff` + `assignment` `hospital`-match
invariant) is resolved by:

- Carrying the live `receivingHospital` reference on the projection
  service's internal context, and stamping it directly on the new
  Encounter (never via `staff.getHospital()` indirection).
- Validating both `staff.getHospital().getId()` AND
  `assignment.getHospital().getId()` against the receiving hospital
  at the service layer before touching `encounterRepository.save`.
- Treating the `Encounter#validate` `@PrePersist` invariants as
  defence in depth — failure modes surface as a clean WARN +
  `NO_MATCH` instead of an `IllegalStateException` in the MLLP
  worker stack trace.

The foundation-pass constraints listed below still apply:

1. `Encounter` requires `staff` and `assignment` whose `hospital`
   matches the encounter hospital (enforced in `Encounter#validate`).
   ADT does not carry an HMS staff id — PV1-7 ("Attending Doctor") is an
   opaque sender-side identifier.
2. `Admission` requires `admittingProvider`, `admissionType`,
   `acuityLevel`, and `chiefComplaint`. ADT carries an admission type
   hint (PV1-2 patient class) and may carry PV1-7 (admitting doctor) but
   nothing that resolves cleanly to a Staff record or an HMS acuity
   level.

Until per-hospital "intake provider" config lands (follow-on PR in v1.1),
auto-provisioning would either invent a SYSTEM-actor placeholder staff
(complicates audit + reporting) or fail entity validation at write time
(silent data loss).

**What this release ships:**

- `V99` migration: `external_visit_number`, `external_sending_application`,
  `external_sending_facility`, `external_message_control_id` columns
  added to `admissions` and `clinical.encounters`. Per-table partial
  composite unique index on `(sending_app, sending_facility, visit_number, hospital_id)`.
- JPA mappings and repository finders on both entities.
- `MllpInboundAdtVisitProjectionService` — locates existing rows by
  the HL7 reconciliation key, stamps the latest message control id,
  emits a structured log. Runs in `REQUIRES_NEW` so any projection
  failure can NEVER roll back the demographic write.
- Feature flag (`app.hl7.adt.visit-sync.enabled`, default `false`)
  and per-soak observability switch (`log-unmatched`).
- Tests covering: flag off → no-op, blank visit number → no-op,
  Admission match, Encounter match, no match, projection bean failure
  does not affect demographic ACK.

**What the row-24 follow-on (this revision) ships:**

- `V103` migration: `platform.adt_intake_provider_configs` (one row
  per hospital) with `admitting_provider_id`, `department_id`,
  `default_admission_type`, `default_acuity_level`,
  `default_encounter_type`, `default_chief_complaint`, and a
  per-hospital `enabled` opt-in column. Hard FK on `hospital_id`;
  provider/department UUIDs are app-layer-validated to tolerate
  staff/dept re-seeds.
- `AdtIntakeProviderConfig` entity + repository.
- `AdtVisitSyncProperties.AutoCreate.enabled` sub-flag
  (`app.hl7.adt.visit-sync.auto-create.enabled`, default `false`).
- Auto-create branch in `MllpInboundAdtVisitProjectionService`:
  on an unmatched A01 with all three gate layers on, builds an
  `Admission` from the per-hospital config defaults, stamps the
  V99 reconciliation key, emits `AuditEventType.ADMISSION_AUTOCREATED`,
  returns `VisitProjectionResult.ADMISSION_AUTOCREATED`.
- Cross-tenant gate via `PatientHospitalRegistration` — patient
  must be actively registered at the receiving hospital.
- 5 new unit tests covering: sub-flag-off no-op, A04 no-op,
  missing intake-config no-op, cross-tenant rejection, and the
  full happy path (verifies the Admission fields + audit emission).

**What the A04 follow-on (this revision) ships:**

- `V104` migration: `platform.adt_intake_provider_configs.default_assignment_id`
  (nullable UUID). Strictly additive `ADD COLUMN IF NOT EXISTS`;
  hospitals that only opted into A01 keep the column NULL.
- `AdtIntakeProviderConfig.defaultAssignmentId` JPA field.
- `VisitProjectionResult.ENCOUNTER_AUTOCREATED` enum value.
- `AuditEventType.ENCOUNTER_AUTOCREATED` audit event type.
- `tryAutoCreateEncounter` branch in
  `MllpInboundAdtVisitProjectionService`. Fires on A04 trigger
  when the same three-layer gate stack is on AND
  `default_assignment_id` is populated AND the resolved staff /
  department / assignment all belong to the receiving hospital.
- `resolveAssignment` defensive helper that mirrors
  `resolveProvider` / `resolveDepartment` (PR #358 review pattern).
- 3 new unit tests: A04 happy path with audit verification,
  A04 skipped when `default_assignment_id` is null, A04 rejected
  when assignment is cross-tenant.

**What still remains for follow-on PRs:**

- Discharge / transfer trigger events (A02, A03). Builds on the
  A01 + A04 paths — A03 updates an existing Admission with the
  discharge timestamp/disposition; A02 updates the department/bed
  reference.
- An admin UI to populate the intake-config table per hospital
  (currently a DB-only surface).
- An admin UI to manually link an existing in-app
  Admission/Encounter to an inbound visit number when the
  auto-resolution cannot.

---

## Identity reconciliation rules (already in production)

These are unchanged by the row 24 work — restated here so a single
document covers the full ADT decision tree.

| Inbound element  | Source field | HMS rule                                                                                                                          |
| ---------------- | ------------ | --------------------------------------------------------------------------------------------------------------------------------- |
| MRN              | PID-3        | Looked up via `EmpiService.findIdentityByAlias(MRN, …)`. **Unknown MRNs are rejected, never auto-created.**                       |
| Patient row      | EMPI alias   | `PatientRepository.findByIdUnscoped` (bypasses tenant scope — MLLP worker has no `HospitalContext`).                              |
| Cross-tenant     | sender ↔ hospital | The receiving hospital is the one allowlisted for the sender (MSH-3, MSH-4). The patient must already be `PatientHospitalRegistration`-bound to that hospital, otherwise the message is rejected with `REJECTED_CROSS_TENANT` → AR. |

---

## Visit reconciliation rules (new in this release)

| Inbound element  | Source field | HMS rule                                                                                                                                  |
| ---------------- | ------------ | ----------------------------------------------------------------------------------------------------------------------------------------- |
| Visit number     | PV1-19       | First component only. Used as the lookup key alongside MSH-3 + MSH-4 + hospital id. **Blank visit number → projection is a no-op.**       |
| Sender scope     | MSH-3, MSH-4 | Two different sending systems may legitimately reuse the same visit number — the reconciliation key includes the sender to keep them separate. |
| Reconcile order  | —            | `Admission` is checked first; on miss, fall through to `Encounter`. A visit number can only belong to one of the two at a given hospital. |
| Idempotency      | MSH-10       | The latest message control id is stamped on the matched row. Replays of the same message stamp the same value; no other state changes.    |
| No match         | —            | When `log-unmatched=true`, a structured `WARN` is emitted with sender + visit + hospital + patient + trigger event. **No row is written** in this release. |

---

## Update-precedence rules (when fields disagree)

The general rule is **"HL7 wins for demographics; HMS wins for clinical
state."** Concrete table:

| Field family                                  | Authoritative source on conflict | Why                                                                                                                                     |
| --------------------------------------------- | -------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| Name, DOB, sex, address                       | HL7 ADT (inbound)                | The sending registration desk is closer to the source of truth than HMS for demographics; this is the pre-row-24 demographic-upsert behaviour. |
| Blank inbound demographic fields              | HMS (existing)                   | Blank does not mean "wipe me" — many sender systems omit fields they don't own. This is the `setIfChanged` guard in `applyDemographics`. |
| Admission status, acuity, attending physician | HMS (existing)                   | These are clinical decisions made inside HMS; ADT messages don't carry the consensual context to overwrite them.                        |
| Discharge timestamp (PV1-45 vs HMS)           | HMS (existing)                   | **Foundation-pass policy: never auto-discharge from ADT.** A discharge is a clinical event, not a registration-system event. A follow-on PR will introduce an explicit `app.hl7.adt.allow-discharge-from-pv1-45` flag (default `false`). |
| Visit number (PV1-19) once stamped            | First write wins                 | Once the partial unique index has a row, subsequent A04 with the same key reconciles; another sender claiming the same number creates a distinct row scoped by its own (MSH-3, MSH-4). |
| Message control id (MSH-10) trail             | Last write wins                  | `external_message_control_id` always reflects the most recent message that touched the row, for traceability.                           |

---

## Trigger-event handling

| ADT trigger | Semantic                       | Demographics upsert | Visit-sync projection                                                                                          |
| ----------- | ------------------------------ | ------------------- | --------------------------------------------------------------------------------------------------------------- |
| A01         | Admit / visit notification     | Yes                 | Try Admission → Encounter; reconcile if found, log `NO_MATCH` otherwise. **No auto-create in this release.**    |
| A04         | Register a patient             | Yes                 | Try Admission → Encounter; reconcile if found, log `NO_MATCH` otherwise.                                       |
| A08         | Update patient information     | Yes                 | Same lookup chain. A08 is the dominant "touch-up" message and is the source of most reconciliation traffic.    |
| Any other   | (not in `ACCEPTED_ADT_EVENTS`) | n/a                 | Dispatcher already returns `AR` upstream. Projection never runs.                                                |

---

## Conflict scenarios (operator playbook)

### 1. `NO_MATCH` flood on a newly-onboarded sender

**Symptom:** thousands of `ADT visit-sync NO_MATCH` warnings shortly
after enabling `app.hl7.adt.visit-sync.enabled=true` for a new
analyzer / registration system.

**Diagnosis:** the sender is correctly authenticated and the patient
demographics are landing (no `REJECTED_*` outcomes in the
`integration_messages` table) but the existing HMS Admission / Encounter
rows were created via the in-app workflow without an `external_visit_number`,
so the projection has nothing to match against.

**Resolution:**

1. Pause the projection's `log-unmatched=false` for the duration of the
   onboarding to keep the noise out of dashboards.
2. Manually backfill `external_visit_number`, `external_sending_application`,
   `external_sending_facility` on the in-app Admissions / Encounters that
   correspond to the visits the sender is now updating. The mapping is
   best done by the registration team — they have the local visit
   numbers in hand.
3. After backfill, re-enable `log-unmatched=true` and verify the
   `NO_MATCH` rate drops to near-zero (only genuinely-new visits should
   trigger it).

### 2. Two senders, same visit number

**Symptom:** sender `LIS_A` and sender `REG_B` both push visits with
`PV1-19 = 12345`. Sender `LIS_A` lands first and binds it to Admission
`X`. Then `REG_B` lands with `12345` for an unrelated patient and
nothing collapses — both records exist independently.

**Behaviour:** correct. The partial unique index is composite over
`(sending_app, sending_facility, visit_number, hospital_id)`. Two
different senders are two different reconciliation namespaces, by
design.

### 3. Same sender, same visit number, different patient

**Symptom:** sender `LIS_A` pushes `PV1-19 = 12345` for patient `P1`,
then later pushes another A08 with the same visit number but
demographically different patient identifiers (different MRN, different
PID-5 name).

**Behaviour:** the partial unique index will block the second insert at
the database layer. The demographic step succeeds (the second patient's
own row is updated), but the projection step finds the existing row
already bound to a different patient, logs a `WARN` (current foundation:
the row is still touched — projection does not validate
patient-id consistency yet), and an operator should investigate.

**Resolution:** the registration team must clarify which visit is the
authoritative one with the sender and, if necessary, manually clear the
`external_visit_number` from the wrongly-bound row.

**Follow-on PR:** add a patient-id consistency check inside the
projection — if the matched Admission/Encounter belongs to a different
patient than the one the demographic step resolved, fail the projection
with a structured `ERROR` log and leave the row untouched. Tracked
inline with the auto-create work.

### 4. Discharge claim arrives via ADT

**Symptom:** an A08 from a registration system has `PV1-45 = 20260518090000`
on a visit currently `ACTIVE` in HMS.

**Behaviour:** the foundation pass **ignores PV1-45**. The Admission
remains `ACTIVE`. A discharge in HMS still has to be performed by a
clinician through the in-app workflow.

**Why:** discharging an Admission cascades into bed release, billing
finalisation, follow-up scheduling, and discharge-summary obligations.
None of those should be triggered by a registration-system message that
the clinical team has not seen.

**Follow-on PR:** the `app.hl7.adt.allow-discharge-from-pv1-45` flag.
Default `false`. When `true`, an A08 with PV1-45 set on an `ACTIVE`
Admission flips status to `AWAITING_DISCHARGE` (not `DISCHARGED`) and
emits an audit event so the clinical team can confirm.

### 5. ADT for a patient unknown to EMPI

**Symptom:** `MLLP ADT mrn=??? unknown to EMPI` log line and
`REJECTED_NOT_FOUND` in `integration_messages`.

**Behaviour:** correct and pre-existing. ADT never auto-creates patients
— EMPI registration must happen first through the in-app workflow.

**Resolution:** the registration team must add the patient in HMS (or
import them via EMPI bulk-load), then the sender can retry. The
analyzer / sender is expected to handle the AE on its side.

---

## Observability

| Signal                              | Where                                                       |
| ----------------------------------- | ----------------------------------------------------------- |
| Demographic ACCEPTED / REJECTED     | `integration_messages` table (`IntegrationMessageRecorder`) |
| Projection result (per message)     | `MllpInboundAdtVisitProjectionServiceImpl` log lines        |
| Reconciliation count                | Count of `ADMISSION_RECONCILED` + `ENCOUNTER_RECONCILED` log entries (Grafana log volume panel) |
| `NO_MATCH` warning rate             | `app.hl7.adt.visit-sync.log-unmatched=true` → WARN per occurrence; alert if > 100 / 5min |

---

## Future direction (post v1.1)

1. **Intake-provider config + auto-create.** Per-hospital config row
   pointing to a single `Staff` that owns ADT-created encounters until a
   real clinician takes over. Unlocks A01 auto-create.
2. **Discharge automation.** `app.hl7.adt.allow-discharge-from-pv1-45=true`
   with the `AWAITING_DISCHARGE` (not `DISCHARGED`) state machine.
3. **A03 (discharge), A11 (cancel admission), A13 (cancel discharge).**
   Currently rejected at the dispatcher with `AR` (unsupported); these
   need explicit handlers once auto-create lands.
4. **Patient-id consistency check** in the projection (see scenario 3
   above).
5. **Audit event type `ADT_VISIT_SYNCED`.** The current implementation
   emits log lines only; a dedicated `AuditEventType` will land
   alongside the auto-create work so the audit trail can be
   queried structurally instead of by log search.

---

## Schema reference

```
admissions
  external_visit_number          VARCHAR(255) NULL    -- PV1-19 (first component)
  external_sending_application   VARCHAR(255) NULL    -- MSH-3
  external_sending_facility      VARCHAR(255) NULL    -- MSH-4
  external_message_control_id    VARCHAR(255) NULL    -- MSH-10 of LAST message that touched the row

CREATE UNIQUE INDEX uk_admission_external_visit
  ON admissions (external_sending_application,
                 external_sending_facility,
                 external_visit_number,
                 hospital_id)
WHERE external_visit_number IS NOT NULL;

clinical.encounters
  -- same four columns, same partial unique index (uk_encounter_external_visit).
```

All four columns are nullable. Manual / in-app-created rows leave them
NULL and are excluded from the unique index.
