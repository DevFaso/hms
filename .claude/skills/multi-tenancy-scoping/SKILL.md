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
    // REJECT_CROSS_TENANT — emit AR, do not proceed
}
```

`PatientHospitalRegistration` is the authoritative table. A patient can
be registered at multiple hospitals over time; never assume a single
home tenant.

## Audit cross-tenant attempts

Every cross-tenant rejection MUST emit an audit event via
`CrossTenantReadAudit`. This is the primary surface for detecting
misconfigured senders + permission-creep bugs. Live in
`security/audit/CrossTenantReadAudit.java`.

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
Patient patient = patientRepository.findById(patientId)
    .orElseThrow(() -> notFound("Patient/" + patientId + " not found"));
boolean registered = registrationRepository
    .findByPatientIdAndHospitalId(patient.getId(), hospitalId)
    .isPresent();
if (!registered) {
    throw notFound("Patient/" + patientId + " not found at the active hospital scope.");
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

A super-admin without an explicit `X-Hospital-Id` header has
`HospitalContextHolder.getActiveHospitalId() == null`. A guard
written as "reject only when BOTH the stored hospitalId and the
current context's hospitalId are non-null and unequal" lets any
super-admin call see any tenant's data — the inverse of the
intended invisible-cross-tenant-rejection contract.

**Correct pattern:**

```java
UUID activeHospitalId = HospitalContextHolder.getContextOrEmpty()
    .getActiveHospitalId();
if (activeHospitalId == null) {
    // No tenant pin → deny. Super-admin must set X-Hospital-Id
    // explicitly per the row-32 KPI pattern.
    return Optional.empty();  // or throw 403 if write context
}
if (!stored.getHospitalId().equals(activeHospitalId)) {
    return Optional.empty();
}
```

Caught on `FhirBulkExportService.getJob` in PR #351. The same
write-side pattern (throwing 403 instead of 404) lands on
`EncounterFhirWriteService` and `ObservationFhirWriteService` from
PR #350 (those rejected on null up-front via
`HospitalContextHolder.getContextOrEmpty().getActiveHospitalId() == null`
→ `ForbiddenOperationException`).

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
