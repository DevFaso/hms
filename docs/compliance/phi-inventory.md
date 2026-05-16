# PHI inventory

Roadmap row 38 — `v2.0 / Compliance / HIPAA-equivalent posture`.

This document inventories every Protected Health Information field
HMS stores or processes, where it lives, how it is protected, and
where it flows. It is the artefact behind §164.308(a)(1) (Risk
Analysis) and §164.502(b) (Minimum Necessary). The
[`hipaa-baa-template.md`](./hipaa-baa-template.md) Section 2 points at
this file.

HIPAA's "18 identifiers" framework is the structural anchor — each
section maps to one of the 18 categories defined at 45 CFR §164.514(b)(2)(i).
Within each section we list the actual HMS columns / fields, with
file-path citations so a reader can verify.

## Conventions

- **At-rest encryption.** "**Yes (gcm1)**" = column carries
  `@Convert(converter = EncryptedStringConverter.class)`. The
  underlying converter is AES-256-GCM with per-row IV and a `gcm1:`
  version prefix.
- **In-transit encryption.** Every public endpoint is TLS 1.2+
  terminated at Railway. The HL7 v2 MLLP listener runs over plain TCP
  per the protocol; deployments are expected to front it with an
  mTLS terminator (see [`MllpProperties` javadoc](../../hospital-core/src/main/java/com/example/hms/hl7/mllp/MllpProperties.java)).
- **Minimum necessary.** "Role" columns name the Spring role names
  authorised to read the field, derived from the per-controller
  `@PreAuthorize` / `@PostAuthorize` annotations and the
  `PermissionCatalog` constants.
- **N/A** in an encryption column means the field is not direct PHI
  (e.g. an internal UUID), but is captured here because it appears
  in the PHI surface (FHIR mapping, audit log, etc.).

---

## §164.514(b)(2)(i)(A) — Names

**Important:** the current encryption posture for patient names is
**plaintext** — Copilot review on PR #335 confirmed the entity lacks
`@Convert(EncryptedStringConverter)` on `firstName` / `lastName` /
`middleName`. This is listed as a P0 remediation item below (extend
field-level encryption to the §164.514(b)(2)(i)(A) identifiers).

| Field                              | Persistence                                                                          | At-rest enc | Roles with read       | Notes                                                  |
| ---------------------------------- | ------------------------------------------------------------------------------------ | ----------- | --------------------- | ------------------------------------------------------ |
| `Patient.firstName`                | `patient.first_name`                                                                 | No          | Clinical roles + self | **Plaintext today.** Encryption planned (P0 remediation) |
| `Patient.lastName`                 | `patient.last_name`                                                                  | No          | Clinical roles + self | **Plaintext today.** Encryption planned (P0 remediation) |
| `Patient.middleName`               | `patient.middle_name`                                                                | No          | Clinical roles + self | **Plaintext today.** Encryption planned (P0 remediation) |
| `User.firstName` / `User.lastName` | `users.first_name` / `users.last_name`                                               | No          | Self + admin          | Workforce names — not PHI under HIPAA, listed for completeness |
| Staff names                        | derived via `Staff.user → User.firstName/lastName` join                              | No          | Self + colleagues     | Workforce names (no `first_name`/`last_name` directly on `Staff`) |
| `PatientAllergy.recordedBy` (display) | derived from `User` join                                                          | No          | Clinical roles        | Workforce                                              |

## §164.514(b)(2)(i)(B) — Geographic subdivisions

| Field                              | Persistence                  | At-rest enc | Roles with read       | Notes                                                  |
| ---------------------------------- | ---------------------------- | ----------- | --------------------- | ------------------------------------------------------ |
| `Patient.address` (legacy single-line) | `patient.address`        | Yes (gcm1)  | Clinical roles + self | Encrypted via `EncryptedStringConverter`               |
| `Patient.addressLine1`             | `patient.address_line1`      | Yes (gcm1)  | Clinical roles + self |                                                        |
| `Patient.addressLine2`             | `patient.address_line2`      | Yes (gcm1)  | Clinical roles + self |                                                        |
| `Patient.city`                     | `patient.city`               | No          | Clinical roles + self | **Plaintext today.** P0 remediation candidate          |
| `Patient.state`                    | `patient.state`              | No          | Clinical roles + self | **Plaintext today.** P0 remediation candidate          |
| `Patient.zipCode`                  | `patient.zip_code`           | No          | Clinical roles + self | **Plaintext today.** P0 remediation candidate          |
| `Patient.country`                  | `patient.country`            | No          | Clinical roles + self | Coarser than ZIP; treated as low-sensitivity           |
| `Hospital.address`                 | `hospital.address`           | No          | All authenticated     | Hospital location is public information                |

## §164.514(b)(2)(i)(C) — Dates more specific than year

`@Convert(EncryptedStringConverter)` is a `String → String` JPA
converter; it cannot be applied to `LocalDate` / `LocalDateTime`
columns. All clinical timestamps therefore live in plaintext today.
HIPAA accepts this when the column is protected by row-level tenant
scoping + role-based access; an alternative posture (server-side
pseudonymisation of dates by shifting per patient) is P2 — see
[`hipaa-gap.md`](./hipaa-gap.md).

| Field                              | Persistence                                                                          | At-rest enc | Roles with read       | Notes                                                  |
| ---------------------------------- | ------------------------------------------------------------------------------------ | ----------- | --------------------- | ------------------------------------------------------ |
| `Patient.dateOfBirth`              | `patient.date_of_birth`                                                              | No          | Clinical roles + self | `LocalDate` — JPA converter cannot apply               |
| `Encounter.encounterDate`          | `clinical.encounters.encounter_date`                                                 | No          | Clinical roles + self |                                                        |
| `Admission.admissionDateTime`      | `admissions.admission_date_time`                                                     | No          | Clinical roles + self |                                                        |
| `LabResult.resultDate`             | `lab.lab_results.result_date`                                                        | No          | Clinical roles + self |                                                        |
| `Prescription.prescribedDate`      | `clinical.prescriptions.prescribed_date`                                             | No          | Clinical roles + self |                                                        |
| `Appointment.scheduledTime`        | `appointments.scheduled_time`                                                        | No          | Clinical roles + self |                                                        |
| `PatientProblem.onsetDate`         | `clinical.patient_problems.onset_date`                                               | No          | Clinical roles + self |                                                        |
| `DischargeSummary.dischargedAt`    | `discharge_summaries.discharged_at`                                                  | No          | Clinical roles + self |                                                        |

## §164.514(b)(2)(i)(D) — Telephone numbers

| Field                              | Persistence                              | At-rest enc | Roles with read       | Notes                                                  |
| ---------------------------------- | ---------------------------------------- | ----------- | --------------------- | ------------------------------------------------------ |
| `Patient.phoneNumberPrimary`       | `patient.phone_number_primary`           | No          | Clinical roles + self | **Plaintext today.** P0 remediation candidate          |
| `Patient.phoneNumberSecondary`     | `patient.phone_number_secondary`         | No          | Clinical roles + self | **Plaintext today.** P0 remediation candidate          |
| `Patient.emergencyContactPhone`    | `patient.emergency_contact_phone`        | Yes (gcm1)  | Clinical roles + self | Encrypted via `EncryptedStringConverter`               |
| `User.phoneNumber`                 | `users.phone_number`                     | No          | Self + admin          | Workforce — not PHI                                    |

## §164.514(b)(2)(i)(E) — Fax numbers

Not collected. HMS does not capture fax numbers; the few fax-routing
integrations rely on per-deployment external services.

## §164.514(b)(2)(i)(F) — Email addresses

| Field                              | Persistence                              | At-rest enc | Roles with read       | Notes                                                  |
| ---------------------------------- | ---------------------------------------- | ----------- | --------------------- | ------------------------------------------------------ |
| `Patient.email`                    | `patient.email`                          | No          | Clinical roles + self | **Plaintext today.** Used for activation links + AVS delivery. P0 remediation candidate |
| `User.email`                       | `users.email`                            | No          | Self + admin          | Workforce; used for password reset + login flows       |

## §164.514(b)(2)(i)(G) — Social Security numbers

Not directly collected. HMS targets non-US deployments and uses
country-specific national identifiers; see §164.514(b)(2)(i)(R) below.

## §164.514(b)(2)(i)(H) — Medical record numbers

The `Patient` entity does **not** carry a dedicated `medical_record_number`
column today. MRNs are surfaced via two adjacent mechanisms:

| Field                              | Persistence                              | At-rest enc | Roles with read       | Notes                                                  |
| ---------------------------------- | ---------------------------------------- | ----------- | --------------------- | ------------------------------------------------------ |
| `PatientHospitalRegistration.mrn`  | `patient_hospital_registrations.mrn`     | No          | Clinical roles + self | Per-hospital MRN, single-system                        |
| `EmpiIdentityAlias.aliasValue` (where `aliasType = MRN`) | `empi_identity_aliases.alias_value` | No | Clinical roles + self | EMPI cross-system MRN aliases (one row per sending system) |
| `EmpiMasterIdentity.mrnSnapshot`   | `empi_master_identities.mrn_snapshot`    | No          | Clinical roles + self | Denormalised snapshot for fast EMPI lookup             |

All three are **plaintext today** and are P0 remediation candidates for
the encryption-extension work.

## §164.514(b)(2)(i)(I) — Health plan beneficiary numbers

| Field                              | Persistence                              | At-rest enc | Roles with read       | Notes                                                  |
| ---------------------------------- | ---------------------------------------- | ----------- | --------------------- | ------------------------------------------------------ |
| `PatientInsurance.policyNumber`    | `patient_insurances.policy_number`       | No          | Finance + self        | **Plaintext today.** P0 remediation candidate          |
| `PatientInsurance.groupNumber`     | `patient_insurances.group_number`        | No          | Finance + self        | **Plaintext today.** P0 remediation candidate          |

## §164.514(b)(2)(i)(J) — Account numbers

| Field                              | Persistence                              | At-rest enc | Roles with read       | Notes                                                  |
| ---------------------------------- | ---------------------------------------- | ----------- | --------------------- | ------------------------------------------------------ |
| `BillingInvoice.invoiceNumber`     | `billing.invoices.invoice_number`        | No          | Finance + self        | Internal generated; not PHI in isolation               |
| `Payment.referenceNumber`          | `billing.payments.reference_number`      | No          | Finance + self        | Payment-processor reference                            |

## §164.514(b)(2)(i)(K) — Certificate/license numbers

| Field                              | Persistence                              | At-rest enc | Roles with read       | Notes                                                  |
| ---------------------------------- | ---------------------------------------- | ----------- | --------------------- | ------------------------------------------------------ |
| `Staff.licenseNumber`              | `staff.license_number`                   | No          | Admin                 | Workforce credential — not patient PHI                |

## §164.514(b)(2)(i)(L) — Vehicle identifiers + serial numbers

Not collected.

## §164.514(b)(2)(i)(M) — Device identifiers + serial numbers

| Field                              | Persistence                              | At-rest enc | Roles with read       | Notes                                                  |
| ---------------------------------- | ---------------------------------------- | ----------- | --------------------- | ------------------------------------------------------ |
| `LabSpecimen.accessionNumber`      | `lab.lab_specimens.accession_number`     | No          | Clinical roles        | Lab analyzer-issued; per-specimen                      |
| HL7 v2 MSH-10 message control id   | inbound only — `integration_messages.payload` | No     | Operator + admin      | Inbound message envelope; raw HL7 stored for replay/DLQ |

## §164.514(b)(2)(i)(N) — Web URLs

Not collected as direct identifiers; patient portal URLs are generic.

## §164.514(b)(2)(i)(O) — IP addresses

| Field                              | Persistence                              | At-rest enc | Roles with read       | Notes                                                  |
| ---------------------------------- | ---------------------------------------- | ----------- | --------------------- | ------------------------------------------------------ |
| `AuditEventLog.ipAddress`          | `audit.audit_event_log.ip_address`       | No          | Admin + operator      | Captured for §164.308(a)(1)(ii)(D) activity review     |
| `AuditEventLog.userAgent`          | `audit.audit_event_log.user_agent`       | No          | Admin + operator      |                                                        |

**Retention policy** for these fields is the same as the audit log
itself — see §164.316(b)(2)(i) in the gap analysis.

## §164.514(b)(2)(i)(P) — Biometric identifiers

| Field                              | Persistence                                                              | At-rest enc | Roles with read | Notes                                                  |
| ---------------------------------- | ------------------------------------------------------------------------ | ----------- | --------------- | ------------------------------------------------------ |
| MFA TOTP secret                    | `user_mfa_enrollments.totp_secret` (encrypted via `TotpSecretEncryptor`) | Yes (custom) | Self           | Not strictly a biometric, but listed for the auth-factor inventory |

No fingerprint / face / iris / voice data is collected.

## §164.514(b)(2)(i)(Q) — Full-face photographs

| Field                              | Persistence                              | At-rest enc | Roles with read       | Notes                                                  |
| ---------------------------------- | ---------------------------------------- | ----------- | --------------------- | ------------------------------------------------------ |
| `User.profileImageUrl`             | `users.profile_image_url`                | No          | Self + colleagues     | Workforce profile photos — stored as object-storage URLs |
| `PatientUploadedDocument.fileKey`  | `patient_uploaded_documents.file_key`    | No          | Clinical roles + self | May contain medical images / referral letters         |

**Note:** `Patient` does not carry a profile-image column in this
release; patient self-uploaded photos live in
`patient_uploaded_documents`. The underlying file content sits on
Railway-managed object storage (or the platform-equivalent).
Object-storage encryption-at-rest is inherited from the platform. The
file metadata (key, name, MIME type) lives in the relational DB; the
file body does not.

## §164.514(b)(2)(i)(R) — Any other unique identifying number, characteristic, or code

The `Patient` entity does **not** carry a dedicated
`national_id_number` column today. Country-specific national
identifiers are surfaced via the EMPI alias table (one row per
alias type, keyed by `EmpiAliasType`):

| Field                              | Persistence                              | At-rest enc | Roles with read       | Notes                                                  |
| ---------------------------------- | ---------------------------------------- | ----------- | --------------------- | ------------------------------------------------------ |
| `Patient.id` (UUID)                | `patient.id`                             | N/A         | Clinical roles + self | Re-identification is feasible if combined with names + DOB; per HIPAA, the UUID alone is a §164.514(b)(2)(i)(R) "other unique characteristic" |
| `EmpiIdentityAlias.aliasValue` (where `aliasType` ∈ `{NATIONAL_ID, …}`) | `empi_identity_aliases.alias_value` | No | Clinical roles + self | **Plaintext today.** P0 remediation candidate (covers national IDs, payer IDs, foreign-system MRNs alongside the MRN entries surfaced under §164.514(b)(2)(i)(H)) |

---

## Clinical PHI (record body — not §164.514 identifiers, but PHI itself)

Beyond the 18 identifiers, the body of the clinical record is PHI by
virtue of being identifiable health information. The headline tables
follow; the schema reference is the canonical source of truth
(`hospital-core/src/main/resources/db/migration/V1__Initial_Schema.sql`
+ subsequent `V*__*.sql` migrations).

| Table                                                                                | Domain                              | At-rest enc on sensitive cols | Notes                                                  |
| ------------------------------------------------------------------------------------ | ----------------------------------- | ----------------------------- | ------------------------------------------------------ |
| `clinical.encounters`                                                                | Visits, triage, room assignment     | No (structural)               | Notes column is plaintext — review for v2.1            |
| `clinical.encounter_notes`                                                           | Free-text clinical notes            | Yes (gcm1) on note body       |                                                        |
| `clinical.patient_problems`                                                          | Problem list (ICD-10 / LOINC)       | No (structural)               | Adding LOINC binding in row 26                         |
| `clinical.patient_allergies`                                                         | Allergy + reaction                  | No (structural)               |                                                        |
| `clinical.prescriptions` + `clinical.medication_dispenses`                           | Medications + dispense              | No (structural)               | Pharmacy module                                        |
| `lab.lab_orders` + `lab.lab_results` + `lab.lab_specimens`                           | Lab results + abnormal flags        | No (structural)               | Inbound ORU^R01 persistence — row 23                   |
| `admissions`                                                                         | Inpatient stays                     | No (structural)               | Adding ADT visit-sync columns in row 24                |
| `discharge_summaries`                                                                | Discharge document                  | Yes (gcm1) on body            |                                                        |
| `appointments`                                                                       | Scheduled visits                    | No (structural)               |                                                        |
| `imaging_studies`                                                                    | Imaging order + interpretation      | No (structural)               | File body on object storage                            |
| `vital_signs`                                                                        | BP, HR, temp, SpO2                  | No (structural)               | High-volume; encryption would impact dashboard perf    |
| `consent`                                                                            | PatientConsent records              | No (structural)               | Consent flags for marked records                       |
| `clinical.patient_uploaded_documents`                                                | Patient-provided documents          | No (metadata)                 | File body on object storage; not in DB                 |

**Encryption coverage today (audited against `@Convert(EncryptedStringConverter)`
usages in the JPA model).** A grep of the entity layer
(`hospital-core/src/main/java/com/example/hms/model/**/*.java`) shows
`EncryptedStringConverter` applied to **14 columns** across 3 entities:

| Entity         | Encrypted columns                                                                                                                   |
| -------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| `Patient`      | `address`, `address_line1`, `address_line2`, `emergency_contact_name`, `emergency_contact_phone`, `emergency_contact_relationship`, `allergies`, `medical_history_summary`, `care_team_notes`, `chronic_conditions` |
| `Prescription` | 3 narrative columns (sig / instructions / notes)                                                                                    |
| `Dispense`     | 1 narrative column                                                                                                                  |

Plus the bespoke TOTP encryption on `user_mfa_enrollments.totp_secret`
via `TotpSecretEncryptor` (separate converter, separate key derivation).

**Gap vs HIPAA expectations.** The §164.514(b)(2)(i) identifier columns
that a typical HIPAA-equivalent posture would encrypt-at-rest — names,
phone numbers, email, city/state/zip, DOB-as-string, MRNs, national
IDs, insurance policy / group numbers — are **plaintext today**. The
threat model accepts this because:

1. The columns are protected by per-row tenant scoping
   (`TenantAwareJpaRepository`) and role-based access control
   (`PermissionCatalog` + `@PreAuthorize`).
2. Railway managed-Postgres provides transparent at-rest encryption on
   the volume (platform-level AES). Field-level encryption is a defence
   in depth on top of that.
3. Wide-coverage columns (vitals, encounter timestamps, ICD codes) are
   queried in bulk by dashboards and BPA rules; column-level encryption
   would force a load-then-decrypt round-trip per row.

The plaintext-identifier columns are **P0 remediation candidates** in
[`hipaa-gap.md`](./hipaa-gap.md) — the encryption-extension work is
scoped under §164.312(a)(2)(iv) (Encryption + Decryption). The current
"Present" rating on that control reflects the existence of an
operating field-level encryption mechanism on the narrative columns,
not full coverage of the 18-identifier surface; that distinction is
made explicit on the gap document's scorecard.

The boundary is reviewed annually as part of the §164.308(a)(8)
periodic evaluation cadence (currently a P1 item in
[`hipaa-gap.md`](./hipaa-gap.md)).

---

## Dataflow map

```
Inbound channels
  ├─ Patient portal (web)      ─┐
  ├─ Patient mobile (iOS/Android)│
  ├─ Clinician portal (web)     │   TLS 1.2+ to
  ├─ FHIR R4 REST API           │   Railway-terminated
  ├─ CDS Hooks (POST cards)     │   load balancer
  ├─ HL7 v2 MLLP (analyzers)    │
  ├─ Webhooks (partner systems) ─┘
                                  │
                                  ▼
  Spring Boot application (hospital-core)
    ├─ Authentication: Keycloak OIDC + MFA for privileged roles
    ├─ Authorization: PermissionCatalog + @PreAuthorize / @PostAuthorize
    ├─ Tenant scope: HospitalContext + TenantAwareJpaRepository
    ├─ Field-level encryption: AES-256-GCM on identifier columns
    ├─ Audit: AuditEventLog row per access / modification / export
                                  │
                                  ▼
  Storage tier
    ├─ Managed Postgres (Railway)   — TLS in transit, AES at rest (platform)
    ├─ Redis (token blacklist, idle-tracker)  — TLS in transit
    ├─ Object storage (file bodies)  — TLS in transit, AES at rest (platform)
                                  │
                                  ▼
  Observability + reporting
    ├─ Splunk HEC               — TLS in transit; aud-log retention ≥ 6 yrs (target)
    ├─ Grafana / OTel           — metrics only (no PHI)
    ├─ Email + SMS notifications — via vendor APIs (BAA / sub-BAA required)
```

PHI never leaves the encryption + audit perimeter to a system without
a documented BAA or sub-BAA.

---

## Per-customer summary template

For each customer, a per-tenant snapshot is generated by:

```bash
./gradlew :hospital-core:bootRun --args='--phi.inventory.export=true --phi.inventory.tenant=<hospital-id>'
```

*(Pending — engineering item, target close 2026-09-30; tracked in the
HIPAA gap analysis P0 backlog.)*

This produces a CSV of: column → row-count → encryption status → last
access (from audit log) for the named tenant, suitable for inclusion in
the customer's compliance binder.

---

Last updated: 2026-05-16. Update on every schema migration that adds
or removes a column carrying a §164.514(b)(2)(i) identifier or a
clinical-PHI body field.
