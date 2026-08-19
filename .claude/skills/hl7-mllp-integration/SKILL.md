---
name: hl7-mllp-integration
description: Use when adding or modifying HL7 v2 inbound handlers (ORU^R01, ADT^A01/A04/A08), the MLLP dispatcher, message recorder, or sender allowlist. Triggers on changes under hospital-core/src/main/java/com/example/hms/hl7/mllp/ or service/integration/.
---

# HL7 v2 MLLP integration

HMS terminates the HL7 v2 MLLP TCP listener at `Hl7MessageDispatcher` and
routes to domain services under `service/integration/`. The pattern is
load-bearing for **all** inbound HL7 work — follow it.

## The dispatch pipeline (do not skip a step)

1. **Frame + header parse** — `Hl7MessageInspector.parseHeader(body)`.
   Invalid MSH → record under sentinel id `"MLLP:?/?"`, return AR with
   the parse error.
2. **Sender allowlist** — `MllpAllowedSenderService.resolveHospital(MSH-3, MSH-4)`.
   Unknown sender → record + return AR `"Sender not authorised"`. Allowlist
   matching is **case-insensitive** (sender keys are uppercased before
   compare; reflect that in your idempotency keys too).
3. **Type-route** — `ORU^R01` → `handleOru`, `ADT^A01/A04/A08` → `handleAdt`.
   Anything else → record + AR `"Unsupported message type"`.
4. **Parse domain segments** — `Hl7v2MessageBuilder.parseOruR01` /
   `parseAdtMessage`. Unparseable → record FAILED + return AE.
5. **Call inbound service** — `MllpInbound{Lab,Adt}Service`. Map outcome
   to ACK: `ACCEPTED → AA`, `REJECTED_NOT_FOUND/INVALID → AE`,
   `REJECTED_CROSS_TENANT → AR`.

## Idempotency rules (MSH-10)

HL7 v2 only guarantees MSH-10 uniqueness **within a sending system**. The
dedup key is always the composite `(MSH-3, MSH-4, MSH-10)` — never MSH-10
alone. Enforce at two layers:

- App: pre-check via repository finder before save.
- DB: partial composite unique index (see V98 for the lab pattern).

Two different analyzers can legitimately emit the same MSH-10 — they
must NOT collapse. The partial index excludes legacy rows whose MSH-10
columns are NULL.

## The recorder is mandatory

Every dispatch path **must** call `IntegrationMessageRecorder.recordMessage`
(RECEIVED on accept, FAILED on any reject) so the DLQ / replay surface is
populated. The recorder runs in `REQUIRES_NEW` and swallows its own
exceptions; wrap calls in a belt-and-braces try/catch anyway so a
recorder-bean failure can never poison the ACK.

`integrationId` format: `"MLLP:" + sendingApp + "/" + sendingFacility`,
trimmed and truncated to **120 chars** (max length of
`clinical.integration_message_event.integration_id`).

## Cross-tenant gate

After EMPI resolves a patient, verify `PatientHospitalRegistration` for
`(patient.id, receivingHospital.id)` exists. Otherwise return
`REJECTED_CROSS_TENANT → AR`. A sender at hospital B cannot push updates
for a patient known only to hospital A.

## Audit on accept

On successful ingest emit an `AuditEventLog` via `AuditEventLogService`.
Wrap in try/catch and log warn on failure — audit must never roll back
the clinical write.

## Patient resolution

PID-3 → MRN → `EmpiService.findIdentityByAlias(EmpiAliasType.MRN, mrn)`.
Use `PatientRepository.findByIdUnscoped(patientId)` to bypass
`TenantAwareJpaRepository` (the MLLP worker has no `HospitalContext`).
Unknown MRNs are **rejected, not auto-created** — accepting external
systems to provision new Patient rows is a larger trust decision than
HL7 ingest is in a position to make.

## Lazy-load trap

`MllpAllowedSenderService.resolveHospital()` returns a Hospital with
**lazy** `getOrganization()`. Dereferencing `hospital.getOrganization()`
outside the allowlist transaction throws `LazyInitializationException`.
For dispatcher-level reject records, return `null` for organizationId
rather than touching the lazy association. If a service actually needs
the organizationId, fetch it inside its own `@Transactional` boundary or
extend the allowlist to project it.

## Reference files

- `hospital-core/src/main/java/com/example/hms/hl7/mllp/Hl7MessageDispatcher.java`
- `hospital-core/src/main/java/com/example/hms/hl7/mllp/Hl7MessageInspector.java`
- `hospital-core/src/main/java/com/example/hms/hl7/mllp/Hl7AckBuilder.java`
- `hospital-core/src/main/java/com/example/hms/hl7/mllp/MllpProperties.java`
- `hospital-core/src/main/java/com/example/hms/hl7/mllp/AdtVisitSyncProperties.java`
- `hospital-core/src/main/java/com/example/hms/service/integration/MllpInboundOutcome.java`
- `hospital-core/src/main/java/com/example/hms/service/integration/impl/MllpInboundLabServiceImpl.java`
- `hospital-core/src/main/java/com/example/hms/service/integration/impl/MllpInboundAdtServiceImpl.java`
- `hospital-core/src/main/java/com/example/hms/service/integration/impl/MllpInboundAdtVisitProjectionServiceImpl.java`
- `hospital-core/src/main/java/com/example/hms/utility/Hl7v2MessageBuilder.java`
- `docs/runbooks/hl7-adt-conflict-resolution.md` — the authoritative
  ADT conflict-resolution policy.

## Tests

Vendor-realistic sample messages live in
`hospital-core/src/test/java/com/example/hms/hl7/mllp/OruR01VendorSampleIngestionTest.java`
(Mindray BS-240 + Sysmex XN-1000). Mirror that style for any new inbound
trigger — full dispatcher → service plumbing, not just service unit
tests.
