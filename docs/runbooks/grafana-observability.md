# Grafana Observability Runbook

Companion runbook for every alert defined in
[`grafana/rules/alerts.yml`](../../grafana/rules/alerts.yml) and
[`grafana/provisioning/alerting/alert-rules.yml`](../../grafana/provisioning/alerting/alert-rules.yml).

For SIEM/SOC log search and audit retention, also see
[`docs/observability/splunk.md`](../observability/splunk.md) — Splunk HEC ships the same
Logback events as a JSON envelope when `SPLUNK_HEC_ENABLED=true` on the Railway service.

When an alert fires, follow the matching section below. All dashboard links
assume the local stack at `http://localhost:3000`; substitute your
production base URL.

---

## HmsErrorBudgetFastBurn  (`severity: page`)

**What it means.** Both the 5-minute and 1-hour 5xx error ratios exceed
14.4× the 99.9% SLO budget. At this rate the entire 30-day error budget
is consumed in about two hours.

**First 5 minutes.**

1. Open [SLO & Golden Signals](http://localhost:3000/d/hms-slo-overview).
2. In the "Top 10 endpoints by 5xx rate" table, identify the top
   contributor — click the URI to drill into the API endpoint dashboard.
3. Open [Log Analytics](http://localhost:3000/d/hms-logs) and check the
   "Top exception classes" panel for new exception types.

**Likely causes.**

- Recent deploy — confirm with the *Deploys* annotation overlay on the SLO
  dashboard.
- DB outage — check [PostgreSQL Deep Dive](http://localhost:3000/d/hms-postgres),
  look for `PostgresDown` or pool saturation alerts.
- Downstream provider (Keycloak, payment gateway) — check Auth/security
  events panel on the Log Analytics dashboard.

**Mitigation.**

- If a recent deploy is suspect, redeploy the previous good build from
  Railway: open the `hms-backend` service → **Deployments** tab → find the
  last deployment marked SUCCESS *before* the regression → click the kebab
  menu → **Redeploy**. (See [`railway-services.md`](./railway-services.md)
  for service map and CLI alternatives.)
- If DB is the cause, see `HmsDbPoolSaturated` below.
- Otherwise drop traffic at the gateway / scale up replicas.

---

## HmsErrorBudgetSlowBurn  (`severity: ticket`)

**What it means.** Same query as fast burn but at 6× burn rate over
30m + 6h windows. Not a 2 a.m. wake-up — file a ticket and investigate
within the business day.

**Diagnose.** Same panels as fast burn, but pay attention to long-tail
endpoints and noisy 4xx misclassifications.

---

## HmsP99LatencyHigh  (`severity: ticket`)

**What it means.** Application-wide p99 has been above 2s for 10 minutes.

1. Open [API Endpoint Deep Dive](http://localhost:3000/d/hms-api-endpoints)
   and pick endpoints from the "Top 10 endpoints by p99 latency" table.
2. Look at the latency heatmap — a bimodal distribution usually means a
   slow path (DB, external call) is occasionally being hit.
3. Cross-reference [PostgreSQL Deep Dive](http://localhost:3000/d/hms-postgres)
   for a corresponding spike in transaction time.

---

## HmsTargetDown  (`severity: page`)

**What it means.** Prometheus has not scraped a target for 5 minutes.

The alert is restricted to always-on jobs in the bare stack
(`prometheus`, `grafana`, `loki`, `alloy`, `integrations/spring-boot`).
Profile-only exporter targets are covered by `HmsExporterDown` (ticket-only).

- If `job=integrations/spring-boot` → the backend is down or its
  `/api/actuator/prometheus` endpoint is failing, *or* Alloy is failing to
  scrape/forward. Hit `curl http://hms-backend:8081/api/actuator/health`
  and check both `hms-backend` and `alloy` container logs.
- If `job=alloy` → Alloy is down; backend metrics will stop flowing even
  if the backend itself is healthy. Check `docker compose logs alloy`.
- If `job=loki` → log shipping is broken; the SLO error-rate signal is
  unaffected but `Errors & Warnings` panels go cold.
- If `job=prometheus|grafana` → the observability plane itself is down.

For exporter-down (postgres / redis / node) under
`--profile observability`, see `HmsExporterDown` — silence it if the
profile is intentionally off.

---

## HmsBackendNoTraffic  (`severity: ticket`)

**What it means.** Either no clients are reaching the backend or the metric
pipeline is broken.

- Confirm via the live tail logs panel that the backend is running.
- If logs are flowing but no metrics arrive, restart Alloy.

---

## HmsHeapHigh  (`severity: ticket`)

**What it means.** JVM heap is above 90% of `-Xmx` for 10 minutes.

1. Check the "JVM Memory Used" panel — is one area (Tenured/Eden) growing
   unboundedly? That signals a leak.
2. Open the GC pause panel — a long-tail of Full GCs confirms heap pressure.
3. Trigger a heap dump via `jcmd <pid> GC.heap_dump /tmp/heap.hprof` if you
   have shell access; otherwise schedule a restart and triage later.

---

## HmsDbPoolSaturated  (`severity: page`)

**What it means.** HikariCP active connections are above 90% of the pool
size for 5 minutes. Backend will queue or refuse requests soon.

1. Check [PostgreSQL Deep Dive](http://localhost:3000/d/hms-postgres):
   - "Connections by state" — `idle in transaction` indicates leaked
     transactions.
   - "Locks held" — long-held `AccessExclusiveLock` may be blocking.
2. On the database, run:

   ```sql
   SELECT pid, state, wait_event_type, wait_event, now() - xact_start AS xact_age, query
   FROM pg_stat_activity
   WHERE state != 'idle'
   ORDER BY xact_age DESC NULLS LAST
   LIMIT 20;
   ```

3. Kill blocking sessions if absolutely necessary:
   `SELECT pg_cancel_backend(pid);` (graceful) or
   `SELECT pg_terminate_backend(pid);` (force).
4. Permanent fix usually means tuning a slow query or adding an index.

---

## HmsDbPoolPendingHigh  (`severity: page`)

**What it means.** At least one Spring thread is waiting on the connection
pool for 5 minutes. Same triage as `HmsDbPoolSaturated`.

---

## PostgresDown  (`severity: page`)

**What it means.** postgres-exporter cannot reach Postgres.

- Check `docker compose ps postgres postgres-exporter`.
- `docker compose logs postgres --tail=200`.
- If Postgres is up but the exporter still fails, verify the
  `DATA_SOURCE_NAME` env var on `postgres-exporter`.

---

## PostgresDeadlocksSpiking  (`severity: ticket`)

**What it means.** More than 5 deadlocks in 5 minutes.

1. Check Postgres logs for `deadlock detected` lines — they include the
   conflicting queries.
2. Common causes: out-of-order locking, large updates without sorting,
   concurrent inserts on tables with non-deferred FKs.

---

## PostgresCacheHitRatioLow  (`severity: ticket`)

**What it means.** Buffer cache hit ratio under 95% for 30 minutes.

- Likely an index missing — capture the slowest queries from
  `pg_stat_statements` and run `EXPLAIN (ANALYZE, BUFFERS)`.
- Or `shared_buffers` is too small for the working set (production sizing).

---

## NodeFilesystemFull / NodeMemoryHigh  (`severity: ticket`)

Standard host saturation alerts — clear logs, prune docker volumes,
extend the disk, or scale the host.

---

## HmsRecentlyRestartedRepeatedly  (`severity: page`)

**What it means.** `process_start_time_seconds` changed more than twice
in 10 minutes — backend is in a crash loop.

1. `docker compose logs hms-backend --tail=400`.
2. Check the "Errors & Warnings" panel on the Application Metrics
   dashboard for the last fatal exception before each restart.
3. If the restart loop traces to a bad config / migration, hold the next
   container start with `docker compose stop hms-backend` and fix forward.

---

## Adding a new runbook entry

When you add an alert in `grafana/rules/alerts.yml`, also:

1. Set `annotations.runbook_url` to a `#anchor` in this file.
2. Add a new section above using the same format
   (*What it means*, *First 5 minutes*, *Likely causes*, *Mitigation*).
3. Keep the most-fired alerts at the top.
