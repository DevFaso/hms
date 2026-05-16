# Synthetic monitoring — operational runbook

**Status:** foundation pass shipped on `feat/v2.0-synthetic-monitoring` (roadmap row 43).

External HTTP probing of HMS public surfaces from multiple geographic vantage points. The PR wires a single-geo local probe so the alerts can be exercised against real signal; the **multi-geo deployment is operational work** and is not landed here.

---

## What ships in this PR

- **Blackbox-exporter** added to `docker-compose.yml` under the existing `observability` profile (alongside postgres-exporter / redis-exporter / node-exporter). Exposes Prometheus-style probes on `:9115`.
- **`grafana/blackbox.yml`** with four probe modules: `http_health_2xx`, `http_smart_2xx`, `http_fhir_metadata_2xx`, `http_cds_discovery_2xx`. Each is a `prober: http` module with explicit `valid_status_codes`, accept headers, and TLS verification (false for local, but kept on by default so a production drop-in just needs the cert chain to be valid).
- **Four Prometheus scrape jobs** in `grafana/prometheus.yml` driving Blackbox against:
  - `/api/actuator/health/liveness`
  - `/api/actuator/health/readiness`
  - `/api/fhir/metadata`
  - `/api/fhir/.well-known/smart-configuration`
  - `/api/cds-services`
- **Three alert rules** in `grafana/rules/alerts.yml`:
  - `HmsSyntheticProbeFailureRate` — > 10% failure for 5 minutes (the deliverable target).
  - `HmsSyntheticProbeAllGeosFailing` — every vantage point returns 0 for 2 minutes (hard outage).
  - `HmsSyntheticProbeLatencyHigh` — `probe_duration_seconds > 5` for 10 minutes (latency degradation).

---

## What's deferred (operational, not code)

The row-43 deliverable is "Grafana k6 cloud OR Blackbox-exporter probes from 3 geos; alert on > 10% probe failure for 5 min". The PR lands the alert + probe **infrastructure**; the three-geo rollout is operational:

1. **Option A — Blackbox in 3 cloud regions.** Stand up three Blackbox instances (e.g. AWS eu-west-1, AWS us-east-1, OVH-Africa) each scrape-pointed at the same target list but with their own `external_labels: { geo: <region> }` override. All three remote_write to Grafana Cloud Mimir. The `avg by (probe_target)` aggregation in `HmsSyntheticProbeFailureRate` then absorbs any single-region misbehavior.
2. **Option B — Grafana k6 cloud.** Reuse the existing `scripts/perf/dispense-baseline.js` script (already wired into `.github/workflows/perf-baseline.yml`) and schedule a smaller "synthetic-canary" k6 script from k6 cloud's three default load zones (`amazon:us:ashburn`, `amazon:eu:dublin`, `amazon:af:cape-town`). The k6-cloud → Grafana metrics integration writes `probe_success_*` series under the same `application=hms,service=synthetic` labels so the same alert rule fires.

Pick one before flipping row 43 to `completed`. Option A is closer to the existing local-stack ergonomics; Option B is closer to the existing perf-baseline CI wiring.

---

## How to run the local probes

```powershell
# Boot the observability profile (includes blackbox-exporter + the existing
# postgres/redis/node exporters + prometheus + grafana + alloy).
docker compose --profile observability up -d

# Inspect the raw probe output for one target — useful while authoring
# new modules in blackbox.yml.
Invoke-WebRequest -Uri "http://localhost:9115/probe?target=http://hms-backend:8080/api/actuator/health/liveness&module=http_health_2xx" `
    | Select-Object -ExpandProperty Content

# Confirm Prometheus is scraping the new jobs.
Invoke-WebRequest -Uri 'http://localhost:9090/api/v1/targets?state=active' `
    | Select-Object -ExpandProperty Content | ConvertFrom-Json `
    | Select-Object -ExpandProperty data | Select-Object -ExpandProperty activeTargets `
    | Where-Object scrapeUrl -match 'blackbox-exporter' | Select-Object scrapeUrl,health,lastScrape
```

`probe_success` is `1` on a passing probe and `0` on a failing one — `avg_over_time(probe_success[5m]) < 0.9` is the deliverable's > 10% failure window.

---

## Alert exercise

```powershell
# Stop the backend container; within 5 minutes the
# HmsSyntheticProbeFailureRate alert should be PENDING and within 5+5
# minutes (the alert's `for: 5m`) it should be FIRING.
docker compose stop hms-backend

# Recover and confirm the alert resolves.
docker compose start hms-backend
```

When testing in CI, the alert can be exercised against a stopped target by temporarily setting `--web.enable-lifecycle` on Prometheus and POSTing a synthetic series — but this is rarely worth the noise; the production smoke is to deliberately fail one probe target and confirm the page lands in the on-call channel.

---

## Production deployment notes

- Public-internet probe targets should hit `https://<env>.hms-domain` rather than the container hostname — replace the `static_configs.targets` list in `grafana/prometheus.yml` per-environment (or template via Alloy `prometheus.scrape "blackbox"` blocks if running in Grafana Cloud).
- TLS validation should remain **on** in production. The blackbox modules ship with `insecure_skip_verify: false`; if a probe fails because of a cert chain issue, fix the cert chain — don't bypass.
- The PHI inventory does **not** apply to the synthetic probe path — these probes never carry patient data and are exempt from the encryption-at-rest controls in `docs/compliance/phi-inventory.md`. (They are subject to the standard audit/access logging applied to any HMS endpoint.)

---

## Reference

- `docker-compose.yml` — `blackbox-exporter` service definition (profile=observability)
- `grafana/blackbox.yml` — probe modules
- `grafana/prometheus.yml` — `blackbox_*` scrape jobs
- `grafana/rules/alerts.yml` — `hms.synthetic` alert group
- `docs/runbooks/disaster-recovery.md` — row 9 (the dependency that gates row 43)
- `scripts/perf/dispense-baseline.js` — existing k6 baseline (related but distinct)
