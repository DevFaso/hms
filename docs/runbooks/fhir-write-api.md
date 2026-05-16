# FHIR R4 Write API — operational runbook

**Status:** foundation pass shipped on `feat/v1.1-fhir-write-api` (roadmap row 20).
**Scope today:** Patient PUT + conditional POST only. Encounter and Observation write paths are deferred to the row-20 follow-on PR.

---

## Feature flag

The write API is gated by a single property, default OFF:

```
app.fhir.write.enabled=${FHIR_WRITE_ENABLED:false}
```

With the flag off:

- `PUT /api/fhir/Patient/{id}` → `405 Method Not Allowed`
- `POST /api/fhir/Patient` → `405 Method Not Allowed`
- `GET /api/fhir/metadata` does NOT advertise `Patient.conditionalCreate=true`

Enable on a per-environment basis via `FHIR_WRITE_ENABLED=true`. Recommend turning on in `dev` first, soaking against the SMART app launcher, then `uat`.

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

## What's deferred (roadmap row 20 follow-on)

The row stays at `started` until these land:

- `PUT /api/fhir/Encounter/{id}` — straightforward; mirrors the Patient PUT pattern.
- `POST /api/fhir/Encounter` — needs a Patient FK resolution path mirroring the conditional-create rule (must already exist).
- `PUT /api/fhir/Observation/{id}` — complicated by the 1:N PatientVitalSign → Observation expansion in `ObservationFhirMapper`; the write path may need to honor only `labresult-{uuid}` namespace and reject `vital-{uuid}-{component}` updates.
- `POST /api/fhir/Observation` — requires Encounter context binding and resolution of LOINC / units against the existing TerminologyCodes guards.

Once all four interactions are conformant against the SMART app launcher (`https://launcher.smarthealthit.org`), row 20 moves to `completed`.

---

## Reference

- `hospital-core/src/main/java/com/example/hms/fhir/FhirWriteProperties.java`
- `hospital-core/src/main/java/com/example/hms/fhir/write/PatientFhirWriteService.java`
- `hospital-core/src/main/java/com/example/hms/fhir/provider/PatientFhirResourceProvider.java`
- `hospital-core/src/main/java/com/example/hms/fhir/mapper/PatientFhirMapper.java`
- `hospital-core/src/main/java/com/example/hms/fhir/smart/HmsCapabilityStatementProvider.java`
- `hospital-core/src/main/resources/db/migration/V101__fhir_write_patient_idempotency.sql`
- `hospital-core/src/test/java/com/example/hms/fhir/PatientFhirWriteIT.java`
- `hospital-core/src/test/java/com/example/hms/fhir/PatientFhirWriteEnabledIT.java`
