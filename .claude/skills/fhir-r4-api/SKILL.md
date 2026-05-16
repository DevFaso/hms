---
name: fhir-r4-api
description: Use when adding or modifying FHIR R4 resource mappers, providers, CapabilityStatement, SMART-on-FHIR endpoints, or terminology coding (LOINC/ICD-10/ICD-11/ATC/RxNorm/CVX). Triggers on changes under hospital-core/src/main/java/com/example/hms/fhir/ or terminology/.
---

# FHIR R4 API

HMS exposes a FHIR R4 read-only API today; row 20 of the roadmap adds
POST/PUT (write). The base URL is derived from the incoming request
(honoring `X-Forwarded-*` headers behind Railway/nginx) unless
`FHIR_SERVER_BASE_URL` env var is set to an absolute HTTPS URL.

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

Discovery scaffolding lives under `fhir/smart/`. Today read-only; row
20 (FHIR write API) is what unlocks the full SMART app launch flow.
When extending, keep `.well-known/smart-configuration` aligned with the
Keycloak OIDC issuer (`app.auth.oidc.issuer-uri`).

## Read-only routing (row 35 foundation)

The `@Transactional(readOnly = true)` annotation on FHIR read service
methods is **load-bearing** — when the replica is enabled
(`app.datasource.replica.enabled=true`) those calls route to the
read replica via `ReadWriteRoutingDataSource`. Never remove
`readOnly = true` from a FHIR read path without re-auditing the
read-your-own-write semantics.

## Reference files

- `hospital-core/src/main/java/com/example/hms/fhir/FhirConfig.java`
- `hospital-core/src/main/java/com/example/hms/fhir/ApacheProxyAddressStrategy.java`
- `hospital-core/src/main/java/com/example/hms/fhir/mapper/ObservationFhirMapper.java`
- `hospital-core/src/main/java/com/example/hms/fhir/provider/` — resource providers + CapabilityStatement
- `hospital-core/src/main/java/com/example/hms/fhir/smart/` — SMART discovery
- `hospital-core/src/main/java/com/example/hms/terminology/TerminologyCodes.java`

## Roadmap context

- Row 20: FHIR write API (Patient, Encounter, Observation + `If-None-Exist` conditional create)
- Row 21: Bulk Data Access (`$export`) → S3-compatible bucket
- Row 22: `$everything` (Patient compartment export)

Build the write API before the bulk / `$everything` operations — they
both depend on write semantics being well-defined.
