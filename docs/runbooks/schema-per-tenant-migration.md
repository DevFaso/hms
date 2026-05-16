# Schema-per-tenant migration runbook

Roadmap row 33 (`v2.0` / Multi-tenancy / "Schema-per-tenant migration path"). This
runbook is the operator's procedure for **moving one specific hospital** from the
default row-level multi-tenancy topology into a dedicated PostgreSQL schema, and
for the rollback when something goes wrong.

> **Status — 2026-05-15.** The application-side foundation has landed
> (this PR — `feat/v2.0-schema-per-tenant`). The runbook below describes
> the **target end-to-end flow**; sections marked **(future PR)** are
> not yet automated and will be operator scripts in the next two follow-up
> PRs. Until then, the feature flag `app.tenancy.schema-isolation.enabled`
> stays **off** in every environment and no hospital's `isolation_mode`
> column changes from `ROW_LEVEL`. Reading the whole runbook now is
> still useful: it documents the contract the foundation pass commits to.

## When to use this

A hospital should be moved to schema isolation **only** when one of these is true:

- The customer is contractually required to keep clinical data physically
  separated (typical for military / national-security contracts and some
  foreign-private hospitals in jurisdictions with strict data-residency law).
- The customer has been granted dedicated infrastructure (a separate Railway
  service / replica) and the schema isolation is the application-side mirror
  of that.
- A formal risk assessment has signed off that the operational cost (per-tenant
  Liquibase, per-tenant backup verification, per-tenant restore drills) is
  worth the isolation gain.

For every other hospital — and there will be many more of them — the row-level
default is the right answer. Schema-per-tenant is **not** a security upgrade
relative to the existing tenant-scope specifications; it is a **regulatory
isolation** posture and it doubles the operational surface.

## Architecture recap

```
┌────────────────────────────────────────────────────────────┐
│  hospital-core (Spring Boot 3.4 / Hibernate 6.6)           │
│                                                            │
│  per-request:                                              │
│    JwtAuthenticationFilter / KeycloakHospitalContextFilter │
│      → HospitalContextHolder.set(ctx)                      │
│                                                            │
│  on each Hibernate connection acquisition:                 │
│    SchemaTenantIdentifierResolver                          │
│      → reads ctx.activeHospitalId                          │
│      → TenantSchemaLookup.schemaFor(id)  (cached, JDBC)    │
│      → returns "__default__" or "<schema_name>"            │
│                                                            │
│    SchemaTenantConnectionProvider                          │
│      → checks out connection from existing Hikari pool     │
│      → SET search_path TO  <tenant>, reference, platform,  │
│                            security, support, public       │
│      → hands connection to Hibernate                       │
│      → on release: SET search_path back to default + close │
└────────────────────────────────────────────────────────────┘
                           │
                  ┌────────┴────────┐
                  │   PostgreSQL    │
                  │                 │
                  │  hospital.*     │ ← shared hospital + org metadata
                  │  reference.*    │ ← shared reference data
                  │  platform.*     │ ← shared platform/billing rules
                  │  security.*     │ ← shared users/roles/audit
                  │  support.*      │ ← shared chat/notifications
                  │                 │
                  │  clinical.*     │ ← row-level tenants live here
                  │  billing.*      │
                  │  lab.*          │
                  │                 │
                  │  tenant_alpha.* │ ← schema-isolated tenant
                  │  tenant_beta.*  │
                  └─────────────────┘
```

**Key invariants:**

1. **Reference / platform / security / support stay shared.** A schema-isolated
   hospital still uses the global drug formulary, role catalogue, and audit
   log. Only the *clinical* and *billing* surfaces get a dedicated copy.
2. **The default search_path is unchanged.** Every existing connection (system
   jobs, Liquibase, super-admin without a pinned hospital) still sees the
   same schemas in the same order it did before this PR.
3. **The feature flag is off by default.** When
   `app.tenancy.schema-isolation.enabled=false`, none of the Hibernate
   multi-tenancy beans are even instantiated; the path through Hibernate is
   bit-for-bit identical to the row-level baseline.
4. **`isolation_mode` and `tenant_schema_name` are constraint-locked.**
   `ROW_LEVEL` rows must have a NULL schema; `SCHEMA` rows must have a
   non-NULL schema; partial unique index prevents two hospitals sharing one
   schema. See `V97__hospital_tenant_isolation_mode.sql`.

## Pre-cutover checklist

Before scheduling a migration window for hospital `<H>`:

- [ ] **Backup proven restorable.** Run a fresh PITR drill against
      yesterday's snapshot per `docs/runbooks/disaster-recovery.md` and
      confirm row counts match within ±1%.
- [ ] **Hospital lifecycle is `ACTIVE`.** Suspended / archived hospitals
      are out of scope — un-suspend or skip them.
- [ ] **Tenant schema name chosen.** Convention is `tenant_<short>` where
      `<short>` is the hospital `code` lowercased and stripped of
      non-`[a-z0-9_]` characters. Total length ≤ 63 (PG limit). The
      `Hospital.tenantSchemaName` `@Pattern` enforces this.
- [ ] **Read replica is caught up.** If the hospital is heavy-write, the
      copy step below holds an exclusive lock briefly; replica lag adds to
      the window.
- [ ] **Customer notified.** A schema flip is a maintenance event; affected
      users are kicked out for the cutover.

## Procedure

### Step 1 — Provision the empty tenant schema *(future PR)*

```bash
# scripts/tenancy/provision-schema.sh <hospital-code> <schema-name>
# (script not yet written — placeholder command shown for shape)

scripts/tenancy/provision-schema.sh BFQ_MIL_001 tenant_bfq_mil_001
```

What it does:
1. Connect as the `LIQUIBASE_USERNAME` DDL role (the same role used for
   versioned migrations — the runtime app role does not have CREATE on
   the public DB).
2. `CREATE SCHEMA IF NOT EXISTS tenant_bfq_mil_001 AUTHORIZATION hms_app;`
3. Run a **Liquibase contexts-filtered** changelog that creates only the
   clinical / billing / lab tables in the new schema. The current
   `V1__Initial_Schema.sql` has those tables in the shared `clinical`,
   `billing`, `lab` schemas; the per-tenant variant points the same DDL
   at the new schema via `currentSchema=tenant_bfq_mil_001` in the JDBC
   URL.
4. Grant `USAGE` on the schema and `SELECT, INSERT, UPDATE, DELETE` on
   all tables to `hms_app`. The DDL role keeps ownership.

### Step 2 — Copy the hospital's clinical rows

```sql
-- Run inside a single REPEATABLE READ transaction so a long copy
-- doesn't see writes from concurrent traffic.

BEGIN ISOLATION LEVEL REPEATABLE READ;

-- Lock the hospital row to serialize cutovers
SELECT * FROM hospital.hospitals WHERE id = '<HOSPITAL_UUID>' FOR UPDATE;

-- Each clinical / billing / lab table:
INSERT INTO tenant_bfq_mil_001.encounters
SELECT * FROM clinical.encounters WHERE hospital_id = '<HOSPITAL_UUID>';

-- ... repeat for every clinical table that has hospital_id ...

-- Pause here. Do NOT delete from the source schemas yet.
COMMIT;
```

**Verification**:

```sql
-- For every clinical / billing / lab table, source count must match
-- destination count exactly. Mismatch = abort, drop the schema, restart.
SELECT
    (SELECT COUNT(*) FROM clinical.encounters         WHERE hospital_id = '<H>') AS src,
    (SELECT COUNT(*) FROM tenant_bfq_mil_001.encounters)                          AS dst;
```

### Step 3 — Drain in-flight requests

The hospital briefly goes maintenance:

```sql
UPDATE hospital.hospitals
   SET lifecycle_state = 'SUSPENDED',
       suspended_at    = now(),
       suspension_reason = 'schema-per-tenant migration'
 WHERE id = '<HOSPITAL_UUID>';
```

`JwtAuthenticationFilter` blocks new logins for SUSPENDED hospitals
(see `docs/super-admin-gaps.md`); already-active sessions drain over the
session-idle timeout (15 min, set by row 7 of v1.0).

Wait for the request-rate dashboard for this hospital to flatline before
proceeding. Typical: 30 min.

### Step 4 — Flip the isolation mode

```sql
BEGIN;

-- Atomic flip. The CHECK constraint on hospitals refuses any
-- intermediate state (ROW_LEVEL with non-null schema OR SCHEMA with
-- null schema) so the two columns must move together.
UPDATE hospital.hospitals
   SET isolation_mode     = 'SCHEMA',
       tenant_schema_name = 'tenant_bfq_mil_001',
       lifecycle_state    = 'ACTIVE',
       suspended_at       = NULL,
       suspension_reason  = NULL
 WHERE id = '<HOSPITAL_UUID>';

COMMIT;
```

Then **invalidate the application cache** so the next request resolves
to the new schema:

```bash
# scripts/tenancy/invalidate-tenant-cache.sh <hospital-uuid>
# (future PR — stub. Currently: rolling restart of hospital-core pods.)
```

The current foundation pass has `TenantSchemaLookup` cache the
isolation mode for 5 minutes, so even without an explicit invalidation
endpoint, a 5-minute wait is sufficient for the change to propagate.

### Step 5 — Soak

For **one full business day** the hospital runs against its dedicated
schema while the source rows in `clinical.*` / `billing.*` / `lab.*`
remain in place as a fallback. Run smoke checks:

- [ ] Doctor opens the patient tracker, sees the same patients as
      pre-migration.
- [ ] Pharmacist dispenses a test prescription.
- [ ] Lab uploads a test ORU result — lands in `tenant_bfq_mil_001.lab_result`.
- [ ] Audit log entries for the above are visible (audit lives in
      shared `security.audit_event_log` — confirm it's not partitioned
      by accident).

If anything fails: skip to **Rollback** below.

### Step 6 — Delete the source rows *(only after soak passes clean)*

```sql
BEGIN;
DELETE FROM clinical.encounters WHERE hospital_id = '<HOSPITAL_UUID>';
-- ... same for every copied table ...
COMMIT;
```

Vacuum the shared schemas at the next maintenance window so PG reclaims
the freed space.

## Rollback

If Step 5 surfaces a problem, **flip back** rather than fix-forward:

```sql
BEGIN;
UPDATE hospital.hospitals
   SET isolation_mode     = 'ROW_LEVEL',
       tenant_schema_name = NULL
 WHERE id = '<HOSPITAL_UUID>';
COMMIT;
```

Then invalidate the cache (or wait 5 min). The hospital is immediately
serving from the original `clinical.*` / `billing.*` / `lab.*` rows
again. Investigate the failure offline; retry the migration in a
later window.

The dedicated schema can sit empty for a while — `DROP SCHEMA
tenant_bfq_mil_001 CASCADE;` cleans it up once you're sure rollback is
permanent.

## What this PR ships vs. what's still needed

| Capability | Status | Notes |
|---|---|---|
| `isolation_mode` + `tenant_schema_name` columns on `hospitals` | ✅ this PR | Liquibase V97, CHECK constraints, partial unique index |
| `TenantIsolationMode` enum + Hospital JPA fields | ✅ this PR | Default `ROW_LEVEL` |
| `SchemaTenantIdentifierResolver` | ✅ this PR | Reads `HospitalContext`, falls back to `__default__` |
| `SchemaTenantConnectionProvider` | ✅ this PR | `SET search_path` with strict identifier allow-list |
| `TenantSchemaLookup` (JDBC, 5-min cache, manual invalidate) | ✅ this PR | Bypasses JPA to avoid resolver recursion |
| Hibernate `multiTenancy=SCHEMA` wiring behind feature flag | ✅ this PR | `app.tenancy.schema-isolation.enabled=false` by default |
| Unit tests for resolver + provider + lookup | ✅ this PR | 28 tests across the three classes |
| `scripts/tenancy/provision-schema.sh` (Step 1 automation) | ⏳ next PR | Liquibase contexts split + per-schema bootstrap |
| `scripts/tenancy/copy-rows.sh` (Step 2 automation) | ⏳ next PR | Idempotent + verification SQL bundled |
| Cache-invalidation endpoint (Step 4 automation) | ⏳ next PR | Super-admin REST endpoint, audited |
| Backup / restore drill against an isolated tenant | ⏳ before first prod use | Pairs with `disaster-recovery.md` |
| Per-tenant Liquibase changelog discipline | ⏳ next PR | How V98+ migrations apply to schema tenants |

## Operational notes

- **Connection pool sizing.** The pool is shared across all tenants
  including isolated ones; a `SET search_path` is a free statement.
  No per-tenant pool needed until we cross ~50 isolated tenants.
- **Audit log placement.** `security.audit_event_log` stays shared so
  cross-tenant super-admin views and the cross-tenant audit row
  (`CrossTenantReadAudit`) keep working without a UNION.
- **Cross-tenant queries.** Super-admin reads via the
  `CrossTenantReadAudit` path issue `SELECT ... FROM clinical.encounters`
  — those will **only see row-level tenants**. Any aggregation over
  isolated tenants must explicitly UNION the per-schema tables. This
  is the largest follow-on work item and the reason schema-isolation
  is opt-in: every report that wants to span hospitals must be
  taught about isolated schemas.
- **EMPI v1 (Horizon 3, row 40)** assumes cross-tenant reads. Sequencing:
  schema-per-tenant lands first, EMPI v1 is built knowing isolated
  tenants exist and routes per-tenant lookups through the schema list.

## References

- Roadmap row 33 — `docs/roadmap.md` § 3.1
- Companion roadmap row 34 — Tenant onboarding pipeline
- `docs/runbooks/disaster-recovery.md` — backup / restore baseline
- `hospital-core/src/main/java/com/example/hms/security/tenant/schema/`
  — implementation
- `hospital-core/src/main/resources/db/migration/V97__hospital_tenant_isolation_mode.sql`
  — schema migration
