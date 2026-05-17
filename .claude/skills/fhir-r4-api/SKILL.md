---
name: fhir-r4-api
description: Use when adding or modifying FHIR R4 resource mappers, providers, CapabilityStatement, SMART-on-FHIR endpoints, or terminology coding (LOINC/ICD-10/ICD-11/ATC/RxNorm/CVX). Triggers on changes under hospital-core/src/main/java/com/example/hms/fhir/ or terminology/.
---

# FHIR R4 API

HMS exposes a FHIR R4 read API and a narrow write API on Patient
(row 20 foundation pass shipped on
`feat/v1.1-fhir-write-api`). The base URL is derived from the
incoming request (honoring `X-Forwarded-*` headers behind
Railway/nginx) unless `FHIR_SERVER_BASE_URL` env var is set to an
absolute HTTPS URL.

## Resource mapper conventions

Each FHIR resource gets a mapper under `fhir/mapper/` (e.g.
`ObservationFhirMapper`, `PatientFhirMapper`). The pattern:

- `toFhir(entity)` — domain → FHIR resource
- `fromFhir(resource)` — FHIR → domain (only on write paths)

Codings on the FHIR resource MUST go through `TerminologyCodes` so the
system URIs are canonical:

- LOINC: `http://loinc.org`
- ICD-10: `http://hl7.org/fhir/sid/icd-10`
- ICD-11: `http://id.who.int/icd/release/11/mms`
- ATC: `http://www.whocc.no/atc`
- RxNorm: `http://www.nlm.nih.gov/research/umls/rxnorm`
- UCUM: `http://unitsofmeasure.org`
- CVX (vaccines): `http://hl7.org/fhir/sid/cvx`

Project-local fallback URIs for unknown systems:

- `urn:hms:lab:test-code`
- `urn:hms:problem-code`
- `urn:hms:medication:code`

Never silently drop a code — emit it under the project-local URN if no
canonical system applies.

## Multi-coding ordering

When an entity has both an explicit LOINC and a domain test code,
**emit LOINC first** (most consumers iterate top-down and pick the
first recognised system). Pattern lives in
`ObservationFhirMapper.addCoding(...)`.

## Validation gates

Before persisting a code on an entity, normalise + validate:

- `TerminologyCodes.normalizeAndRequireValidLoinc(value)` — trims +
  validates `n{1,7}-d`; throws `IllegalArgumentException` (mapped to
  400 by the global handler) for malformed non-blank input.
- `normalizeAndRequireValidAtc(value)` — uppercases + validates
  `L##LL##`.
- `normalizeAndRequireValidRxNorm(value)` — validates 1–12 digits.

Blank input → `null` (drop), never throw. This is the contract every
controller should respect.

## CapabilityStatement (`/metadata`)

Lives in `fhir/provider/`. When adding a new resource type or operation:

1. Update the `Resource` block in the CapabilityStatement provider.
2. Add the `SearchParameter` set if querying is supported.
3. Add the operation (e.g. `$everything`, `$export`) under `operation`.
4. Add an integration test that calls `/metadata` and asserts the new
   capability is advertised.

## SMART-on-FHIR

Discovery scaffolding lives under `fhir/smart/`. Patient write is now
on (feature-flagged); Encounter + Observation write are the remaining
pieces that unlock the full SMART app launch flow. When extending,
keep `.well-known/smart-configuration` aligned with the Keycloak OIDC
issuer (`app.auth.oidc.issuer-uri`).

## FHIR write API (Patient — row 20 foundation)

Gated by `app.fhir.write.enabled` (env `FHIR_WRITE_ENABLED`,
default `false`). When off, `@Create` + `@Update` return 405. The
flag is also surfaced in the `CapabilityStatement` —
`Patient.conditionalCreate=true` is advertised only when on.

### PUT /Patient/{id}

`PatientFhirWriteService.update()` applies the **FHIR-mutable subset
only**: address (line/city/state/postalCode/country), telecom
(phone-mobile, phone-home, email), and `active`. **Never** overwrites
name, DOB, gender, or identifiers — those flow through the
registration admin path which carries the deeper audit trail. The
honored-field set is encoded in `PatientFhirMapper.applyFhirUpdates`.

Emits `AuditEventType.PATIENT_UPDATE`.

### POST /Patient (conditional-create)

**Never auto-provisions a new Patient row** — same trust boundary as
HL7 ADT inbound (see the `empi-identity` skill: "Never auto-create a
Patient from an unknown EMPI alias"). Honored response shape:

| Matches on the `If-None-Exist` MRN | HTTP |
| --- | --- |
| 0 | `404 Not Found` + OperationOutcome (NOTFOUND) |
| 1 | `200 OK` with the existing Patient resource |
| >1 | `412 Precondition Failed` + OperationOutcome (MULTIPLEMATCHES) |
| missing / non-MRN `If-None-Exist` | `422` + OperationOutcome (BUSINESSRULE / NOTSUPPORTED) |

The `If-None-Exist` parser accepts only
`identifier=<system>|<value>` where `<system>` matches
`urn:hms:hospital:<hospitalId>:mrn`. Any other search parameter is
rejected as 422.

Multi-match is kept unreachable **for the active set** by
`V101__fhir_write_patient_idempotency.sql` — a partial unique index on
`(hospital_id, LOWER(mrn))` where `is_active = true AND btrim(mrn) <>
''`. Inactive registrations and whitespace-only MRNs are excluded
from that uniqueness scope, so `412 MULTIPLEMATCHES` is still
reachable if the conditional-create matcher considers active +
inactive rows together. The current matcher uses
`PatientHospitalRegistrationRepository.findActiveByHospitalIdAndIdentifier`
which only returns `r.active = true` rows, so 412 is unreachable in
practice today; if a future matcher widens this, audit the
reachability before relying on the 412 branch.

Emits `AuditEventType.PATIENT_ACCESS` on the matched read (no
`PATIENT_CREATE` — nothing is created).

**Deviation from FHIR R4 spec.** The spec says a conditional-create
with zero matches should return `201 Created` after provisioning. HMS
returns `404` instead, by deliberate policy — auto-provisioning would
silently expand the data surface beyond what the registration desk has
reviewed (same trust call as the HL7 ADT inbound). Do not "fix" this
back to the spec default; the `empi-identity` skill is the authority.
The `422 ⇒ missing If-None-Exist` branch is also an HMS deviation —
the spec allows POST without `If-None-Exist` to mean "unconditional
create". HMS forbids that path entirely.

### Feature-flag short-circuit ordering (load-bearing)

When `app.fhir.write.enabled=false`, the provider MUST return 405
**before** any request-shape validation. The current Patient provider
validates resource-id consistency before the write service throws
`MethodNotAllowedException`, which means a flag-off request with a
mismatched body id returns 422 instead of 405 — contradicting the
documented contract and breaking the flag-off ITs. The corrective
pattern:

```java
@Update
public MethodOutcome update(@IdParam IdType id, @ResourceParam Patient resource) {
    if (!writeService.isEnabled()) {
        throw new MethodNotAllowedException("FHIR write API is disabled.");
    }
    // ... request validation + writeService.update(...) ...
}
```

Caught in PR #343 Copilot review. The same fix is owed on `@Create`.

### Audit entityType MUST be the canonical PATIENT literal

`AuditEventLogServiceImpl` special-cases `entityType` matching the
literal `"PATIENT"` (case-insensitive). The Patient FHIR write code
initially used `"Patient"` (Pascal case from the FHIR resource type),
which silently disabled the patient-resolution path —
resource-name + id derived columns on the audit row stayed null.
Always use `"PATIENT"`, the same literal `AuditEventType.PATIENT_*`
implies. Caught in PR #343 Copilot review.

### Conditional-URL parameter parser is strict

`PatientFhirWriteService.extractIdentifierToken()` must enforce
exactly **one** `identifier=` parameter and **no other** search
parameters. A relaxed parser that accepts
`identifier=...&active=true&_count=10` contradicts the documented
contract and lets the conditional URL leak fields HMS never agreed
to honor. Caught in PR #343 Copilot review.

### Encounter + Observation write (row-20 follow-on, PR #350)

`PUT /Encounter/{id}` honors a very narrow subset only:
`period.end → checkoutTimestamp` (only when currently null) and
`reasonCode[0].text → chiefComplaint` (only when currently
blank). Tenant scope via `EncounterRepository.findByIdAndHospital_Id`
with a defence-in-depth hospital-equality check on the loaded
entity (missing scope or mismatch → 403). New
`AuditEventType.ENCOUNTER_UPDATE` with `entityType="ENCOUNTER"`.
Note: the constant is `ENCOUNTER_UPDATE` not `ENCOUNTER_UPDATED`
(naming-convention bug caught in PR #350 review — fix slated for
a follow-on rename once it has not yet been persisted to audit
history).

POST `/Encounter` is intentionally NOT exposed (encounter
provisioning has staff @ hospital + assignment @ hospital +
appointment-match invariants enforced by `Encounter.@PrePersist`
that an inbound FHIR sender cannot reliably satisfy).

`PUT /Observation/{id}` honors `Observation/labresult-{uuid}` only;
the body's `note[0].text` appends to `lab_results.notes` (pipe
separator; duplicate text is a no-op). `PUT /Observation/vital-*`
returns `422 BUSINESSRULE` because the 1:N `PatientVitalSign` →
Observation expansion has no single-row write target. Tenant
scope: `LabResult.labOrder.hospital.id` must match the active
hospital (missing or mismatched → 403). Audit:
`LAB_RESULT_UPDATED` with `entityType="LAB_RESULT"`.

### Audit naming convention

The "Care delivery workflow" enum group uses past-tense
`_UPDATED`: `APPOINTMENT_UPDATED`, `PRESCRIPTION_UPDATED`,
`LAB_RESULT_UPDATED`, `IMAGING_RESULT_UPDATED`. New event types
must follow suit. `ENCOUNTER_UPDATE` (PR #350) broke this and is
the open follow-on rename — renaming the constant AFTER it has
been persisted to audit history is a multi-step migration, so
land the rename before the row flips from `started → completed`.

## Named operations: `$export` + `$everything` (rows 21 / 22)

Foundation pass shipped on `feat/v1.1-fhir-bulk-and-everything`
(PR #351). Two independent flags under `app.fhir.operations.*`,
both default OFF:

- `app.fhir.operations.bulk-export.enabled` — gates POST
  `/api/fhir/$export` (system) + POST `/api/fhir/Patient/$export`
  (Patient-type level). Plain provider
  `FhirBulkExportOperationProvider` registered via
  `server.registerProvider(...)`. Returns `202 Accepted` +
  `Content-Location: /api/fhir-bulk-status/{jobId}`. The poll URL
  is a sibling Spring controller (`FhirBulkExportStatusController`)
  outside HAPI's `/api/fhir/*` mount because the FHIR servlet
  captures that whole path; canonical poll-URL mounting via HAPI
  `manualResponse=true` is the row-21 follow-on.
- `app.fhir.operations.everything.enabled` — gates
  `Patient/{id}/$everything` via `@Operation(name="$everything",
  type=Patient.class)` on `PatientFhirResourceProvider`,
  delegating to `PatientEverythingService`. Synchronous (Bundle
  assembled inline from existing read mappers).

`HmsCapabilityStatementProvider.applyOperationVisibility(cs)`
strips both `rest[].operation` and `rest[].resource[].operation`
entries for `export` / `everything` when the corresponding flag
is off — HAPI auto-emits both levels and the strip must visit
both lists.

### Patient-compartment `$everything` tenant gate (row 22)

**`PatientRepository.findById(uuid)` is NOT tenant-aware** —
covered in the `multi-tenancy-scoping` skill. The
`PatientEverythingService` must verify
`PatientHospitalRegistrationRepository.findByPatientIdAndHospitalId
(patientId, activeHospitalId).isPresent()` BEFORE adding the
Patient resource to the Bundle, otherwise the Patient resource
(name / DOB / address / phone / email — PHI) leaks across
tenants. The per-resource sub-queries are hospital-scoped, but
the Patient itself slips through if the gate is missing. Caught
on PR #351 Copilot review (High severity).

### Bulk-export tenant gate must DENY on null active hospital

`FhirBulkExportService.getJob` previously short-circuited the
cross-tenant guard when the active hospital was null — letting any
super-admin call see any tenant's jobs. The correct pattern is
to DENY first (return `Optional.empty()`) when
`HospitalContextHolder.getContextOrEmpty().getActiveHospitalId() ==
null`, THEN check the stored vs current hospital equality. See
the `multi-tenancy-scoping` skill for the full pattern. Caught
on PR #351.

### Bulk-data spec: 400 on malformed `_since` / `_outputFormat`

The FHIR Bulk Data Access spec requires the server to reject
invalid `_since` (ISO-8601 instant) and `_outputFormat`
(`application/fhir+ndjson` / `application/ndjson` / `ndjson`)
values with `400 Bad Request` + a FHIR `OperationOutcome`
explaining why. Silently returning `null` from a `parseInstant`
helper makes the runner fall back to a full export while the
client believes the incremental window held — observable as
duplicate-fanout the next quarter. Fix the parser to throw
`InvalidRequestException` with an `OperationOutcome.IssueType.VALUE`
issue when the input is non-blank but unparseable. Caught on
`FhirBulkExportOperationProvider.parseInstant` in PR #351.

### Dead `Parameters` construction is a code smell

When a `@Operation(manualResponse=true)` handler writes the
response body by hand (`response.getWriter().write(jsonLiteral)`),
do NOT also construct a HAPI `Parameters` resource that goes
nowhere — at first glance it looks like the response is built
from the model. Either:

1. Use HAPI's `IParser`:
   `String body = fhirContext.newJsonParser().encodeResourceToString(params);`
2. Or drop the unused `Parameters` builder entirely and only do
   the hand-rolled JSON.

The hand-rolled JSON is also fragile if `jobId.toString()` ever
returns a value needing JSON escaping (UUIDs are safe; arbitrary
text isn't). Caught on `FhirBulkExportOperationProvider` in PR
#351.

### `parseTypeList` ReDoS

`raw.split("\\s*,\\s*")` is a Sonar polynomial-backtracking
warning. The safe form for comma-separated FHIR `_type` input:

```java
return Arrays.stream(raw.split(","))
    .map(String::trim)
    .filter(s -> !s.isEmpty())
    .toList();
```

Caught on `FhirBulkExportOperationProvider.parseTypeList` in PR
#351.

## Read-only routing (row 35 foundation)

The `@Transactional(readOnly = true)` annotation on FHIR read service
methods is **load-bearing** — when the replica is enabled
(`app.datasource.replica.enabled=true`) those calls route to the
read replica via `ReadWriteRoutingDataSource`. Never remove
`readOnly = true` from a FHIR read path without re-auditing the
read-your-own-write semantics.

## Reference files

- `hospital-core/src/main/java/com/example/hms/fhir/FhirConfig.java`
- `hospital-core/src/main/java/com/example/hms/fhir/FhirWriteProperties.java` — write feature flag
- `hospital-core/src/main/java/com/example/hms/fhir/ApacheProxyAddressStrategy.java`
- `hospital-core/src/main/java/com/example/hms/fhir/mapper/ObservationFhirMapper.java`
- `hospital-core/src/main/java/com/example/hms/fhir/mapper/PatientFhirMapper.java` — read + `applyFhirUpdates` (write)
- `hospital-core/src/main/java/com/example/hms/fhir/provider/` — resource providers + CapabilityStatement
- `hospital-core/src/main/java/com/example/hms/fhir/smart/` — SMART discovery
- `hospital-core/src/main/java/com/example/hms/fhir/write/PatientFhirWriteService.java` — Patient PUT + conditional POST
- `hospital-core/src/main/java/com/example/hms/fhir/write/EncounterFhirWriteService.java` — Encounter PUT (row-20 follow-on)
- `hospital-core/src/main/java/com/example/hms/fhir/write/ObservationFhirWriteService.java` — Observation PUT (row-20 follow-on; labresult-only carve-out)
- `hospital-core/src/main/java/com/example/hms/fhir/FhirOperationsProperties.java` — `$export` + `$everything` flags
- `hospital-core/src/main/java/com/example/hms/fhir/bulk/FhirBulkExportOperationProvider.java` — row-21 plain provider
- `hospital-core/src/main/java/com/example/hms/fhir/bulk/FhirBulkExportStatusController.java` — `/api/fhir-bulk-status/{jobId}` poll + cancel
- `hospital-core/src/main/java/com/example/hms/fhir/everything/PatientEverythingService.java` — row-22 Bundle assembler
- `hospital-core/src/main/java/com/example/hms/terminology/TerminologyCodes.java`
- `docs/runbooks/fhir-write-api.md` — Patient + Encounter + Observation write runbook
- `docs/fhir-bulk.md` — `$export` + `$everything` runbook (row 21 + 22)

## Roadmap context

- Row 20: FHIR write API. Patient PUT + conditional POST shipped on
  `feat/v1.1-fhir-write-api` (PR #343). Encounter + Observation PUT
  shipped on `feat/v1.1-fhir-write-encounter-observation` (PR #350)
  — Observation honors `labresult-{uuid}` only; `vital-*` returns
  `422 BUSINESSRULE` per the deliverable's 1:N carve-out.
- Row 21: Bulk Data Access (`$export`) — foundation pass on
  `feat/v1.1-fhir-bulk-and-everything` (PR #351). In-memory job
  state; async NDJSON-to-S3 runner is the row-21 follow-on.
- Row 22: `$everything` (Patient compartment export) — foundation
  pass on the same PR #351. Synchronous Bundle assembly via
  existing read mappers; `_since` / `_type` / page cursor + SMART
  App Launcher conformance soak are the row-22 follow-on.
