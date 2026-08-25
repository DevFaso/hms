# FHIR R4 Write API — operational runbook

**Status:** Patient PUT + conditional POST shipped on `feat/v1.1-fhir-write-api`. Encounter PUT + Observation PUT (labresult namespace only) shipped on `feat/v1.1-fhir-write-encounter-observation` (roadmap row 20 follow-on).
**Scope today:** narrow PUT on `Patient`, `Encounter`, and `Observation/labresult-{uuid}`; conditional POST on `Patient`. POST on `Encounter` / `Observation` is intentionally not exposed.

---

## Feature flag

The write API is gated by a single property, default OFF:

```
app.fhir.write.enabled=${FHIR_WRITE_ENABLED:false}
```

With the flag off:

- `PUT /api/fhir/Patient/{id}` → `405 Method Not Allowed`
- `POST /api/fhir/Patient` → `405 Method Not Allowed`
- `PUT /api/fhir/Encounter/{id}` → `405 Method Not Allowed`
- `PUT /api/fhir/Observation/{id}` → `405 Method Not Allowed` (both `labresult-*` and `vital-*` namespaces)
- `GET /api/fhir/metadata` does NOT advertise `update` or `conditionalCreate` interactions on any of the three resources

Provider methods short-circuit on the flag check **before** any request-shape validation runs, so flag-off + malformed body still surfaces as 405, not 422. (Caught on the Patient provider during PR #343 Copilot review; the same fix is applied uniformly on the Encounter + Observation providers in this follow-on.)

Enable on a per-environment basis via `FHIR_WRITE_ENABLED=true`. Recommend turning on in `dev` first, soaking against the SMART app launcher, then `prod`.

---

## Supported semantics

### `PUT /api/fhir/Patient/{id}`

Updates the **FHIR-mutable subset** of an existing patient. Honored fields:

| FHIR field | HMS column |
| --- | --- |
| `address[0].line[0]` | `address_line1` |
| `address[0].line[1]` | `address_line2` |
| `address[0].city` | `city` |
| `address[0].state` | `state` |
| `address[0].postalCode` | `zip_code` |
| `address[0].country` | `country` |
| `telecom` (system=phone, use=mobile) | `phone_number_primary` |
| `telecom` (system=phone, use=home) | `phone_number_secondary` |
| `telecom` (system=email) | `email` |
| `active` | `is_active` |

Identity columns — `firstName`, `lastName`, `middleName`, `dateOfBirth`, `gender`, plus all identifier rows on `patient_hospital_registrations` — are **never** overwritten via FHIR PUT. Those changes must go through the registration admin path, which carries the deeper audit trail.

Audit emission: `AuditEventType.PATIENT_UPDATE` on success.

### `POST /api/fhir/Patient` with `If-None-Exist`

**Never auto-provisions a new Patient row** (per the `empi-identity` skill: "Never auto-create a Patient from an unknown EMPI alias"). The conditional-create semantics are:

| Match count | HTTP response |
| --- | --- |
| 0 matches | `404 Not Found` + `OperationOutcome` |
| 1 match | `200 OK` with the existing `Patient` resource |
| >1 matches | `412 Precondition Failed` + `OperationOutcome` (unreachable in practice — see V101 index) |

The `If-None-Exist` header **must** contain exactly one `identifier=<system>|<value>` clause, where `<system>` matches `urn:hms:hospital:<hospitalId>:mrn` and `<value>` is the MRN. Any other search parameter returns `422 Unprocessable Entity`.

Audit emission: `AuditEventType.PATIENT_ACCESS` on the matched read (no `PATIENT_CREATE` is emitted because nothing is created).

A POST without an `If-None-Exist` header returns `422` with an OperationOutcome explaining the policy.

### `PUT /api/fhir/Encounter/{id}`

Updates a **very narrow subset** of an existing encounter at the active hospital scope. Honored fields:

| FHIR field | HMS column | Apply rule |
| --- | --- | --- |
| `period.end` | `checkout_timestamp` | Only when `checkout_timestamp` is currently `NULL` — the FHIR PUT can close an encounter from an external system but cannot rewrite an in-app checkout. |
| `reasonCode[0].text` | `chief_complaint` | Only when `chief_complaint` is currently `NULL` or blank — never overwrite the clinician's triage note. |

**Not honored:** `status` (the encounter state-machine fires timestamps + side-effects that PUT cannot orchestrate), `class`, `type`, `subject`, `period.start`, participants, diagnoses, hospitalization. Those flow through the clinical workflow path.

**Tenant scope:** the active hospital id is read from the request's `HospitalContext` (JWT or `X-Hospital-Id` header). The encounter is fetched via `EncounterRepository.findByIdAndHospital_Id`, so a caller in tenant A cannot mutate tenant B's encounter even if they hold the UUID. A missing active hospital returns `403`. A mismatched hospital on the loaded entity also returns `403` (defence-in-depth).

POST `/api/fhir/Encounter` is intentionally NOT exposed. Encounter provisioning has mandatory invariants (staff @ hospital, assignment @ hospital, appointment match) enforced by `Encounter.@PrePersist` that an inbound FHIR sender cannot reliably satisfy. New encounters go through the clinical workflow.

Audit emission: `AuditEventType.ENCOUNTER_UPDATE` (new enum value) on success, with `entityType="ENCOUNTER"` (canonical UPPER_SNAKE literal — same lesson as the Patient write `"PATIENT"` fix from PR #343 review).

### `PUT /api/fhir/Observation/{id}`

The Observation read surface emits two id namespaces:

- `labresult-{uuid}` — 1:1 against `lab.lab_results`. **This is the only writable namespace.**
- `vital-{uuid}-{component}` — 1:N expansion of `clinical.patient_vital_signs` into up to seven Observation resources. **Read-only by policy.**

`PUT /api/fhir/Observation/vital-*` returns `422 Unprocessable Entity` + `OperationOutcome` (BUSINESSRULE) because the source row has no single-row write target — updating "heart rate" individually would silently disagree with the other six co-recorded vitals on the same `PatientVitalSign` row. Vital-signs updates flow through the clinical workflow path.

Honored fields on `labresult-*`:

| FHIR field | HMS column | Apply rule |
| --- | --- | --- |
| `note[0].text` | `lab_results.notes` | Appended with `" \| "` separator if `notes` already contains text; copied verbatim if `notes` is null/blank. Duplicate inbound text (already a substring of the existing column) is a no-op. |

**Not honored:** `status` (release / sign / acknowledge transitions are actor-stamped state-machine events — FHIR PUT has no signer), `code`, `value`, `subject`, `effective`, `category`. Those flow through the clinical release UI or the HL7 MLLP ingest path.

**Tenant scope:** the active hospital id is read from `HospitalContext`. The lab result is fetched by id and its `labOrder.hospital.id` must match. Missing active hospital → `403`. Cross-tenant id → `403`. Unknown id-prefix → `404`. Malformed UUID after `labresult-` → `404`.

Audit emission: `AuditEventType.LAB_RESULT_UPDATED` (already wired by the MLLP ingest path) with `entityType="LAB_RESULT"` on success.

---

## Schema dependency

`V101__fhir_write_patient_idempotency.sql` adds a partial unique index:

```sql
CREATE UNIQUE INDEX IF NOT EXISTS uk_patient_hospital_registration_active_mrn
    ON clinical.patient_hospital_registrations (hospital_id, LOWER(mrn))
 WHERE is_active = true AND mrn IS NOT NULL AND mrn <> '';
```

If a duplicate already exists when the migration runs at startup, the app fails fast and the operator must resolve the duplicate via the `EmpiMergeService` admin flow before re-deploying.

---

## Smoke-test commands

With the flag on, against the local stack:

```powershell
# Expect 405 when flag is off; 422 when on (no If-None-Exist).
$body = '{"resourceType":"Patient"}'
Invoke-WebRequest -Method POST `
    -Uri 'http://localhost:8080/api/fhir/Patient' `
    -ContentType 'application/fhir+json' `
    -Body $body

# Expect 404 when MRN does not match any active registration.
$hospital = '<hospital-uuid>'
$mrn = 'MRN-DOES-NOT-EXIST'
Invoke-WebRequest -Method POST `
    -Uri 'http://localhost:8080/api/fhir/Patient' `
    -ContentType 'application/fhir+json' `
    -Headers @{ 'If-None-Exist' = "identifier=urn:hms:hospital:$hospital`:mrn|$mrn" } `
    -Body $body

# Expect 200 with the existing Patient resource on a single MRN match.
$mrn = 'MRN-FROM-REGISTRATION-TABLE'
Invoke-WebRequest -Method POST `
    -Uri 'http://localhost:8080/api/fhir/Patient' `
    -ContentType 'application/fhir+json' `
    -Headers @{ 'If-None-Exist' = "identifier=urn:hms:hospital:$hospital`:mrn|$mrn" } `
    -Body $body
```

---

## What's still deferred (roadmap row 20 — remaining follow-on)

The row stays at `started` until these land:

- `POST /api/fhir/Encounter` — needs a Patient FK resolution path mirroring the Patient conditional-create rule (must already exist). Deferred indefinitely; the in-app workflow remains the canonical creation path.
- `POST /api/fhir/Observation` — requires Encounter context binding and resolution of LOINC / units against the existing `TerminologyCodes` guards. Deferred indefinitely; analyzer-driven results land via MLLP, and ad-hoc clinical observations land via the clinical UI.
- Conformance soak against the SMART app launcher (`https://launcher.smarthealthit.org`), Cerner sandbox, and Epic sandbox for all three resources end-to-end.

Once the conformance soak is recorded and the operational verdict is "no POST is needed for the integration surface", row 20 moves to `completed`.

---

## Reference

- `hospital-core/src/main/java/com/example/hms/fhir/FhirWriteProperties.java`
- `hospital-core/src/main/java/com/example/hms/fhir/write/PatientFhirWriteService.java`
- `hospital-core/src/main/java/com/example/hms/fhir/write/EncounterFhirWriteService.java`
- `hospital-core/src/main/java/com/example/hms/fhir/write/ObservationFhirWriteService.java`
- `hospital-core/src/main/java/com/example/hms/fhir/provider/PatientFhirResourceProvider.java`
- `hospital-core/src/main/java/com/example/hms/fhir/provider/EncounterFhirResourceProvider.java`
- `hospital-core/src/main/java/com/example/hms/fhir/provider/ObservationFhirResourceProvider.java`
- `hospital-core/src/main/java/com/example/hms/fhir/mapper/PatientFhirMapper.java`
- `hospital-core/src/main/java/com/example/hms/fhir/mapper/EncounterFhirMapper.java`
- `hospital-core/src/main/java/com/example/hms/fhir/mapper/ObservationFhirMapper.java`
- `hospital-core/src/main/java/com/example/hms/fhir/smart/HmsCapabilityStatementProvider.java`
- `hospital-core/src/main/resources/db/migration/V101__fhir_write_patient_idempotency.sql`
- `hospital-core/src/test/java/com/example/hms/fhir/PatientFhirWriteIT.java`
- `hospital-core/src/test/java/com/example/hms/fhir/PatientFhirWriteEnabledIT.java`
- `hospital-core/src/test/java/com/example/hms/fhir/EncounterFhirWriteIT.java`
- `hospital-core/src/test/java/com/example/hms/fhir/EncounterFhirWriteEnabledIT.java`
- `hospital-core/src/test/java/com/example/hms/fhir/ObservationFhirWriteIT.java`
- `hospital-core/src/test/java/com/example/hms/fhir/ObservationFhirWriteEnabledIT.java`
