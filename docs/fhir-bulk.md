# FHIR Bulk Data Access — operational notes

**Status:** foundation pass shipped on `feat/v1.1-fhir-bulk-and-everything` (roadmap row 21).
**Scope today:** kickoff + poll + cancel surfaces against an in-memory job map; **the actual async runner that emits NDJSON to S3 is deferred to the row-21 follow-on.**

---

## Feature flags

Two independent switches under `app.fhir.operations.*`, both default OFF:

```
app.fhir.operations.bulk-export.enabled=${FHIR_BULK_EXPORT_ENABLED:false}
app.fhir.operations.everything.enabled=${FHIR_EVERYTHING_ENABLED:false}
```

Promote bulk-export and `$everything` independently — the latter is synchronous and low-risk; the former is async and capacity-sensitive.

With `bulk-export.enabled=false`:

- `POST /api/fhir/$export` → `405 Method Not Allowed`
- `POST /api/fhir/Patient/$export` → `405 Method Not Allowed`
- `GET /api/fhir-bulk-status/{jobId}` → `405 Method Not Allowed`
- `DELETE /api/fhir-bulk-status/{jobId}` → `405 Method Not Allowed`
- `GET /api/fhir/metadata` does NOT advertise the `$export` operation entry

With `everything.enabled=false`:

- `GET /api/fhir/Patient/{id}/$everything` → `405 Method Not Allowed`
- `GET /api/fhir/metadata` does NOT advertise the `$everything` operation entry

---

## Supported semantics

### `$export` (row 21)

| Endpoint | Path | Status |
| --- | --- | --- |
| Kickoff (system level) | `POST /api/fhir/$export` | Foundation: 202 + `Content-Location` |
| Kickoff (Patient-type level) | `POST /api/fhir/Patient/$export` | Foundation: 202 + `Content-Location` |
| Kickoff (Group instance level) | `POST /api/fhir/Group/{id}/$export` | **Deferred** — needs `GroupFhirResourceProvider` |
| Poll status | `GET /api/fhir-bulk-status/{jobId}` | Foundation: always `202 Accepted` + `Retry-After: 120` |
| Cancel | `DELETE /api/fhir-bulk-status/{jobId}` | Foundation: `202 Accepted` (drops job from map) |

The poll path lives at `/api/fhir-bulk-status/{jobId}` (NOT `/api/fhir/$export-poll-status/{jobId}`) because the HAPI FHIR servlet captures the entire `/api/fhir/*` space; mounting a plain Spring controller under `/api/fhir/*` requires HAPI's `manualResponse=true` machinery on a `@Operation` method with a synthetic name. That mounting lands with the row-21 follow-on along with the async runner — partner integrations should treat the URL as opaque (the bulk-data spec dictates the server chooses the Content-Location URL).

**Honored parameters:**

| Parameter | Type | Notes |
| --- | --- | --- |
| `_since` | ISO-8601 instant | Resources changed at or after this instant |
| `_type` | comma-separated string | Limit which resource types are exported |
| `_outputFormat` | string | Accepted for spec compliance; currently ignored — runner writes `application/fhir+ndjson` |

The kickoff response body is a minimal `Parameters` resource carrying the assigned `jobId` and `pollUrl`. The bulk-data spec does not mandate a kickoff body; we emit one so foundation-pass clients can read the id without parsing the header.

**Tenant scope:** the active hospital is read from `HospitalContextHolder.getActiveHospitalId()` and pinned on the job at creation time. Status / cancel calls from a different tenant collapse to `404 Not Found` — cross-tenant rejection is invisible (no information leak).

**Audit emission:** `AuditEventType.DATA_EXPORT` on kickoff + cancel, with `entityType="FHIR_BULK_EXPORT_JOB"`.

### `$everything` (row 22)

| Endpoint | Path | Status |
| --- | --- | --- |
| Patient compartment export | `GET /api/fhir/Patient/{id}/$everything` | Foundation: synchronous `200 OK` + `Bundle` |

The Bundle is of type `searchset` and contains:

- 1 `Patient`
- Up to 200 most-recent `Encounter`s (hospital-scoped)
- Up to 200 most-recent vital-sign rows (each expanded 1:N into up to 7 `Observation` resources by `ObservationFhirMapper`)
- Up to 200 most-recent lab results (`Observation`, hospital-scoped via `labOrder.hospital`)
- All `Condition`s (problem list — typically small enough to skip a page cap)
- Up to 200 most-recent `MedicationRequest`s (hospital-scoped)

**Tenant scope:** active hospital read from `HospitalContextHolder`. Missing scope → `403 Forbidden`. Patient resolution is via `PatientRepository.findById(...)` which is tenant-aware; cross-tenant access surfaces as `404`.

**Audit emission:** `AuditEventType.PATIENT_EXPORT` with `entityType="PATIENT"` and a description recording the entry count.

---

## What's deferred (row-21 follow-on)

The row stays at `started` until these land:

- **Persistent job store** — `fhir_bulk_export_jobs` table (V103 in the next free slot), JPA entity + repository replacing the in-memory `ConcurrentHashMap`.
- **Async runner** — `@Scheduled` (or Kafka consumer once row 36 lands) that picks up `QUEUED` jobs, fans out per-resource-type collection queries through the existing FHIR read mappers, streams NDJSON to an S3-compatible bucket.
- **Output manifest** — `200 OK` poll response with `transactionTime`, `request`, `requiresAccessToken`, `output: [{type, url}]`, `error: []`.
- **Group-level $export** — needs `GroupFhirResourceProvider` (HMS does not currently model Group as a first-class FHIR resource).
- **Canonical poll-URL mounting** — `/api/fhir/$export-poll-status/{jobId}` via a HAPI plain-provider `@Operation` with `manualResponse=true`, so consumers can rely on the spec's "treat Content-Location as opaque" guarantee while still keeping every bulk-data path under `/api/fhir/*`.
- **`_outputFormat` honoured** — `application/fhir+ndjson` (default), `application/ndjson`, `ndjson`.
- **Spec-compliant 501 on flag-off** — the foundation pass returns 405 to match the rest of HMS's flag-off contract; the spec preference is 501 and ships with the async runner.

## What's deferred (row-22 follow-on)

- **Authenticated end-to-end IT** with a seeded patient + encounter / observation / condition / medication-request so the Bundle composition is asserted on the wire (today's IT is a metadata-advertisement check; the 401-or-handler-status pattern blocks a deeper assertion).
- **`_since` / `_type` honored on $everything** for incremental sync.
- **Page cursor / `_count`** for compartments that exceed the 200-row caps (rare on a single patient but real for long-running ones).
- **`start` / `end` parameters** for date-range scoping per the FHIR R4 operation definition.
- **Conformance soak against SMART App Launcher** before the row flips to `completed`.

---

## Reference

- `hospital-core/src/main/java/com/example/hms/fhir/FhirOperationsProperties.java`
- `hospital-core/src/main/java/com/example/hms/fhir/bulk/FhirBulkExportService.java`
- `hospital-core/src/main/java/com/example/hms/fhir/bulk/BulkExportJobState.java`
- `hospital-core/src/main/java/com/example/hms/fhir/bulk/FhirBulkExportOperationProvider.java`
- `hospital-core/src/main/java/com/example/hms/fhir/bulk/FhirBulkExportStatusController.java`
- `hospital-core/src/main/java/com/example/hms/fhir/everything/PatientEverythingService.java`
- `hospital-core/src/main/java/com/example/hms/fhir/provider/PatientFhirResourceProvider.java` (`@Operation $everything`)
- `hospital-core/src/main/java/com/example/hms/fhir/smart/HmsCapabilityStatementProvider.java` (`applyOperationVisibility`)
- `hospital-core/src/main/java/com/example/hms/fhir/FhirConfig.java` (plain-provider registration)
- Tests:
  - `hospital-core/src/test/java/com/example/hms/fhir/FhirBulkExportIT.java`
  - `hospital-core/src/test/java/com/example/hms/fhir/FhirBulkExportEnabledIT.java`
  - `hospital-core/src/test/java/com/example/hms/fhir/PatientEverythingIT.java`
  - `hospital-core/src/test/java/com/example/hms/fhir/PatientEverythingEnabledIT.java`
