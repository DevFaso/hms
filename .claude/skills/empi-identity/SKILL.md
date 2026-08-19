---
name: empi-identity
description: Use when working with Enterprise Master Patient Index (EMPI) — alias resolution, master identity records, merge events, MRN lookups across sending systems, or the upcoming probabilistic match (row 25). Triggers on changes under com.example.hms.model.empi, service.empi, or repository.empi.
---

# EMPI — Enterprise Master Patient Index

HMS resolves patient identity across sending systems (MRN, national ID,
payer ID, etc.) through the EMPI layer. The MLLP, FHIR write, and
EMPI-merge admin flows all go through it.

## Domain model

Three tables, all in the `empi.*` schema:

- **`EmpiMasterIdentity`** — one row per resolved person. Carries
  `empi_number` (unique stable ID), `patient_id` (FK to `Patient`),
  `organization_id`, `hospital_id`, `status` (enum
  `EmpiIdentityStatus`), `resolution_state` (enum
  `EmpiResolutionState`), `source_system`, `mrn_snapshot` (denormalised
  for fast lookup), `metadata` JSON. **One Patient row → at most one
  active master identity.**
- **`EmpiIdentityAlias`** — N rows per master identity, one per
  external identifier. `alias_type` (enum `EmpiAliasType`: `MRN`,
  `NATIONAL_ID`, `PAYER_ID`, etc.), `alias_value`, `source_system`,
  `active`.
- **`EmpiMergeEvent`** — append-only audit of merges. `merge_type`
  (enum `EmpiMergeType`), source + target master identity IDs, merge
  reason, executed-by staff.

## The resolution contract

Always resolve through the service, not by direct repository finds:

```java
Optional<EmpiIdentityResponseDTO> identity =
    empiService.findIdentityByAlias(EmpiAliasType.MRN, mrn);
```

- Returns `Optional.empty()` if no alias matches.
- Returns the resolved master identity DTO (including `patientId`) on
  match.
- Does NOT enforce tenant scope — the caller must apply the
  cross-tenant gate via `PatientHospitalRegistration` after resolving.

## Plaintext alias values today

`empi_identity_aliases.alias_value` is **plaintext** today. It will
encrypt under the row-38 encryption-extension P0 (when names + MRNs
get `@Convert(EncryptedStringConverter)`). Until then, treat the
column as PHI for access-control + audit purposes even though the at-
rest layer is platform-level only.

## Unknown MRN policy

**Never auto-create a Patient from an unknown EMPI alias.** This is a
load-bearing trust decision — accepting external systems to provision
new Patient rows would silently expand the data surface beyond what
the registration desk has reviewed. Every inbound path (HL7 ADT, FHIR
write once it lands) MUST reject unknown MRNs:

- HL7: `REJECTED_NOT_FOUND` → AE
- FHIR (when row 20 lands): 404 with a specific OperationOutcome issue

The exception is the explicit EMPI provisioning admin flow, which is
gated by `ROLE_HOSPITAL_ADMIN` or `ROLE_SUPER_ADMIN`.

## Merges (irreversible)

`EmpiMergeService` (when present) performs a merge:

1. Append `EmpiMergeEvent` row.
2. Reassign aliases from the source master identity to the target.
3. Reassign `patient_id` references on dependent clinical rows (if
   doing a deep merge).
4. Soft-delete source master identity (`status = MERGED`).
5. Emit `AuditEventType.PATIENT_MERGE`.

The merge is **append-only audit** — never DELETE merge events, never
mutate them after creation.

## EMPI v0 vs v1

- **v0 (row 25, v1.1)** — intra-tenant probabilistic match on name +
  DOB + sex + national ID. Target: ≥ 90 % recall on a labelled audit
  set. Receptionist UI shows candidate matches; the operator
  confirms.
- **v1 (row 40, v2.0)** — cross-tenant, national-ID-keyed, with an
  explicit FHIR `Consent` resource per data-sharing event.

Don't implement cross-tenant alias lookup until v1 — even when adding
v0, scope the matcher to a single hospital's `PatientHospitalRegistration`
set.

## Validation rules

Alias values:

- `MRN`, `NATIONAL_ID`, `PAYER_ID` — sender-defined shape; trim only,
  no format validation.
- `aliasType` MUST match an `EmpiAliasType` enum value — anything else
  is a 400 at the controller boundary.
- `source_system` is informational, not part of the lookup key.

The lookup key for "is this the same person?" is **`(alias_type,
alias_value)`** — NOT `source_system`. Two systems with the same MRN
value point to the same person if the alias_value matches (subject to
namespace, see below).

## Namespace caution

For `MRN`, the alias_value alone is the key. For `NATIONAL_ID`,
collisions are possible across countries. The roadmap text in row 40
calls for `Consent`-keyed cross-tenant in v1; until then, scope MRN
resolution per hospital via `PatientHospitalRegistration`.

## Reference files

- `hospital-core/src/main/java/com/example/hms/model/empi/EmpiMasterIdentity.java`
- `hospital-core/src/main/java/com/example/hms/model/empi/EmpiIdentityAlias.java`
- `hospital-core/src/main/java/com/example/hms/model/empi/EmpiMergeEvent.java`
- `hospital-core/src/main/java/com/example/hms/enums/empi/EmpiAliasType.java`
- `hospital-core/src/main/java/com/example/hms/enums/empi/EmpiIdentityStatus.java`
- `hospital-core/src/main/java/com/example/hms/enums/empi/EmpiResolutionState.java`
- `hospital-core/src/main/java/com/example/hms/enums/empi/EmpiMergeType.java`
- `hospital-core/src/main/java/com/example/hms/service/empi/EmpiService.java`
- `hospital-core/src/main/java/com/example/hms/payload/dto/empi/EmpiIdentityResponseDTO.java`
- `hospital-core/src/main/java/com/example/hms/mapper/empi/EmpiMapper.java`
