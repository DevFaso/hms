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
5. **Call inbound service** — `MllpInbound{Lab,Adt,Merge}Service`. Map
   outcome to ACK: `ACCEPTED → AA`, `REJECTED_NOT_FOUND/INVALID → AE`.
   There is no third mapping. `AR` belongs to the dispatcher's own
   transport-level refusals (bad MSH, sender not allowlisted, unsupported
   type) and to nothing a domain handler returns — see **Cross-tenant
   gate**.

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
so the DLQ / replay surface is populated. `FAILED` on any reject — every
reject, including the ordinary ones; a refusal filed as `RECEIVED` tells
anyone filtering a hospital's healthy inbound traffic that it was processed
without error. `RECEIVED` on accept where the path records accepts at all
(the ORU path does; the ADT and A40 paths record rejections only). The recorder runs in `REQUIRES_NEW` and swallows its own
exceptions; wrap calls in a belt-and-braces try/catch anyway so a
recorder-bean failure can never poison the ACK.

`integrationId` format: `"MLLP:" + sendingApp + "/" + sendingFacility`,
trimmed and truncated to **120 chars** (max length of
`clinical.integration_message_event.integration_id`).

## Cross-tenant gate

After EMPI resolves a patient, verify `PatientHospitalRegistration` for
`(patient.id, receivingHospital.id)` exists. A sender at hospital B cannot
push updates for a patient known only to hospital A.

**Return `REJECTED_NOT_FOUND`, exactly as for an identifier that exists
nowhere.** There is no `REJECTED_CROSS_TENANT` constant and there must not
be one again: it mapped to `AR` while an unknown identifier mapped to `AE`,
and a sender that can tell those two apart can send one message per
candidate identifier and collect the ones that are real in hospitals it
cannot read. That is an enumeration oracle over every identifier space HL7
reaches — closed for `ORU^R01` in #715 and for `ADT`/`ADT^A40` in #738.
The ACK must be identical in code **and text**; `ackForOutcome` builds one
answer for both, so do not hand-build an ack in a new handler.

Two rules follow, and both were real defects:

- **Gate before you answer anything else.** The A40 merge path has an
  already-merged no-op that answers `AA`, and it used to run before the
  gate — which told a sender that two identifiers it does not own resolve
  to one patient somewhere else. An accept leaks as readily as a reject.
- **Partial ownership is not partial permission.** A40 needs *both*
  patients registered locally. Owning one of the two must answer like
  owning neither, or a sender pairs its own legitimate MRN with any
  candidate and reads off whether the candidate exists elsewhere.

**Record the reason, never ACK it.** The refusal writes an
`integration_message_event` row (`"cross-tenant rejection"`, status
`FAILED`) through `MllpRecordingContext`, which the sender cannot read. Do
not put the raw message in it on a rejection path: the ACK is `AE`, senders
retry `AE`, and a full PID per retry turns the record into an unbounded PHI
sink. MSH-10 in the error message is enough to correlate.

**Pass a stable `correlationId`** on a rejection, via
`MllpRecordingContext.rejectionCorrelationId(integrationId, messageType,
reason)` and the eight-argument `recordMessage`.
`countUnresolvedDeadLetters` counts a `FAILED` row only when no *later* row
shares its correlation id, and the ACK for a refusal is `AE`, which senders
retry on a timer — so a random id per row puts one unresolved dead letter on
the operator's badge per retry, thousands a day for one misconfigured feed.
Derive the id from the sender, the message type and the reason and from
**nothing per-message**: an MSH-10 or an identifier in there defeats it, and
an identifier also puts PHI in an indexed column. Note what this does and
does not buy: a feed retrying the same broken thing stays **one** dead
letter however long it runs, but that row does not clear by itself when the
feed stops — it is the newest row for its correlation id, so it stays
counted until an operator resolves it.

The gate lives in the inbound services rather than in `EmpiServiceImpl`
because there is **no security context on an MLLP worker thread**: every
guard that resolves the caller's hospital from it reads a null active
hospital as "unscoped, allow". Do not add anything on this path that reads
the security context.

## Field widths

Every sender-controlled **identifier** (a field that is matched or keyed on)
is held to the width of its column **once, where it is first read** — the limits live
in `Hl7FieldBounds`. MSH-3/4/9/10 are checked in
`Hl7MessageInspector.parseHeader` (an invalid MSH, so `AR` before the
allowlist); PID-3 and MRG-1 in the ADT and A40 parsers; OBR-2 in
`MllpInboundLabServiceImpl`, because the ORU parser is shared with paths
where OBR-2 is not an accession; PV1-19 and PV1-3's point of care in the
visit projection, their only reader, which skips rather than refusing the
message - an over-width visit field must not drop a demographic update.
Check a field where it is **read**: refusing the whole message for a field
only an optional step reads rejects what works today.

- **Refuse, never truncate.** These are identifiers: a truncated MSH-10
  reads as a replay of any other id with the same prefix, a truncated MRN
  or placer can match someone else. A limit is the column's width, not the
  HL7 nominal length — senders exceed v2.5's 20-character MSH-10.
- **Do not add per-sink wrappers** (capping or sanitising a field where it
  is logged or recorded). That was tried and did not converge, and a setter
  into a `VARCHAR(255)` is a sink no wrapper sees. A new field that reaches
  a sink gets a bound in `Hl7FieldBounds`, checked where it is parsed.
- A refusal names the field and the limit, **never the value**.
- **Not yet covered: demographics.** PID-5/7/8/11 go into `Patient`
  columns of 100 (sex: 10) unbounded, and an over-width value fails at
  commit with a generic AE and no dead-letter row. Known debt: they are not
  identifiers, so whether to refuse or truncate them is still undecided.

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
