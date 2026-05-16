---
name: phi-encryption-audit
description: Use when adding new String columns that could carry PHI (names, addresses, phones, emails, MRNs, national IDs, clinical narratives), when wiring write actions that need audit emission, or when extending AuditEventType. Triggers on changes touching EncryptedStringConverter, AuditEventLog, AuditEventType, or Splunk HEC config.
---

# PHI encryption + audit emission

These two concerns travel together: a clinical write either lands PHI
(encrypt) or is observable (audit). Both must be wired correctly or the
HIPAA-equivalent posture (roadmap row 38) regresses.

## Field-level PHI encryption

`EncryptedStringConverter` is an AES-256-GCM JPA `AttributeConverter`
with per-row random IVs and a versioned wire format
(`gcm1:<Base64(iv || ciphertext+tag)>`). The version prefix allows
algorithm rotation without a destructive migration.

### When to apply

Apply `@Convert(converter = EncryptedStringConverter.class)` to any new
`String` column carrying a §164.514(b)(2)(i) identifier or a clinical
narrative. **Today's coverage is narrow**:

- `Patient.address`, `address_line1`, `address_line2`,
  `emergency_contact_name`, `emergency_contact_phone`,
  `emergency_contact_relationship`, `allergies`,
  `medical_history_summary`, `care_team_notes`, `chronic_conditions`
- `Prescription` narrative columns (sig / notes / instructions)
- `Dispense` narrative column

Plaintext today and on the P0 remediation backlog (row 38):
**Patient names, DOB, phone, email, city/state/zip, MRN aliases,
insurance policy/group numbers, national IDs**. When you touch one of
these columns, prefer to fix the encryption gap in the same PR (or open
a follow-on referencing the gap doc).

### Rules

- **Never** `@Converter(autoApply = true)` — that would silently
  encrypt every `String` column.
- The converter is `String → String`. It **cannot** apply to
  `LocalDate` / `LocalDateTime` / `UUID` / `Integer` columns. For
  dates, the threat-model decision is row-level + RBAC, not at-rest
  encryption.
- `null` ↔ `null`, blank ↔ blank — never encrypted. Empty PHI columns
  stay readable in DB tooling.
- On read, values **without** the `gcm1:` prefix pass through verbatim.
  This is the rolling-migration path — legacy plaintext rows coexist
  with freshly-encrypted writes.

### Key management

`app.encryption.key` is Base64-encoded 32 bytes, env-driven via
`APP_ENCRYPTION_KEY`. **No default in production** — the app fails
fast at startup if the env var is missing. Local dev runs with an
empty key (converter loaded but inactive).

Key rotation is a documented obligation in
`docs/compliance/hipaa-baa-template.md` §4.4 (12-month cycle); the
runbook is **pending** (`docs/runbooks/key-rotation.md` — P1 in
[`hipaa-gap.md`](../../../docs/compliance/hipaa-gap.md)).

### MFA TOTP secret

Separate encryption: `UserMfaEnrollment.totp_secret` uses
`TotpSecretEncryptor` (not `EncryptedStringConverter`). Different
key derivation, different converter. Don't mix them.

## Audit event emission

Every write that changes PHI or auth state MUST emit an audit event via
`AuditEventLogService.logEvent(AuditEventRequestDTO)`.

### Pattern

```java
try {
    AuditEventRequestDTO request = AuditEventRequestDTO.builder()
        .eventType(AuditEventType.LAB_RESULT_UPDATED)
        .status(AuditStatus.SUCCESS)
        .eventDescription("...")
        .entityType("LabResult")
        .resourceId(saved.getId().toString())
        .build();
    auditEventLogService.logEvent(request);
} catch (RuntimeException ex) {
    // Audit MUST NEVER roll back the clinical write — log + swallow.
    log.warn("audit emission failed for ...", ex);
}
```

### Event types

The full enum is `AuditEventType` (80+ values). Pick the most specific
existing value before adding a new one. Categories:

- Authentication & session (LOGIN, MFA_*, TOKEN_REFRESH, SESSION_TERMINATED, ...)
- User & access admin (USER_CREATE, ROLE_ASSIGNED, PERMISSION_GRANTED, ...)
- Patient identity & consent (PATIENT_ACCESS, PATIENT_EXPORT, BREAK_GLASS_ACCESS, CONSENT_*, RECORD_SHARE)
- Care delivery (APPOINTMENT_*, PRESCRIPTION_*, LAB_RESULT_*, IMAGING_RESULT_*)
- Data ops (DATA_ACCESS, DATA_EXPORT, DATA_DELETE, DATA_IMPORT)
- Pharmacy (DISPENSE_*, STOCK_*, MTM_*)
- Security & platform (SECURITY_*, INTEGRATION_CONFIGURED, IMPERSONATION_*)
- Tenant + hospital lifecycle (TENANT_*, HOSPITAL_*)

When adding a new event type, group it with the matching category in
the enum file.

### Hospital scope on the audit row

`AuditEventLogService` derives `hospitalName` from `assignment.hospital`
or an explicit hospital snapshot field. For SYSTEM-actor writes (HL7,
schedulers) there's no assignment — supply a hospital snapshot on the
request DTO so the audit row is hospital-scoped and surfaces in the
hospital-scoped audit query.

### Splunk HEC export

Audit rows export to Splunk HEC (when `SPLUNK_HEC_ENABLED=true`) via
the appender in `config/observability/SplunkLoggingProperties`. The
retention target is **≥ 6 years** (HIPAA §164.316(b)(2)(i)). Splunk
index retention is a P0 remediation item — verify the index config
when activating a new event type that will see high volume.

## Reference files

- `hospital-core/src/main/java/com/example/hms/security/EncryptedStringConverter.java`
- `hospital-core/src/main/java/com/example/hms/security/EncryptionKeyHolder.java`
- `hospital-core/src/main/java/com/example/hms/security/crypto/TotpSecretEncryptor.java`
- `hospital-core/src/main/java/com/example/hms/model/AuditEventLog.java`
- `hospital-core/src/main/java/com/example/hms/enums/AuditEventType.java`
- `hospital-core/src/main/java/com/example/hms/enums/AuditStatus.java`
- `hospital-core/src/main/java/com/example/hms/enums/AuditSource.java`
- `hospital-core/src/main/java/com/example/hms/service/AuditEventLogService.java`
- `hospital-core/src/main/java/com/example/hms/payload/dto/AuditEventRequestDTO.java`
- `hospital-core/src/main/java/com/example/hms/security/audit/CrossTenantReadAudit.java`
- `hospital-core/src/main/java/com/example/hms/config/observability/SplunkLoggingProperties.java`
- `docs/compliance/phi-inventory.md` — what's encrypted vs plaintext today
- `docs/compliance/hipaa-gap.md` — §164.312(a)(2)(iv) Encryption + Decryption control rating
