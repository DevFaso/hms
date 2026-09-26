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
- Every other `/fhir/**` endpoint requires the same Bearer JWT used elsewhere
  **and a role** (`SecurityConfig`):
  - reads (`GET`, and `POST <type>/_search`): the chart readers — `DOCTOR`,
    `PHYSICIAN`, `SURGEON`, `NURSE`, `MIDWIFE`, `RADIOLOGIST`,
    `ANESTHESIOLOGIST`, `PHYSIOTHERAPIST`, `SUPER_ADMIN`;
  - `POST $export`: `SUPER_ADMIN`, `HOSPITAL_ADMIN` (the pair its service admits);
  - every other method (the flag-gated writes): `DOCTOR`, `PHYSICIAN`,
    `SURGEON`, `NURSE`, `MIDWIFE`, `SUPER_ADMIN`.

  A patient token, and every non-clinical staff role, gets 403.
- **Tenancy is enforced once, at the servlet, by `FhirTenantBoundaryInterceptor`**
  — not by the providers, four of which (`Encounter`, `Condition`,
  `MedicationRequest`, `Immunization`) read with no hospital filter. The
  JPA repositories do not scope these entities (they are not `TenantScoped`),
  and where they do (`Patient`) they scope to every permitted hospital and
  organisation, not the one the request is acting in. See
  [Tenant boundary](#tenant-boundary) below.
- The role gate checks the **union** of the caller's roles across all their
  hospitals (a Spring Security path matcher sees only flat authorities): a
  DOCTOR at A who is a RECEPTIONIST at B passes it while acting at B. The
  tenant boundary then checks the role held **at the hospital the request is
  bound to**, and refuses that caller at B.
- There is no integration identity yet: no machine role is provisioned in
  any migration or in the realm, and no realm client has service accounts
  enabled. An integration signs in as a staff user and is gated by that
  user's role.
- CSRF is exempted on `/fhir/**` (server-to-server clients use Bearer JWT,
  not browser cookies).

## Tenant boundary

`FhirTenantBoundaryInterceptor` (registered first in `FhirConfig`) bounds
every request except `metadata` to one hospital, for every provider:

1. **Bind.** The hospital comes from the authenticated principal —
   `FhirTenantBoundary.boundHospital`: the active hospital, only if the
   principal holds it. `X-Hospital-Id` selects among the principal's own
   hospitals; a super-admin must supply it (global view is refused). No
   hospital → `403` + `OperationOutcome(forbidden)` before any provider runs.
   The caller must also hold, **at that hospital**, a role the request needs
   — reads: `DOCTOR`, `PHYSICIAN`, `SURGEON`, `NURSE`, `MIDWIFE`,
   `RADIOLOGIST`, `ANESTHESIOLOGIST`, `PHYSIOTHERAPIST`; `$export`:
   `HOSPITAL_ADMIN`; every other method: `DOCTOR`, `PHYSICIAN`, `SURGEON`,
   `NURSE`, `MIDWIFE` (a super-admin is global). Spring Security's authorities
   are the union across hospitals, so a doctor at A who is a receptionist at B
   is refused while acting at B. HMS tokens are checked against the live
   assignments; Keycloak tokens against their `role_assignments` claim.
2. **Gate the named id.** `GET`/`PUT` `<Type>/{id}` and
   `Patient/{id}/$everything` answer `404` unless the resource is visible at
   that hospital, with the same `ResourceNotFoundException` a provider throws
   for an unknown id — the provider never runs, so another hospital's row, a
   row that does not exist and an unparsable id answer identically.
3. **Filter the response.** Search bundles lose the entries not visible at the
   hospital and their `total` is corrected; `_count` / `_offset` are removed
   before the provider runs so the total is exact. `$everything` is exempt from
   this step (its sections follow the E8 treatment-relationship policy).

Visibility (`FhirTenantBoundary`): the row's own hospital, except `Patient`
and patient-uploaded `DocumentReference` (registered at the hospital) and lab
orders/results (ordering hospital or performing laboratory). A resource type
the boundary has not been taught is refused entirely; `FhirTenantBoundaryIT`
fails the build if a provider's type is missing.

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

| Field | Current binding | Target (P1, gap #5) |
| --- | --- | --- |
| `Patient.identifier` | `urn:hms:patient:id`, `urn:hms:hospital:{id}:mrn` | unchanged |
| `Condition.code` | ICD-10 / ICD-11 when `icdVersion` set (P1 #1: ICD-11 MMS format now validated), else local | ICD-10 + WHO ICD-11 |
| `MedicationRequest.medication.coding` | RxNorm if numeric, else local | RxNorm + WHO ATC |
| `MedicationCatalogItem` | ATC + RxNorm fields validated against canonical patterns (P1 #1) | ATC + RxNorm in MedicationRequest mapping once Prescription↔Catalog FK lands |
| `Observation.code` (vitals) | LOINC | LOINC |
| `Observation.code` (labs) | LOINC primary coding when `LabTestDefinition.loincCode` is set (P1 #1), with `urn:hms:lab:test-code` retained as a secondary identifier | LOINC |
| `Immunization.vaccineCode` | CDC CVX when `vaccineCode` set, else local | CDC CVX + DHIS2 PAHO |

P1 #1 binding is enforced at the application layer by
[`com.example.hms.terminology.TerminologyCodes`](../hospital-core/src/main/java/com/example/hms/terminology/TerminologyCodes.java),
which holds the canonical FHIR system URIs and the format validators
called from `LabTestDefinitionService`, `MedicationCatalogItemService`,
and `PatientServiceImpl`. Codes that fail the format check are rejected
with HTTP 400 before they ever reach a downstream FHIR consumer.

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
- **No paging** — `_count` / `_offset` are removed from every search by the
  tenant boundary. First 50–250 results returned depending on resource (the
  cap applies before the boundary filters, so a patient with more rows at
  other hospitals than the cap can see fewer of their own).
- **No `_include` / `_revinclude`** beyond what HAPI advertises by default.
- **CDS Hooks 1.0** is now available — see [`cds-hooks.md`](cds-hooks.md).
- **SMART-on-FHIR App Launch 1.0** discovery is now available — see
  [`smart-on-fhir.md`](smart-on-fhir.md).
- **HL7 v2 MLLP listener** is now available — see [`hl7-mllp.md`](hl7-mllp.md).
- **Bulk `$export` and `Patient/{id}/$everything`** are now available behind
  independent feature flags (default OFF) — see [`fhir-bulk.md`](fhir-bulk.md)
  for semantics and the enable runbook. **`Bundle` transactions** — not yet.
- **`given` / `family` / `gender` Patient search params** — not yet honored;
  callers should use the broader `name` parameter. Pushing these into the
  JPA query is a P1 follow-up.
