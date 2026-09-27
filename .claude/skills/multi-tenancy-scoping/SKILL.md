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

- Set by BOTH auth filters (`JwtAuthenticationFilter`,
  `KeycloakHospitalContextFilter`) from ONE live computation,
  `ActingScopeResolver.liveContext`: the caller's active assignments read
  from the table on every request. No token claim (`hospital_id`,
  `role_assignments`, `primaryHospitalId`, `isSuperAdmin`) is an
  authorization input. On Keycloak the local account comes only from the
  `appUserId` claim (`scripts/keycloak-migration` backfills it).
- Read through `ActingScopeResolver` (below), never the raw fields.
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
    // EXACTLY the answer a caller gets for something that does not exist
    // anywhere: same exception, same status, same text. Never a
    // distinguishable "not yours" - and never fall through.
    throw notFound("Patient/" + patient.getId() + " not found");
}
```

**A cross-tenant refusal must be indistinguishable from "no such thing."**
If a caller can tell *exists, but not yours* from *does not exist*, it can
walk an identifier space and enumerate what other tenants hold, and the gate
becomes a read primitive instead of a boundary. That holds on every
transport: on HTTP a mismatch is the same 404 as a miss, never a 403 beside
a 404; on HL7 v2 it is the same ACK code and the same ACK text.

- **Same body means same body** — same status, same message text, same
  `OperationOutcome` diagnostics. No particular wording is the leak; a
  *difference* between the two branches is.
- **No distinguishing constant.** The MLLP paths once returned a dedicated
  `REJECTED_CROSS_TENANT` outcome that mapped to AR. It was added once and
  then carried into each new handler that needed a cross-tenant branch,
  because it was there to be reached for; #715 stopped the ORU^R01 path
  using it and #738 deleted it. A constant whose only job is a
  distinguishable answer does not get re-added — it gets reused. (The
  `hl7-mllp-integration` skill has the MLLP rules.)
- **An accept leaks as readily as a reject.** A no-op or already-done branch
  that answers success before the gate runs tells the caller the entity
  exists. Gate first, then decide what the request means.
- **Partial ownership is not partial permission.** An operation naming two
  entities where the caller owns one must answer exactly as if it owned
  neither — otherwise the caller pairs something it legitimately owns with
  a candidate identifier and reads the answer off. Evaluate both ownership
  checks before branching rather than letting `||` short-circuit. That
  closes part of the timing gap, not all of it: an identifier that exists
  nowhere usually fails at the first lookup while a foreign one reaches the
  ownership check, so the two differ in work even when they match in
  content. Write that residual down on a new surface rather than assume it
  away.
- **Check ownership the way the entity defines it.** For a patient that is
  `PatientHospitalRegistration`; for a lab result it is ordering **or**
  performing hospital (`LabOrder.isHandledBy`), so an equality check on one
  hospital column would refuse a performing lab the results it owns.

**Known violation — do not copy:** `ObservationFhirWriteService` answers 404
for an unknown lab result and 403 ("does not belong to the active hospital
scope") for another tenant's, which is exactly the oracle above. It is
tracked as a defect to fix.

### Resolving the tenant

One resolver, `ActingScopeResolver` (docs/security/tenant-resolution.md). Its
answer is an `ActingScope`, never a meaningful `null`:

- **`Pinned(hospital)`** — the hospital the caller named (`X-Hospital-Id`,
  or a controller's `?hospitalId=` / path variable via `narrowTo`), else
  the only one they hold.
- **`Global`** — only a VERIFIED super-admin (a live active SUPER_ADMIN
  assignment) who named no hospital. Read-only: writes call
  `requirePinned()`.
- **`PatientOwned`** — a patient-only caller (ROLE_PATIENT and nothing
  else) who named no hospital, however many hospitals registered them.
  Bounded by ownership, not by a hospital (design Q1): every adapter that
  needs "the" hospital refuses it (403 `PATIENT_OWNED`), never `null`
  (null means an unscoped super-admin). A patient-reached read uses an
  explicit owner variant filtered by the caller's own patient id
  (`...ForPortalPatient`, `PatientChartAccess` admits the chart's owner);
  a patient write takes its hospital from the record it acts on, or from
  the body's hospital checked against the patient's registrations.
- **`Refused(reason)`** — `AMBIGUOUS` (several hospitals, none named;
  never "the newest"), `NO_HOSPITAL`, `NO_LOCAL_USER`, or, for a hospital
  named explicitly, `NOT_PERMITTED` / `NO_LONGER_PERMITTED` (403 with the
  reason; a refused header is answered by the filter before any
  controller).

Rules:

- **Services** call `RoleValidator.requireActiveHospitalId()` (the adapter:
  pinned → id, global → `null`, refused → `HOSPITAL_CONTEXT_REQUIRED`) or
  `ActingScopeResolver.requirePinned()` when global view must not reach
  them. Code that cannot take the bean uses
  `ActingScopeResolver.pinnedHospitalIdOrNull()`.
- **Controllers** call `ControllerAuthUtils.resolveHospitalScope`; a
  handler parameter named `hospitalId` is narrowed before the handler runs
  by `HospitalIdNarrowingInterceptor`, unless the handler carries
  `@HospitalScopeExempt(reason = ...)`.
- **The scope is read once.** The first consumer seals it; a `narrowTo` to
  a different hospital afterwards is an `IllegalStateException`.
- **Refuse before any lookup when no tenant resolves**, and **a
  cross-tenant mismatch answers exactly like a miss** (the gate above).
- `ActingScopeCoverageTest` fails a new raw `getActiveHospitalId()` read,
  a local `ctx.isSuperAdmin()` test or a "newest assignment" pick outside
  its frozen list; `HospitalIdParameterCoverageTest` freezes the exempt
  handlers. Tests set the context with `ActingScopeTestSupport`.

`PatientHospitalRegistration` is the authoritative table. A patient can
be registered at multiple hospitals over time; never assume a single
home tenant.

## Audit cross-tenant attempts

`CrossTenantReadAudit` (`security/audit/CrossTenantReadAudit.java`) writes
two kinds of row:

- **Global-view reads** — one `DATA_ACCESS` / `SUCCESS` row per request a
  verified super-admin served in global view (`GlobalViewAuditInterceptor`,
  from the final scope, ids only), or the view's own labelled row from
  `recordCrossTenantRead`. A super-admin pinned to a hospital writes none.
- **Refusals of an explicitly named hospital** — `recordRefusal`, called by
  the resolver for a refused header or `narrowTo`: `DATA_ACCESS` /
  `REJECTED`, reason `NOT_PERMITTED` (a hospital never held: the probe) or
  `NO_LONGER_PERMITTED` (a stale chip), deduplicated per actor, hospital and
  reason per hour. A service's own cross-tenant 404 (the gate above) is not
  a named-hospital refusal and does not call it.

The MLLP inbound paths have no principal on the worker thread and record a
refusal on an `integration_message_event` row instead. The ADT and A40
refusals carry no message body and a correlation id keyed on the sender,
a **fixed** per-path type (`"ADT"`, `"ADT^A40"`) and the reason — never
MSH-9 or the trigger event, because nothing a sender controls may key a
correlation id; the ORU^R01 refusal still stores the full raw
message with a random id per row, which the `hl7-mllp-integration` skill
forbids. That path is outstanding.

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

1. Resolve the hospital via `ActingScopeResolver.pinnedHospitalIdOrNull()`
   (or `RoleValidator.requireActiveHospitalId()`), never the raw
   `getActiveHospitalId()`: the row-32 foundation pass read the raw
   context, and for a super-admin in global view that was an incidental
   assignment (PR #341), which is why the ratchet now forbids it.
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
// ONE message for both branches: two different 404 texts are still an
// oracle - the status matches and the body tells them apart.
Patient patient = patientRepository.findById(patientId)
    .orElseThrow(() -> notFound("Patient/" + patientId + " not found"));
boolean registered = registrationRepository
    .findByPatientIdAndHospitalId(patient.getId(), hospitalId)
    .isPresent();
if (!registered) {
    throw notFound("Patient/" + patientId + " not found");
}
// only NOW safe to render
return patientMapper.toFhir(patient);
```

The 404 is intentional — cross-tenant rejection collapses to "no
such patient" so the existence of patients belonging to other
tenants stays invisible.

Caught on `PatientEverythingService.everythingForPatient` in PR
#351 (FHIR `$everything`). The same pattern applies to any other
new read path that takes a patient UUID from the URL.

### Cross-tenant guard must DENY on null/empty active hospital

A guard written as "reject only when BOTH the stored hospitalId and
the resolved one are non-null and unequal" reads "no tenant" as
"unscoped, allow" and lets any caller whose resolver yields nothing see
any tenant's data — the inverse of the invisible-cross-tenant-rejection
contract. Refuse on "no tenant" first, then compare.

```java
UUID tenant = /* the endpoint's own resolver - see "Resolving the
                 tenant" above; do not hand-roll a raw-context check */;
if (tenant == null) {
    // No tenant -> refuse, BEFORE any lookup.
    throw /* the refusal this surface uses */;
}
// Then the ownership check, answering a mismatch exactly like a miss.
```

Caught on `FhirBulkExportService.getJob` in PR #351. With the one resolver
the rule is `requirePinned()` (or `pinnedHospitalIdOrNull()` and refuse on
`null`) before the lookup.

The exception: read-only aggregate dashboards (row 32 KPI) where
the documented behaviour is "super-admin without X-Hospital-Id
returns an empty rollup". (How they resolve the tenant is in
"Aggregate / dashboard queries" above.)

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
