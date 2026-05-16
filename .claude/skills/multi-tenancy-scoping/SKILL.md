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

1. Read `HospitalContextHolder.getContextOrEmpty().getActiveHospitalId()`
   inside the service. **Don't** thread `hospitalId` through the
   controller signature — the dashboard is an implicit-context
   endpoint by design.
2. When `activeHospitalId` is `null` (super-admin without an explicit
   hospital pin), return an empty rollup with sample-sizes at zero.
   Don't compute cross-tenant aggregates by default — that would
   silently expand the data surface.
3. The aggregate output (counts, averages, ratios) is exempt from the
   `PATIENT_ACCESS` audit emission requirement because no patient-
   level row escapes the service. Standard request logging applies.

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
