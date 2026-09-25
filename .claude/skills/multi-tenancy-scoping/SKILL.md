---
name: multi-tenancy-scoping
description: Use when adding repositories, queries, or services that read or write tenant-scoped data, when working with HospitalContext / TenantAwareJpaRepository, or when extending the schema-per-tenant path. Triggers on changes touching com.example.hms.security.context, security.tenant, or repository methods filtering by hospital_id.
---

# Multi-tenancy scoping

HMS is multi-tenant. **Every clinical write or read MUST be hospital-scoped.**
The default mode is row-level (filter by `hospital_id`); the v2.0 path adds
schema-per-tenant for high-isolation deployments.

## The two pillars

### `HospitalContext` (thread-local)

- Set by `KeycloakHospitalContextFilter` on every authenticated request
  from the JWT's `hospital_id` claim (resolved via
  `KeycloakHospitalContextResolver`).
- Read inside services + repositories via `HospitalContextHolder.getCurrentHospitalId()`.
- **Worker threads (MLLP, schedulers, Kafka consumers) have NO context** —
  they must resolve the hospital from the message envelope and pass it
  explicitly.

### `TenantAwareJpaRepository`

The base repository interface that auto-applies a `hospital_id` filter
on every find query. Use it for any entity that has a `hospital_id`
column — never extend `JpaRepository` directly for clinical entities.

When the calling code legitimately needs cross-tenant access (super-admin
flows, EMPI merge, audit aggregation, MLLP worker), use the explicit
`*Unscoped` variant — e.g. `PatientRepository.findByIdUnscoped(id)`.
Document why at the call site.

## The cross-tenant gate

Resolving a `Patient` from an external system (HL7, FHIR, EMPI) is not
the same as authorizing access to it. After resolution, verify that
the requesting context's hospital can act on this patient:

```java
boolean registered = registrationRepository
    .findByPatientIdAndHospitalId(patient.getId(), hospitalId)
    .isPresent();
if (!registered) {
    // Refuse — with the SAME answer the caller gets for something that
    // does not exist anywhere. Never a distinguishable "not yours".
}
```

**A cross-tenant refusal must be indistinguishable from "no such thing".**
This is the same contract as *The 404 is intentional* further down, and it
applies to every transport, not just HTTP reads: if a caller can tell
"exists, but not yours" from "does not exist", it can walk an identifier
space and enumerate what other tenants hold, and the gate stops being a
boundary and becomes a read primitive. On HTTP that means the same status
and the same body as a miss — 404, not 403 beside a 404. On HL7 v2 it
means the same ACK code *and the same ACK text*: the MLLP paths return
`REJECTED_NOT_FOUND` and there is deliberately no cross-tenant outcome to
return. The lesson from how it happened is sharper than "don't add one":
the `REJECTED_CROSS_TENANT` constant was added **once** and then carried
along into each new handler that needed a cross-tenant branch, because it
was there to be reached for. #715 stopped the ORU^R01 path returning it;
the constant itself was deleted only in #738. A constant whose only job is
to produce a distinguishable answer does not get re-added — it gets
reused. (The `hl7-mllp-integration` skill has the MLLP-specific rules.)

**"Same body" means the same body**: identical status, identical message
text, identical `OperationOutcome` diagnostics. No particular wording is
the leak — the *difference* is. A single message used for both branches
("not found at the active hospital scope", say) is fine; two 404s whose
messages differ between the unknown and the foreign case are still an
oracle.

**No tenant pinned is a different case.** The rule above is about
*exists, but not yours*, on reads and writes alike. A caller with **no tenant
pinned at all** — a super-admin in global view, with no `X-Hospital-Id` —
names nothing that could be confirmed, and the rule for it is:

> **Resolve the pin with `RoleValidator.requireActiveHospitalId()`, and
> refuse a null result before any lookup.**

Two parts, both load-bearing:

- **Resolve it through `RoleValidator`, not the raw context.**
  `HospitalContextHolder.getActiveHospitalId()` is *not* null for a
  super-admin in global view: the resolver fills it from the JWT's
  `hospital_id` claim for every caller. Only
  `RoleValidator.requireActiveHospitalId()` drops that primary for a
  super-admin without a header override (see the PR #341 finding below).
  A null check on the raw context silently acts on the super-admin's home
  hospital instead of refusing.
- **Refuse before anything is looked up.** That ordering is the security
  property. Decided before a lookup, the answer is identical for every
  identifier the caller could name, so it confirms nothing.

The **status** for a null pin is therefore not security-relevant, and this
codebase uses the convention of the surface: REST controllers throw
`BusinessException("Hospital context is required ...")`, which
`GlobalExceptionHandler` maps to **400** (`EncounterController`,
`PatientController`, `InBasketController`, `NurseTaskController`,
`MedicationCatalogController`, and others); FHIR write services throw a
**403** `ForbiddenOperationException` with an `OperationOutcome`, and FHIR
`$export` kickoff a **400**. For new code, match the surface you are on.
What must never vary is the ordering — and **a mismatch between a pinned
tenant and the stored one is never a 400 or a 403**, on a read or a write:
it is the same 404, with the same body, as a miss.

The row-32 KPI aggregate dashboards are the one sanctioned exception to
refusing a null pin: a super-admin in global view gets an empty rollup by
design, because an aggregate names no identifier and "nothing" is a
truthful answer to it.

**Known violation — do not copy:** `LabResultServiceImpl`'s
`requireResultInActiveHospital` reads a null resolved pin as "super-admin,
unscoped" and *allows* the request (`if (activeHospitalId != null && ...)`),
on get, update, delete and read-back of `/lab-results/{id}`. That is the
"null pin as unscoped, allow" defect described under *Cross-tenant guard
must DENY on null* below, not an example of a 404 done right.

The simplest way to get this right is to make the lookup itself tenant-scoped,
so a foreign identifier and an unknown one are the same row-not-found:
`EncounterFhirWriteService` does exactly this with
`findByIdAndHospital_Id(id, hospitalId)`, and both cases get one 404 with one
message. Copy the **lookup**, not the whole method: after it, the service
keeps a defensive `throw forbidden(...)` for a hospital mismatch on the
loaded row. That branch is unreachable — the scoped query cannot return
another tenant's row — and must not be generalised into a mismatch-means-403
pattern anywhere a lookup is not already scoped.
**`ObservationFhirWriteService` is a known violation — do not copy it**: it
loads the `LabResult` unscoped, answers 404 when the id is unknown and 403
("does not belong to the active hospital scope") when it belongs to another
tenant, so a pinned caller can probe which lab result ids exist elsewhere.

Two traps, both of which were real defects here:

- **An accept leaks as readily as a reject.** A no-op or already-done
  branch that answers success *before* the gate runs tells the caller the
  entity exists. Gate first, then decide what the request means.
- **Partial ownership is not partial permission.** An operation naming two
  entities where the caller owns one must answer exactly as if it owned
  neither — otherwise the caller pairs something it legitimately owns with
  any candidate identifier and reads the answer off.

Identical answers are identical in **content, not in time**. An identifier
that exists nowhere usually fails fast at the first lookup, while one that
exists in another tenant goes on to the ownership check, so the work differs
even when the response does not. Evaluating both ownership checks before
branching (rather than letting `||` short-circuit) closes the smaller part of
that gap; it does not make the path constant-time, and the residual should be
written down on any new surface rather than assumed away. The worked
example is the MLLP A40 merge path: the comment above the registration
checks in `MllpInboundMergeServiceImpl` and the javadoc on
`MllpInboundOutcome` both state which part of the gap is closed and which
part cannot be.

`PatientHospitalRegistration` is the authoritative table. A patient can
be registered at multiple hospitals over time; never assume a single
home tenant.

## Audit cross-tenant attempts

`CrossTenantReadAudit` (`security/audit/CrossTenantReadAudit.java`) is
**not** a rejection audit, whatever its name suggests. It records one thing:
a *successful* super-admin read that spans tenants — it returns early unless
the caller is a super-admin, and writes `DATA_ACCESS` with status `SUCCESS`.
Calling it on a refusal records nothing for an ordinary user and records a
successful cross-tenant read for a super-admin, which is worse than nothing.
An earlier version of this section said every cross-tenant rejection MUST
emit it; that was never true of the class, and following it would have
produced either silence or a false audit row.

**No rejection path in this codebase emits `CrossTenantReadAudit`**, and
none should. To audit a refusal, use the general audit API:
`AuditEventLogService.logEvent` with `AuditStatus.FAILURE` — which is what
`ReceptionServiceImpl` does for a denied encounter status update, and what
`PartnerExchangeService.auditUnmatched` does with
`SECURITY_ALERT_TRIGGERED`. What does not exist is a *cross-tenant-specific*
refusal event or any surface that records cross-tenant refusals
consistently. Today they are visible only where a surface happens to
record them:

- The MLLP inbound paths (ORU^R01, ADT, A40) have no principal on the worker
  thread and record the refusal on an `integration_message_event` row. They do
  not record it identically: the ADT and A40 refusals carry no message body
  and a stable correlation id per (sender, reason), while the ORU^R01
  refusal still stores the full raw message with a random id per row — so
  a retrying sender adds a dead letter, and a copy of the message, per
  attempt, which is exactly what the `hl7-mllp-integration` skill forbids.
  ORU^R01 is the outstanding path; bringing it in line belongs with the
  redesign that bounds sender-controlled fields where they are parsed, not
  with this document.
- HTTP surfaces that follow the contract refuse with the same 404 as a miss
  and write nothing specific to the refusal. Not every HTTP surface follows
  it yet — `ObservationFhirWriteService` and `LabResultServiceImpl` are the
  known violations named above.

If you need refusals to be detectable — for enumeration, or for a
misconfigured integration — that is a new event type to design, not a call
to `CrossTenantReadAudit`.

## Schema-per-tenant (v2.0 path, off by default)

`Hospital.isolationMode` enum: `ROW_LEVEL` (default) or `SCHEMA`. When
`SCHEMA`, the per-tenant Postgres schema name lives in
`Hospital.tenantSchemaName` and Hibernate routes queries via
`SchemaTenantIdentifierResolver` + `SchemaTenantConnectionProvider`
(strict identifier allow-list, `SET search_path` per tenant). The whole
path is gated by `app.tenancy.schema-isolation.enabled` (default
`false`).

**Never set `tenantSchemaName` directly on a `Hospital` row** without
also provisioning the schema via the operational runbook — there's a
DB-level CHECK constraint binding the two columns.

## Read-replica + tenant interaction

When the read replica is enabled (`app.datasource.replica.enabled=true`),
`@Transactional(readOnly = true)` methods route to the replica. Row-level
filters still apply — replication carries them across. Schema-per-tenant
DOES NOT yet propagate the schema-resolver context to the replica routing
layer. If you wire both flags on at the same time, audit the call sites.

## Query-level scoping

For raw `@Query` JPQL/native queries, you have two options:

1. **Filter inline** — `WHERE e.hospital.id = :hospitalId` with
   `:hospitalId` from `HospitalContextHolder`.
2. **Use a JpaSpecificationExecutor + tenant Specification** — see
   `security/tenant/specification/` for examples.

Never write a query that returns clinical rows across hospitals without
an explicit unscoped-justification comment and an audit emission.

## Aggregate / dashboard queries

For aggregate rollups that never expose patient-level rows (e.g. the
row-32 KPI dashboard — `KpiDashboardServiceImpl`), the contract is:

1. Resolve the active hospital via
   `RoleValidator.requireActiveHospitalId()` (or its empty-rollup
   variant), **not** raw
   `HospitalContextHolder.getContextOrEmpty().getActiveHospitalId()`.
   For a real super-admin in global view, the raw context can still
   carry a JWT-derived primary hospital — only `RoleValidator`
   explicitly drops that value when no `X-Hospital-Id` override was
   sent. The row-32 foundation pass read the raw context and Copilot
   flagged it (PR #341 Medium severity): an unpinned super-admin
   would receive one hospital's KPIs instead of the documented
   empty rollup. Fix this before flipping row 32 to `completed`.
2. **Don't** thread `hospitalId` through the controller signature —
   the dashboard is an implicit-context endpoint by design. Clients
   set the hospital via `X-Hospital-Id` (or via the JWT for normal
   users), not via a query param.
3. When the resolved hospital id is `null` (super-admin without an
   explicit hospital pin), return an empty rollup with sample-sizes
   at zero. Don't compute cross-tenant aggregates by default — that
   would silently expand the data surface.
4. The aggregate output (counts, averages, ratios) is exempt from
   the `PATIENT_ACCESS` audit emission requirement because no
   patient-level row escapes the service. Standard request logging
   applies.

## Cross-tenant pitfalls (2026-05-16 evening batch — PRs #349 / #351 / #352)

Three subtle tenant-leak patterns that slipped through earlier
review cycles. Each was caught on a foundation-pass branch and is
now load-bearing reading before any new clinical write or read
service lands.

### `PatientRepository.findById(uuid)` is NOT tenant-aware

The Javadoc on `PatientFhirResourceProvider` historically said
"Read is tenant-scoped through `PatientRepository.findById(Object)`
which already applies hospital-context filters via the
`tenantContext` bean". That is **wrong**: `PatientRepository`
extends plain `JpaRepository`, and `findById` returns any patient
by primary key regardless of `HospitalContextHolder.getActiveHospitalId()`.

A read path that calls `patientRepository.findById(...)` and then
immediately renders the Patient (Bundle entry, mapper, FHIR
resource) **leaks PHI** (name, DOB, address, phone, email) across
tenants whenever the caller holds a Patient UUID for a tenant
they're not authorised in.

**Correct pattern:**

```java
// ONE message for both cases. Two different 404 texts — "not found" vs
// "not found at the active hospital scope" — are still an oracle: the
// status matches and the body tells them apart.
String notFoundMessage = "Patient/" + patientId + " not found";
Patient patient = patientRepository.findById(patientId)
    .orElseThrow(() -> notFound(notFoundMessage));
boolean registered = registrationRepository
    .findByPatientIdAndHospitalId(patient.getId(), hospitalId)
    .isPresent();
if (!registered) {
    throw notFound(notFoundMessage);
}
// only NOW safe to render
return patientMapper.toFhir(patient);
```

The 404 is intentional — cross-tenant rejection collapses to "no
such patient" so the existence of patients belonging to other
tenants stays invisible. That only holds if the *body* is the same
too: an earlier version of this snippet used a different message for
each branch and would have leaked through the diagnostics.

Caught on `PatientEverythingService.everythingForPatient` in PR
#351 (FHIR `$everything`). The same pattern applies to any other
new read path that takes a patient UUID from the URL.

### Cross-tenant guard must DENY on null/empty active hospital

A super-admin without an explicit `X-Hospital-Id` header has
`HospitalContextHolder.getActiveHospitalId() == null`. A guard
written as "reject only when BOTH the stored hospitalId and the
current context's hospitalId are non-null and unequal" lets any
super-admin call see any tenant's data — the inverse of the
intended invisible-cross-tenant-rejection contract.

**Correct pattern:**

```java
// RoleValidator, NOT HospitalContextHolder: the raw context holds the JWT's
// primary hospital even for a super-admin in global view, so a null check on
// it never fires for the one caller this guard exists for.
UUID activeHospitalId = roleValidator.requireActiveHospitalId();
if (activeHospitalId == null) {
    // No tenant pin -> refuse, BEFORE any lookup, so the answer is the
    // same whatever id the caller named. Status by surface convention:
    // BusinessException (400) on REST, ForbiddenOperationException (403)
    // on a FHIR write. See "No tenant pinned is a different case" above.
    throw new BusinessException("Hospital context is required; supply X-Hospital-Id.");
}
// Scope the lookup itself, so a foreign id and an unknown id are the
// same row-not-found and get the same 404 with the same body.
Stored stored = repository.findByIdAndHospital_Id(id, activeHospitalId)
    .orElseThrow(() -> notFound(id + " not found"));
```

This supersedes an earlier version of the snippet that returned
`Optional.empty()` on a null pin "or throw 403 if write context", and a
later one that checked the raw `HospitalContextHolder` — which never sees a
null pin for a super-admin, so it refused nobody.

The original defect was caught on `FhirBulkExportService.getJob` in
PR #351: a guard that treated a null pin as "unscoped, allow". The FHIR
write services from PR #350 (`EncounterFhirWriteService`,
`ObservationFhirWriteService`) follow the rule above for the **null**
case — a 403 up front, before any lookup. For a *mismatch* only
`EncounterFhirWriteService` does, by scoping its lookup;
`ObservationFhirWriteService` is the known violation named above.

The exception: read-only aggregate dashboards (row 32 KPI) where
the documented behaviour is "super-admin without X-Hospital-Id
returns an empty rollup". Those flow through
`RoleValidator.requireActiveHospitalId()` which deliberately
returns `null` for the empty-rollup case.

### Aggregate queries must group by a stable key, not display name

Hospital names are not unique in the schema (only
`Hospital.code` carries a `unique = true` constraint). Two
hospitals can share a name; a hospital rename also splits the
same tenant's historic data across the old and new
`hospitalName` snapshots on
`audit.audit_event_logs.hospital_name`.

**Wrong:**

```java
@Query("SELECT a.hospitalName, COUNT(a) FROM AuditEventLog a "
     + "GROUP BY a.hospitalName")
```

**Right:**

```java
@Query("SELECT a.assignment.hospital.id, a.assignment.hospital.name, COUNT(a) "
     + "FROM AuditEventLog a "
     + "WHERE a.assignment.hospital.id IS NOT NULL "
     + "GROUP BY a.assignment.hospital.id, a.assignment.hospital.name "
     + "ORDER BY a.assignment.hospital.name ASC")
```

Project the display name alongside for the UI, but key by id.
Caught on `AuditEventLogRepository.countByHospitalBetween` in PR
#352 (per-tenant cost obs).

## Aggregate queries on Patient-compartment resources must use hospital-scoped repository methods

**Caught:** Multi-row review on `PatientEverythingService` —
the Condition section called `patientProblemRepository.findByPatient_Id(patientId)`
without a hospital filter. `PatientProblem` is hospital-scoped
(non-null `hospital_id` FK), and the repository already provides
`findByPatient_IdAndHospital_Id(...)`. The unscoped call leaked
problems recorded at OTHER hospitals for the same patient into a
hospital-scoped `$everything` response — same patient registered at
two facilities, the response carries problems from both, including
the one the caller has no right to see.

**Pattern to follow:** for any aggregate that pulls clinical data
for a Patient by `patient_id` alone, audit the repository call against:

1. Is the entity hospital-scoped (`@TenantScoped` / non-null
   `hospital_id` FK)? If yes, the call MUST be the
   `findByPatient_IdAndHospital_Id(...)` variant.
2. Does the entity have a parent that is hospital-scoped (e.g.
   `LabResult` → `LabOrder.hospital_id`)? Then the call must scope
   through the parent (`findByLabOrder_Patient_IdAndLabOrder_Hospital_Id`).
3. If neither, the entity is global/reference data and an unscoped
   query is fine.

The `findByPatient_Id` shape (no hospital filter) should only exist
on repositories whose entity has no hospital column. When you're
tempted to use it on a tenant-scoped entity, add the hospital-scoped
variant to the repository and use that instead — don't rely on the
caller to "filter in memory after the fetch."

## Reference files

- `hospital-core/src/main/java/com/example/hms/security/context/HospitalContext.java`
- `hospital-core/src/main/java/com/example/hms/security/context/HospitalContextHolder.java`
- `hospital-core/src/main/java/com/example/hms/security/context/HospitalContextRequestOverrides.java`
- `hospital-core/src/main/java/com/example/hms/security/oidc/KeycloakHospitalContextFilter.java`
- `hospital-core/src/main/java/com/example/hms/security/oidc/KeycloakHospitalContextResolver.java`
- `hospital-core/src/main/java/com/example/hms/security/audit/CrossTenantReadAudit.java`
- `hospital-core/src/main/java/com/example/hms/repository/PatientHospitalRegistrationRepository.java`
- `hospital-core/src/main/java/com/example/hms/repository/PatientRepository.java` — example of `findByIdUnscoped`
- `docs/runbooks/schema-per-tenant-migration.md` — schema-isolation operational procedure
