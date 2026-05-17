# Schema-per-tenant migration runbook

Roadmap row 33 (`v2.0` / Multi-tenancy / "Schema-per-tenant migration path"). This
runbook is the operator's procedure for **moving one specific hospital** from the
default row-level multi-tenancy topology into a dedicated PostgreSQL schema, and
for the rollback when something goes wrong.

> **Status — 2026-05-17.** Foundation pass landed on
> `feat/v2.0-schema-per-tenant`. Row-33 follow-on shipped on
> `feat/v2.0-schema-per-tenant-scripts`: `provision-schema.sh`,
> `copy-rows.sh`, `invalidate-tenant-cache.sh`, and the
> `POST /api/super-admin/tenancy/schema-cache/invalidate/{hospitalId}`
> endpoint behind the existing
> `app.tenancy.schema-isolation.enabled` flag. The per-tenant
> Liquibase bootstrap (the only step still flagged **(future PR)**)
> and the first UAT cutover are the remaining row-33 work before
> the row flips from `started` to `completed`. The feature flag
> stays **off** in every environment until then.

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

### Step 1 — Provision the empty tenant schema

```bash
# Required libpq env: PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD
# (PGUSER is the DDL role; HMS_APP_ROLE defaults to hms_app)

scripts/tenancy/provision-schema.sh BFQ_MIL_001 tenant_bfq_mil_001
# preview-only:
scripts/tenancy/provision-schema.sh --dry-run BFQ_MIL_001 tenant_bfq_mil_001
```

What it does today (row-33 follow-on scope):

1. Validate the schema name against the same
   `^[a-z][a-z0-9_]{0,62}$` regex the application enforces in
   `SchemaTenantConnectionProvider#SAFE_IDENTIFIER`; refuse any of
   the shared globals (`hospital`, `reference`, `platform`, etc.).
2. `CREATE SCHEMA IF NOT EXISTS "<schema>" AUTHORIZATION "<PGUSER>";`
   — idempotent; re-running is a no-op.
3. `GRANT USAGE ON SCHEMA "<schema>" TO "<HMS_APP_ROLE>";` and
   default privileges (`SELECT, INSERT, UPDATE, DELETE` on future
   tables, `USAGE, SELECT` on future sequences) so the runtime app
   role can use the schema once the per-tenant DDL lands.
4. Stamp a `COMMENT ON SCHEMA` so the schema's provenance is
   self-documenting in `psql \dn+`.

What it does **not** do yet:

- **Per-tenant Liquibase bootstrap.** Creating the clinical /
  billing / lab tables inside the new schema is deliberately deferred
  to a follow-on PR — splitting the existing `V1__Initial_Schema.sql`
  into a tenant-scoped Liquibase context is a multi-week migration in
  its own right. Until that lands, the operator applies the captured
  tenant-tables DDL by hand:

  ```bash
  psql -d "${PGDATABASE}" \
       -c "SET search_path TO tenant_bfq_mil_001, public;" \
       -f path/to/tenant-tables-bootstrap.sql
  ```

### Step 2 — Drain in-flight requests

The hospital goes into maintenance **before** the copy so concurrent
INSERTs into `clinical/billing/lab` cannot drift the source-row
counts during the copy window. Earlier drafts of this runbook had
drain coming after the copy; that order was flagged on PR #356
Copilot review (High) because the post-commit `src=dst` verification
sees a fresh snapshot and can false-fail on any in-flight write.

```sql
UPDATE hospital.hospitals
   SET lifecycle_state = 'SUSPENDED',
       suspended_at    = now(),
       suspension_reason = 'schema-per-tenant migration'
 WHERE id = '<HOSPITAL_UUID>';
```

`JwtAuthenticationFilter` blocks new logins for SUSPENDED hospitals
(see `docs/super-admin-gaps.md`); already-active sessions drain over
the session-idle timeout (15 min, set by row 7 of v1.0). Wait for the
request-rate dashboard for this hospital to flatline before
proceeding. Typical: 30 min.

`copy-rows.sh` (Step 3) refuses to run unless the hospital row is in
`lifecycle_state = 'SUSPENDED'` — this guard is the machine-enforced
version of the drain-before-copy ordering.

### Step 3 — Copy the hospital's clinical rows

```bash
scripts/tenancy/copy-rows.sh <HOSPITAL_UUID> tenant_bfq_mil_001
# preview-only (prints the discovered table plan + source row counts):
scripts/tenancy/copy-rows.sh --dry-run <HOSPITAL_UUID> tenant_bfq_mil_001
```

The script:

1. Validates both args (UUID shape + schema-name regex).
2. Confirms the hospital row exists, is `SUSPENDED` (see Step 2),
   and the target schema is present (refuses to run if either
   `provision-schema.sh` was skipped or the drain step was skipped).
3. Discovers every base table in `clinical`, `billing`, `lab` that
   has a `hospital_id` column — the list is regenerated each run, so
   new tenant-scoped tables added in future migrations are picked up
   automatically.
4. Opens a single `BEGIN ISOLATION LEVEL REPEATABLE READ` transaction,
   takes `SELECT FOR UPDATE` on the hospital row to serialize against
   another concurrent cutover, runs one
   `INSERT INTO tenant.X SELECT ... FROM clinical.X WHERE hospital_id = ...`
   per discovered table, and **captures the per-table source count
   inside the same snapshot** via a CTE that exposes
   `(src_count, rows_copied, status)`. Mismatch RAISEs an EXCEPTION
   which aborts the transaction before `COMMIT`, so a partial copy
   never reaches the tenant schema.
5. Parses the per-table verification rows from the psql output; any
   `MISMATCH` exits 1 so the runbook's "abort, drop the schema,
   restart" path triggers immediately.

Source rows are **not** deleted by this script — Step 6 (post-soak)
runs the explicit `DELETE FROM clinical.X WHERE hospital_id = ...`
sweep so rollback during the soak window stays trivial.

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

Then **invalidate the application cache** so the next request
resolves to the new schema:

```bash
# Required env: HMS_BACKEND_BASE_URL, HMS_ADMIN_TOKEN
scripts/tenancy/invalidate-tenant-cache.sh <HOSPITAL_UUID>
```

The wrapper calls
`POST /api/super-admin/tenancy/schema-cache/invalidate/{hospitalId}`,
which drops the one entry from `TenantSchemaLookup`'s 5-minute cache
and emits a `TENANT_SCHEMA_CACHE_INVALIDATED` audit row attributed
to the calling super-admin. The endpoint is `@PreAuthorize`-gated on
the `SUPER_ADMIN` role and is itself flag-gated on
`app.tenancy.schema-isolation.enabled` — it returns 404 when the
flag is off, so a rolling pod restart remains the operator's only
option until the flag flips on in that env.

`TenantSchemaLookup#invalidateAll()` stays package-private (test
only) — operators target one hospital at a time so a typo on the
cutover host cannot nuke every cached resolver entry. Recovering
from a wider problem ("we think the entire resolver cache is wrong")
is a rolling restart, by design.

If you must skip the endpoint (e.g. backend is on an older build
that pre-dates this PR), the original "wait 5 min" path still works:
the cache TTL guarantees propagation within `TenantSchemaLookup#DEFAULT_TTL`.

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
| `scripts/tenancy/provision-schema.sh` (Step 1 automation) | ✅ row-33 follow-on | Schema + grants only; per-tenant Liquibase still operator-applied |
| `scripts/tenancy/copy-rows.sh` (Step 2 automation) | ✅ row-33 follow-on | REPEATABLE READ tx, auto table discovery, src=dst count verify |
| `scripts/tenancy/invalidate-tenant-cache.sh` (Step 4 wrapper) | ✅ row-33 follow-on | Thin curl wrapper around the super-admin endpoint |
| Cache-invalidation REST endpoint (Step 4) | ✅ row-33 follow-on | `POST /super-admin/tenancy/schema-cache/invalidate/{id}`, SUPER_ADMIN, audited |
| Per-tenant Liquibase changelog discipline | ⏳ next follow-on | How V98+ migrations apply to schema tenants |
| Backup / restore drill against an isolated tenant | ⏳ before first prod use | Pairs with `disaster-recovery.md` |
| First end-to-end UAT cutover | ⏳ before row 33 flips to `completed` | Pilot tenant, soak, decision to keep or roll back |

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
