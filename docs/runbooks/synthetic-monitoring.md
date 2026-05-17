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

## Multi-geo rollout — pick Option A OR Option B

The row-43 deliverable is "Grafana k6 cloud OR Blackbox-exporter probes from 3 geos; alert on > 10% probe failure for 5 min". The original PR landed the alert + probe infrastructure; the follow-on adds **deployable templates for both options** so the operator only needs to provision cloud accounts and substitute placeholders. Pick one before flipping row 43 to `completed`. Both write the same `probe_success` + `probe_duration_seconds` series under `application=hms, service=synthetic` so the existing `HmsSyntheticProbeFailureRate` / `HmsSyntheticProbeAllGeosFailing` alerts evaluate the same way regardless of source.

### Option A — Blackbox in 3 cloud regions

**Template:** [`grafana/prometheus-multigeo.example.yml`](../../grafana/prometheus-multigeo.example.yml).

Stand up three Blackbox-exporter instances + one Prometheus next to each:

| Region | `geo` external_label | Notes |
| --- | --- | --- |
| AWS `eu-west-1` (Dublin) | `aws-eu-west-1` | Closest k6/Blackbox vantage to EU-hosted Railway region. |
| AWS `us-east-1` (Virginia) | `aws-us-east-1` | Validates the trans-Atlantic path for any partner reading the SMART config. |
| OVH Dakar bare-metal OR AWS `af-south-1` (Cape Town) | `ovh-dakar` / `aws-af-south-1` | ECOWAS-adjacent vantage. Swap to OVH Dakar when row 39 (`docs/compliance/ecowas-residency-decision-record.md`) lands. |

Per-region copy of the template — substitute these four placeholders:

| Placeholder | Example |
| --- | --- |
| `${GEO_LABEL}` | `aws-eu-west-1` |
| `${BLACKBOX_HOST}` | `blackbox-eu-west-1.local:9115` |
| `${HMS_PUBLIC_BASE_URL}` | `https://api.hms.bitnesttechs.com` |
| `${MIMIR_REMOTE_WRITE_URL}` | The Grafana Cloud Mimir push URL from project → connections |

Cross-region invariants:

- All three remote_write to the **same** Mimir tenant.
- The alert rule `HmsSyntheticProbeAllGeosFailing` requires `count(probe_success == 0) == count(probe_success)` — three geos is the minimum that meaningfully distinguishes "global outage" from "one network blip". Two geos halves the precision; one geo defeats the purpose.
- Don't stamp `geo` via `relabel_configs` AND `external_labels` — `external_labels` wins at remote_write time, so the two would silently disagree on the Prometheus self-view.

Validate the rollout with:

```promql
# Three series — one per geo — each near 1.0 on a healthy stack.
sum by (geo) (rate(probe_success[5m]))

# The page condition exactly: probe success below 90 % anywhere.
avg by (probe_target) (avg_over_time(probe_success[5m])) < 0.9
```

### Option B — Grafana k6 cloud canary

**Template:** [`scripts/perf/synthetic-canary-k6.js`](../../scripts/perf/synthetic-canary-k6.js).

Distinct from `scripts/perf/dispense-baseline.js` (which is the performance baseline — 50 VUs, auth, write paths). The canary is intentionally tiny: 1 VU per zone, 5 minutes per run, **only** public no-auth surfaces. Three default k6 cloud zones:

| Zone | Geo |
| --- | --- |
| `amazon:us:ashburn` | US-East |
| `amazon:eu:dublin` | EU-West |
| `amazon:af:cape-town` | AF-South |

Schedule via k6 cloud → Scheduled tests → every 5 minutes, OR via a GitHub Action workflow_dispatch on a cron:

```yaml
# .github/workflows/synthetic-canary.yml (example — not committed)
on:
  schedule:
    - cron: '*/5 * * * *'
  workflow_dispatch:
jobs:
  canary:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: grafana/k6-action@v0.3.1
        with:
          filename: scripts/perf/synthetic-canary-k6.js
          cloud: true
        env:
          K6_CLOUD_TOKEN: ${{ secrets.K6_CLOUD_TOKEN }}
          K6_CLOUD_PROJECT_ID: ${{ secrets.K6_CLOUD_PROJECT_ID }}
          HMS_PUBLIC_BASE_URL: https://api.hms.bitnesttechs.com
```

The script emits a `probe_success` rate metric and `probe_duration_seconds` trend tagged per probe — k6 cloud's Grafana Cloud integration ships these to the same Mimir tenant the in-cluster Prometheus writes to, so the alert rules fire on identical series shapes.

Validate the rollout with:

- The cloud project dashboard's per-zone p95 panel — three columns, one per zone.
- The same `sum by (geo) (rate(probe_success[5m]))` query in Grafana — three series should appear with the k6-cloud-supplied geo labels.

### Choosing between A and B

| Concern | Option A — Blackbox | Option B — k6 cloud |
| --- | --- | --- |
| **Operational ergonomics** | Native to the existing local stack — copy of `prometheus.yml`, no new vendor. | Vendor lock-in to Grafana Cloud k6, but reuses the perf-baseline runner. |
| **Cost** | Three small VMs + Mimir ingest. ≈ USD 30/mo at scale. | k6 cloud pay-per-execution; ≈ USD 50/mo at every-5-min cadence. |
| **Failure modes** | Per-region Blackbox + Prometheus failure surface. | Single vendor outage takes all three zones. |
| **ECOWAS path** | Replace one region with OVH Dakar bare-metal post-row-39. | k6 cloud Cape Town stays in place; ECOWAS residency is moot for non-PHI probes. |

Default recommendation: **Option A** for any deployment where the in-cluster Prometheus already remote-writes to Mimir, **Option B** for deployments that already have a k6 cloud subscription for perf-baseline. Either satisfies the row-43 deliverable.

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
