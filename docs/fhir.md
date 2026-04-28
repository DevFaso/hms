# HMS FHIR R4 façade

> P0.1 of the Epic-alignment workstream (see [`claude/finding-gaps.md`](../claude/finding-gaps.md)).
>
> **Goal:** make HMS speak FHIR R4 so it can be a node in an OpenHIE / DHIS2 /
> OpenMRS network — the realistic interoperability ecosystem for West African
> health deployments.

## Status

| Resource          | Read | Search | Search params                                                              |
| ----------------- | :--: | :----: | -------------------------------------------------------------------------- |
| `Patient`         |  ✅  |   ✅   | `_id`, `identifier`, `name`, `given`, `family`, `birthdate`, `gender`, `phone`, `email`, `active` |
| `Encounter`       |  ✅  |   ✅   | `_id`, `patient`, `subject`                                                |
| `Observation`     |  ✅  |   ✅   | `patient`, `subject`, `category` (`vital-signs`, `laboratory`)             |
| `Condition`       |  ✅  |   ✅   | `patient`, `subject`                                                       |
| `MedicationRequest` |  ✅  |   ✅   | `patient`, `subject`                                                       |
| `Immunization`    |  ✅  |   ✅   | `patient`, `subject`                                                       |

Write/Create/Update/Delete are not exposed yet. The server is read-only by
design until terminology binding (gap #5) and the inbound MLLP listener
(gap #2) land.

## Where it lives

- Servlet registration: `com.example.hms.fhir.FhirConfig` mounts a HAPI
  `RestfulServer` at `/fhir/*` (becomes `/api/fhir/*` via the application
  context-path).
- Resource providers: `com.example.hms.fhir.provider.*`.
- Entity → FHIR mappers: `com.example.hms.fhir.mapper.*`.
- Server base URL advertised in the CapabilityStatement honours
  `X-Forwarded-Proto` / `X-Forwarded-Host` so it remains correct behind
  Railway and nginx (`ApacheProxyAddressStrategy`).

## Authentication & tenancy

- `GET /fhir/metadata` is public (per the FHIR R4 spec — clients fetch the
  CapabilityStatement before authenticating).
- Every other `/fhir/**` endpoint requires the same Bearer JWT used elsewhere.
- All reads go through the existing JPA repositories, so the tenant scope
  applied via `HospitalContextHolder` and the `tenantContext` SpEL bean is
  preserved without any extra plumbing.
- CSRF is exempted on `/fhir/**` (server-to-server clients use Bearer JWT,
  not browser cookies).

## Quick smoke test

```bash
# 1. Boot with H2 in-memory
./gradlew :hospital-core:bootRun -Pargs='--spring.profiles.active=local-h2'

# 2. CapabilityStatement (no auth)
curl -s http://localhost:8081/api/fhir/metadata | jq '.resourceType, .fhirVersion'

# 3. Read a Patient (Bearer JWT obtained via /api/auth/login)
TOKEN=...   # paste the access_token
curl -s -H "Authorization: Bearer $TOKEN" \
     http://localhost:8081/api/fhir/Patient/<uuid> | jq

# 4. Search a Patient by name
curl -s -G -H "Authorization: Bearer $TOKEN" \
     --data-urlencode 'name=Diallo' \
     http://localhost:8081/api/fhir/Patient | jq '.entry[].resource.name'
```

## Terminology bindings (current vs target)

| Field                  | Current binding                                | Target (P1, gap #5) |
| ---------------------- | ---------------------------------------------- | --------------------- |
| `Patient.identifier`   | `urn:hms:patient:id`, `urn:hms:hospital:{id}:mrn` | unchanged             |
| `Condition.code`       | ICD-10 / ICD-11 when `icdVersion` set, else local | ICD-10 + WHO ICD-11   |
| `MedicationRequest.medication.coding` | RxNorm if numeric, else local | RxNorm + WHO ATC      |
| `Observation.code` (vitals) | LOINC                                     | LOINC                 |
| `Observation.code` (labs)   | local `urn:hms:lab:test-code`             | LOINC                 |
| `Immunization.vaccineCode`  | CDC CVX when `vaccineCode` set, else local | CDC CVX + DHIS2 PAHO  |

SNOMED CT bindings are intentionally deferred — SNOMED licensing is hard for
non-affiliate African deployments. Where SNOMED would normally be expected
(e.g. `Condition.code`) we use ICD-10/11 instead, which is freely usable and
matches the WHO SMART Guideline profiles used by DHIS2.

## How to add a new resource

1. Create a `*FhirMapper` under `com.example.hms.fhir.mapper`.
2. Create a `*FhirResourceProvider` implementing `IResourceProvider` under
   `com.example.hms.fhir.provider`.
3. Annotate it with `@Component`. `FhirConfig` auto-discovers all
   `IResourceProvider` beans, so the CapabilityStatement updates without any
   further wiring.
4. Add tests under `src/test/java/com/example/hms/fhir/`.

## Known gaps (intentional, deferred)

- **No CRUD writes** — read-only until terminology bindings land.
- **No paging** — `_count` / `_offset` ignored. First 50–250 results returned
  depending on resource.
- **No `_include` / `_revinclude`** beyond what HAPI advertises by default.
- **CDS Hooks 1.0** is now available — see [`cds-hooks.md`](cds-hooks.md).
- **SMART-on-FHIR App Launch 1.0** discovery is now available — see
  [`smart-on-fhir.md`](smart-on-fhir.md).
- **HL7 v2 MLLP listener** is now available — see [`hl7-mllp.md`](hl7-mllp.md).
- **Bulk export, `$everything`, `Bundle` transactions** — not yet.
- **`given` / `family` / `gender` Patient search params** — not yet honored;
  callers should use the broader `name` parameter. Pushing these into the
  JPA query is a P1 follow-up.
