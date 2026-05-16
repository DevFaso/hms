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

Multi-match is kept unreachable by
`V101__fhir_write_patient_idempotency.sql` — a partial unique index
on `(hospital_id, LOWER(mrn))` where `is_active = true` and `mrn` is
non-blank. Excludes inactive/legacy rows.

Emits `AuditEventType.PATIENT_ACCESS` on the matched read (no
`PATIENT_CREATE` — nothing is created).

### What's deferred

`Encounter` and `Observation` write paths are deferred to the row-20
follow-on. Observation is the harder one: the mapper has a 1:N
PatientVitalSign → 7 Observation expansion, so the write path will
likely only honor `labresult-{uuid}` namespace and reject
`vital-{uuid}-{component}` updates. The DTO contract (FHIR Encounter
/ Observation as the body) stays stable.

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
- `hospital-core/src/main/java/com/example/hms/terminology/TerminologyCodes.java`
- `docs/runbooks/fhir-write-api.md` — operational runbook

## Roadmap context

- Row 20: FHIR write API (Patient PUT + conditional POST shipped on
  `feat/v1.1-fhir-write-api`; Encounter + Observation deferred to
  the row-20 follow-on)
- Row 21: Bulk Data Access (`$export`) → S3-compatible bucket
- Row 22: `$everything` (Patient compartment export)

Build the rest of the write API (Encounter + Observation) before the
bulk / `$everything` operations — they both depend on write semantics
being well-defined.
