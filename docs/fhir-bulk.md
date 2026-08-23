# FHIR Bulk Data Access — operational notes

**Status:** complete (roadmap row 21; P3 item 24 shipped the async runner, V129).
**Scope today:** kickoff + poll + cancel + **NDJSON output + authenticated download**, backed by the persistent `platform.fhir_bulk_export_jobs` / `fhir_bulk_export_files` tables and a `@Scheduled` runner. The foundation pass's in-memory job map is gone.

---

## Enable runbook

The flags are **environment-bound `@ConfigurationProperties`, not DB feature
flags** — changing them means changing the environment and restarting the
service. There is no admin-UI toggle.

1. Set the environment variables on the deployment (Railway service / compose
   file):

   ```properties
   FHIR_BULK_EXPORT_ENABLED=true          # gates $export + poll + download
   FHIR_EVERYTHING_ENABLED=true           # gates Patient/{id}/$everything (independent)
   FHIR_BULK_EXPORT_DIR=/data/fhir-bulk   # optional; default "fhir-bulk-exports"
   FHIR_BULK_EXPORT_RUNNER_INTERVAL_MS=60000  # optional sweep interval
   ```

2. **Storage directory rules:** the directory must be writable by the service
   user and must NOT live under the public upload tree — `/uploads/**` is
   served `permitAll` and export output is a mass PHI extract. The default is
   a sibling directory (the V126 patient-photo precedent). Files stream only
   through the authenticated download endpoint, which is what makes the
   manifest's `requiresAccessToken: true` literally true.

3. Restart the service. `GET /api/fhir/metadata` now advertises the `$export`
   operation; the runner sweep starts picking up `QUEUED` jobs within one
   interval.

4. **Who can run it:** kickoff, poll and download all require
   `SUPER_ADMIN` or `HOSPITAL_ADMIN` (a bulk export is a mass PHI extract;
   `/fhir/**` itself carries no role gate, so the gate lives in the service
   and on the status controller). Every call must carry an active hospital
   scope (`X-Hospital-Id` for super-admins) — kickoff without one is a 400,
   and jobs are pinned to that hospital for their lifetime.

5. Promote `$everything` and `$export` independently — the former is
   synchronous and low-risk; the latter is async and capacity-sensitive.

With `bulk-export.enabled=false` (the default), every `$export` surface —
kickoff, poll, cancel, download — returns **`501 Not Implemented`** + a FHIR
`OperationOutcome`, and the `CapabilityStatement` omits the operation entry.
(The foundation pass returned 405 and documented the flip to 501 as shipping
with the async runner; that is this pass.) `$everything` flag-off remains
`405` — the synchronous-path HMS flag-off contract.

---

## Supported semantics

### `$export` (row 21 — complete)

| Endpoint | Path | Behaviour |
| --- | --- | --- |
| Kickoff (system level) | `POST /api/fhir/$export` | 202 + `Content-Location` |
| Kickoff (Patient-type level) | `POST /api/fhir/Patient/$export` | 202 + `Content-Location` |
| Kickoff (Group instance level) | `POST /api/fhir/Group/{id}/$export` | **Deferred** — needs `GroupFhirResourceProvider` |
| Poll | `GET /api/fhir-bulk-status/{jobId}` | 202 + `X-Progress` while QUEUED/IN_PROGRESS; **200 + manifest** when COMPLETED; 500 + `OperationOutcome` when FAILED; 404 when cancelled/unknown/cross-tenant |
| Cancel | `DELETE /api/fhir-bulk-status/{jobId}` | 202 on an open job (row kept, flipped CANCELLED); 404 otherwise. A job cancelled mid-run aborts at its next patient page and discards partial output |
| Download | `GET /api/fhir-bulk-status/{jobId}/file/{fileName}` | Streams one NDJSON file of a COMPLETED job (`application/fhir+ndjson`) |

The poll path lives at `/api/fhir-bulk-status/{jobId}` (NOT `/api/fhir/$export-poll-status/{jobId}`) because the HAPI FHIR servlet captures the entire `/api/fhir/*` space. Partner integrations should treat the URL as opaque — the bulk-data spec dictates the server chooses the `Content-Location` URL.

**Honored parameters:**

| Parameter | Type | Notes |
| --- | --- | --- |
| `_since` | ISO-8601 instant | Resources changed at or after this instant. Rows with no modification timestamp pass the filter (legacy-data stance shared with `$everything`). Malformed → 400 |
| `_type` | comma-separated string | Supported: `Patient`, `Encounter`, `Observation`, `Condition`, `MedicationRequest`. An unsupported type is **rejected at kickoff (400)** — silently dropping one would let a client believe its extract was complete |
| `_outputFormat` | string | `application/fhir+ndjson`, `application/ndjson`, `ndjson` accepted (output is always `application/fhir+ndjson`); anything else → 400 |

**What a job exports:** every patient actively registered at the job's
hospital, fanned out through the same five hospital-scoped queries and FHIR
mappers `$everything` uses, streamed page-by-page into one NDJSON file per
resource type (`{storage-dir}/{jobId}/Patient.ndjson`, …). Types that produce
zero resources leave no file and no manifest line. SYSTEM and PATIENT scope
currently produce identical output — HMS has no non-patient-compartment
resources to add at system level yet.

**Completion manifest** (spec shape):

```json
{
  "transactionTime": "2026-08-22T10:01:00Z",
  "request": "/api/fhir/$export?_type=Patient,Observation",
  "requiresAccessToken": true,
  "output": [
    {"type": "Patient", "url": "https://…/api/fhir-bulk-status/{jobId}/file/Patient.ndjson", "count": 1204}
  ],
  "error": []
}
```

**Job lifecycle:** `QUEUED → IN_PROGRESS → COMPLETED | FAILED | CANCELLED`.
The runner claims QUEUED jobs with an atomic conditional UPDATE (safe without
ShedLock across instances), updates `processed_patients` as it pages (visible
in `X-Progress`), and never deletes a terminal row. Failures carry
`error_message`; there is no automatic retry — re-kick `$export`.

**Tenant scope:** pinned at creation from `HospitalContextHolder`; missing
scope → 400. Poll/cancel/download from another tenant (or with no
`X-Hospital-Id`) collapse to 404 — cross-tenant rejection is invisible.

**Audit emission:** `AuditEventType.DATA_EXPORT` on kickoff, cancel,
completion and failure, with `entityType="FHIR_BULK_EXPORT_JOB"`.

### `$everything` (row 22)

Unchanged — see the `PatientEverythingService` javadoc; synchronous
`200 OK + Bundle`, `_since` / `_type` / `_count` / `_page` honored,
flag-off 405.

---

## What's still deferred

- **Group-level $export** — needs `GroupFhirResourceProvider` (HMS does not
  model Group as a first-class FHIR resource).
- **Canonical poll-URL mounting** under `/api/fhir/*` via a HAPI
  plain-provider `@Operation` with `manualResponse=true`.
- **S3-compatible output target** — output is local-disk only; there is no S3
  client on the classpath. Point `FHIR_BULK_EXPORT_DIR` at a mounted volume.
- **Retry / DLQ semantics** — a FAILED job is terminal; the operator re-kicks.
- **`deleteRequested` output-file expiry** — files live until the operator
  clears the storage directory; add a retention sweep when a deployment
  actually accumulates exports.

---

## Reference

- `hospital-core/src/main/java/com/example/hms/fhir/FhirOperationsProperties.java`
- `hospital-core/src/main/java/com/example/hms/fhir/bulk/FhirBulkExportService.java`
- `hospital-core/src/main/java/com/example/hms/fhir/bulk/FhirBulkExportRunner.java`
- `hospital-core/src/main/java/com/example/hms/fhir/bulk/FhirBulkExportOperationProvider.java`
- `hospital-core/src/main/java/com/example/hms/fhir/bulk/FhirBulkExportStatusController.java`
- `hospital-core/src/main/java/com/example/hms/model/platform/FhirBulkExportJob.java`
- `hospital-core/src/main/resources/db/migration/V129__fhir_bulk_export_jobs.sql`
- `hospital-core/src/main/java/com/example/hms/fhir/everything/PatientEverythingService.java`
- Tests:
  - `hospital-core/src/test/java/com/example/hms/fhir/bulk/FhirBulkExportServiceTest.java`
  - `hospital-core/src/test/java/com/example/hms/fhir/bulk/FhirBulkExportRunnerTest.java`
  - `hospital-core/src/test/java/com/example/hms/fhir/bulk/FhirBulkExportStatusControllerTest.java`
  - `hospital-core/src/test/java/com/example/hms/fhir/FhirBulkExportIT.java`
  - `hospital-core/src/test/java/com/example/hms/fhir/FhirBulkExportEnabledIT.java`
