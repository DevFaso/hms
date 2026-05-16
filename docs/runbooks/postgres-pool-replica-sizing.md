# Postgres connection pool sizing + read-replica activation

Roadmap row 35 — `v2.0 / Performance / Read replicas + Hikari tuning`.

This runbook covers the two halves of the row together because they are
operationally coupled:

1. **Hikari pool tuning** — how the write-side connection pool is sized,
   what each timeout means in production, and how to override per
   deployment.
2. **Read-replica routing** — how to wire a streaming/logical replica
   to HMS without code changes once the foundation has shipped.

---

## 1. Hikari sizing — write primary

### Formula

The classic [Hikari pool-sizing FAQ](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
defines the upper bound:

```
pool_size = ((core_count * 2) + effective_spindle_count)
```

For Railway/RDS managed Postgres on SSD, treat `effective_spindle_count ≈ 1`
unless the storage class explicitly advertises higher concurrency. The
formula is an upper bound — actual production sizing is the lower of:

- The formula result for the database server's core count.
- `db_max_connections / N` where `N` = number of app instances **plus
  Liquibase migration runner + replica + any other consumer**. Reserve
  ≥ 10 % headroom for ad-hoc psql / pgAdmin connections.

### Reference table (defaults shipped in `application.properties`)

| Deployment           | App instances | DB cores | DB max_connections | Write pool size | Min idle |
| -------------------- | ------------- | -------- | ------------------ | --------------- | -------- |
| Local dev (laptop)   | 1             | n/a (H2) | n/a                | 5               | 1        |
| Hosted dev (Railway) | 1             | 2        | 100                | 10              | 2        |
| UAT                  | 1             | 2        | 100                | 15              | 5        |
| Prod (single tenant) | 2             | 4        | 200                | 20              | 5        |
| Prod (multi-tenant)  | 4             | 8        | 400                | 25              | 8        |

The shipped defaults are sized for the **Prod (single tenant)** row —
that is the canonical pilot deployment. Other deployments override via
env vars (see "Per-deployment overrides" below).

### Timeout settings

| Hikari setting              | Default | What it controls                                                                                            |
| --------------------------- | ------- | ----------------------------------------------------------------------------------------------------------- |
| `connection-timeout`        | 30 s    | How long a thread waits to *acquire* a connection from the pool before failing. Mirrors pre-row-35 baseline. |
| `idle-timeout`              | 10 min  | Idle connection eviction. Set BELOW Postgres `idle_in_transaction_session_timeout` so Hikari evicts first.    |
| `max-lifetime`              | 30 min  | Hard cap on connection age — recycles before any cloud TCP keep-alive expires. Set BELOW DB `wal_sender_timeout`. |
| `leak-detection-threshold`  | 0 (off) | When non-zero, logs a stack trace for any connection held longer than N ms. Production should set ≥ 10 s.    |

### Per-deployment overrides

The shipped properties read every value from env vars with a documented
default:

```
HIKARI_PRIMARY_POOL_NAME=hms-primary-pool
HIKARI_PRIMARY_MAX_POOL_SIZE=20
HIKARI_PRIMARY_MIN_IDLE=5
HIKARI_PRIMARY_IDLE_TIMEOUT_MS=600000
HIKARI_PRIMARY_MAX_LIFETIME_MS=1800000
HIKARI_PRIMARY_CONNECTION_TIMEOUT_MS=30000
HIKARI_PRIMARY_LEAK_DETECTION_MS=0
```

Promotion procedure: set the new value in Railway env, redeploy, and
verify via the Grafana Postgres dashboard:

- `hikaricp_connections{pool="hms-primary-pool"}` — total
- `hikaricp_connections_active{pool="hms-primary-pool"}` — in use
- `hikaricp_connections_pending{pool="hms-primary-pool"}` — queued

Pool *pending* > 0 sustained for > 60 s indicates undersized pool **or**
slow queries holding connections — check pg_stat_activity before
bumping the size.

---

## 2. Read-replica routing — foundation pass

### What this release ships

- `ReplicaDataSourceProperties` — `@ConfigurationProperties` for
  `app.datasource.replica.*` (URL, credentials, Hikari tuning).
- `ReadWriteRoutingDataSource` — `AbstractRoutingDataSource` that picks
  WRITE vs READ based on Spring's
  `TransactionSynchronizationManager.isCurrentTransactionReadOnly()`.
- `ReadReplicaDataSourceConfiguration` — `@ConditionalOnProperty(name =
  "app.datasource.replica.enabled", havingValue = "true")` — only
  creates the replica `HikariDataSource` bean when the flag is on.
- `DataSourceConfig` — refactored to expose `writeDataSource` as a
  named bean and to wrap (write, replica) behind the routing wrapper
  when both are present.
- Documented Hikari tuning for primary AND replica with sane defaults.

**Flag-off behaviour is bit-for-bit unchanged.** The primary
`DataSource` bean is the single write Hikari pool, exactly as before
row 35. Nothing routes anywhere.

### What's left for the activation PR

- Per-deployment Railway replica provision (managed-Postgres "read
  replica" feature on the pilot environments).
- Soak procedure (this runbook's "Activation playbook" section).
- Grafana dashboard panels for `hikaricp_connections{pool="hms-replica-pool"}`
  side-by-side with primary.
- A replication-lag alert (Postgres `pg_replication_slots.confirmed_flush_lsn`
  vs primary `pg_current_wal_lsn()`).

### Routing semantics

The routing key is derived from Spring's read-only-transaction flag,
which is set by the `@Transactional(readOnly = true)` advice BEFORE
the connection is acquired:

| Caller                                              | Lookup key | Target pool         |
| --------------------------------------------------- | ---------- | ------------------- |
| No transaction in effect                            | `WRITE`    | Primary             |
| `@Transactional` (default, `readOnly = false`)      | `WRITE`    | Primary             |
| `@Transactional(readOnly = true)`                   | `READ`     | Replica             |
| Nested propagation, outer readOnly=true             | `READ`     | Replica             |
| Liquibase / Flyway migrations                       | `WRITE`    | Primary             |

The routing is **sticky per transaction**: once the first
`getConnection()` is served from a pool, that's the pool the
transaction stays on. Spring's transaction synchronization manager
guarantees the read-only flag is set before the connection acquisition.

### Existing `@Transactional(readOnly = true)` call sites

Methods already annotated `@Transactional(readOnly = true)` will route
to the replica automatically the moment the flag is flipped on. The
inventory at the start of v2.0 includes (non-exhaustive):

- `PatientService.findPatient`, `PatientService.searchPatients`
- `MeController.me`
- `AnnouncementServiceImpl.list*`
- Most FHIR R4 read endpoints
- Most dashboard / analytics aggregators

Before activation, **audit the inventory**: any `readOnly = true`
method that depends on read-your-own-write semantics (e.g., a "create
then immediately fetch" flow that crossed a transaction boundary) is at
risk of seeing replication lag. The conservative fix is to remove
`readOnly = true` from those specific methods so they stay on the
write primary. Tracked in the activation PR.

---

## 3. Activation playbook

### Pre-flight

1. Provision the replica via the managed-Postgres console (Railway:
   "Add read replica" on the primary service; AWS RDS: "Create read
   replica" on the source instance).
2. Create a `hms_app_ro` user on the **primary** (replication will copy
   it):
   ```sql
   CREATE ROLE hms_app_ro WITH LOGIN PASSWORD '…';
   GRANT pg_read_all_data TO hms_app_ro;   -- Postgres ≥ 14
   ```
   For Postgres < 14, grant SELECT on every schema-level object
   explicitly. Replication will propagate the role automatically.
3. Verify the replica is up and lagging < 1 s:
   ```sql
   SELECT now() - pg_last_xact_replay_timestamp();
   ```

### Soak procedure

1. Set the env vars **on UAT first**:
   ```
   APP_DATASOURCE_REPLICA_ENABLED=true
   APP_DATASOURCE_REPLICA_URL=jdbc:postgresql://replica.host:5432/hospital_db
   APP_DATASOURCE_REPLICA_USERNAME=hms_app_ro
   APP_DATASOURCE_REPLICA_PASSWORD=…
   ```
2. Redeploy. Boot logs should contain:
   - `HikariPool-1 — Starting…` for `hms-primary-pool`
   - `HikariPool-2 — Starting…` for `hms-replica-pool`
3. Verify routing: hit a known-read endpoint (e.g.
   `GET /api/me`) and confirm the replica pool's
   `hikaricp_connections_active` increments.
4. Soak for **5 business days** under representative load. Watch:
   - Per-pool active connections (Grafana).
   - Replication lag (`pg_last_xact_replay_timestamp`).
   - Application error rate (5xx in nginx / Railway logs).
   - "Cannot execute INSERT in a read-only transaction" Postgres errors
     — these signal a method incorrectly marked `readOnly = true`.
5. If clean, promote to prod with the same env-var contract.

### Rollback

The fastest, safest rollback:

```
APP_DATASOURCE_REPLICA_ENABLED=false
```

Redeploy. The replica `HikariDataSource` bean is no longer created;
the routing wrapper falls back to the write pool. Existing connections
on the replica pool are closed cleanly via Hikari's `close()` hook
during context shutdown.

The replica database itself stays running (it can be re-enabled with
a single env var flip). Decommission the replica only after a
documented post-rollback retrospective.

---

## 4. Things that are intentionally NOT in this release

- **No replica failover.** When the replica is unavailable, read-only
  transactions FAIL — they do not silently fall back to the primary.
  Rationale: silent failover would mask the activation-PR audit work
  ("which `readOnly = true` methods actually require read-your-own-
  write semantics?"). A future PR can add explicit fallback once the
  audit is complete.
- **No replication-lag-based routing.** A transaction is sent to the
  replica regardless of current lag. Adding a lag-aware router needs a
  PG-specific health check + a pluggable predicate; out of scope for
  the foundation pass.
- **No HikariCP MXBean exporter beyond what spring-boot-starter-actuator
  already gives us.** The `hikaricp_connections*` metrics are already
  exposed via Prometheus.

---

## 5. Schema / code reference

| Component                                                                                          | Role                                                                                |
| -------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| `com.example.hms.config.DataSourceConfig`                                                          | Wires the `writeDataSource` + primary `dataSource` (routing wrapper when enabled).  |
| `com.example.hms.config.datasource.ReplicaDataSourceProperties`                                    | `@ConfigurationProperties` for `app.datasource.replica.*` (URL, creds, Hikari).     |
| `com.example.hms.config.datasource.ReadReplicaDataSourceConfiguration`                             | `@ConditionalOnProperty` bean factory for `replicaDataSource`.                      |
| `com.example.hms.config.datasource.ReadWriteRoutingDataSource`                                     | `AbstractRoutingDataSource` keyed off `isCurrentTransactionReadOnly()`.             |
| `application.properties` — `spring.datasource.hikari.*`                                            | Primary pool tuning (env-overridable).                                              |
| `application.properties` — `app.datasource.replica.*`                                              | Replica config + pool tuning (env-overridable).                                     |

---

Last updated: 2026-05-16. Update when the activation PR lands.
