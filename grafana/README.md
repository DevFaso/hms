# HMS Observability Stack — Grafana / Prometheus / Loki / Alloy

A complete, opinionated, "best-ever" local observability setup for the HMS
backend, with hooks to point at Grafana Cloud in production.

```
┌──────────────┐  scrape  ┌────────────┐  query  ┌──────────┐
│ hms-backend  │ ───────► │ Prometheus │ ◄────── │  Grafana │ (UI :3000)
│  :8081/      │ ─┐       │   :9090    │         └────┬─────┘
│  actuator    │  │       └─────▲──────┘              │
└──────────────┘  │             │ remote_write        │ logs
                  │  scrape     │                     ▼
                  ▼             │             ┌──────────┐
            ┌──────────┐  push  │             │   Loki   │ (logs :3100)
            │  Alloy   │────────┘             └────▲─────┘
            │  :12345  │  docker logs              │ push
            └──────────┘ ──────────────────────────┘
```

## What lives in this folder

| Path                              | Purpose                                            |
|-----------------------------------|----------------------------------------------------|
| `prometheus.yml`                  | Prometheus scrape config + rule loader             |
| `rules/recording.yml`             | Recording rules (RED, SLO ratios, USE)             |
| `rules/alerts.yml`                | Prometheus alerting rules (SLO burn-rate + infra)  |
| `config.alloy`                    | Alloy collector — scrapes backend + ships logs     |
| `dashboards/*.json`               | Provisioned Grafana dashboards                     |
| `provisioning/datasources/`       | Prometheus + Loki datasource definitions           |
| `provisioning/dashboards/`        | Dashboard provider config                          |
| `provisioning/alerting/`          | Contact points, notification policies, alert rules |

## Dashboards

All dashboards live under the **HMS** folder in Grafana, are tagged with
`hms`, and link to each other via the dashboards-dropdown in the top-right.

| UID                    | Title                          | When to open it                        |
|------------------------|--------------------------------|----------------------------------------|
| `hms-slo-overview`     | SLO & Golden Signals           | First stop. RED + USE + burn-rate.     |
| `hms-app-metrics`      | Application Metrics & Logs     | Spring Boot internals (JVM, HTTP, DB). |
| `hms-business-kpi`     | Business KPIs                  | Patient/appointment/billing volume.    |
| `hms-api-endpoints`    | API Endpoint Deep Dive         | Drill into one URI/method.             |
| `hms-postgres`         | PostgreSQL Deep Dive           | DB connections, locks, cache, IO.      |
| `hms-host`             | Host Metrics                   | Node-exporter (CPU/mem/disk/net).      |
| `hms-logs`             | Log Analytics (Loki)           | Errors, exceptions, free-text search.  |

### Dashboard conventions used everywhere

- `$prometheus_ds` and `$loki_ds` template variables let the same JSON be
  used against another datasource without editing.
- `$application` defaults to `hms` and is reused across dashboards so a
  single picker filters everything.
- `$instance` accepts multi-value + `All` so you can compare or aggregate
  pods/replicas.
- All `rate()` calls use `$__rate_interval` so they auto-fit the time range.
- Tables that show URIs link to the API endpoint deep-dive dashboard
  (`?var-uri=…`) for one-click drill-down.
- Deploy annotations come from the Loki query
  `{application="hms"} |~ "Started .*Application in [0-9.]+ seconds"`.

## Local quickstart

**The backend is not a compose service.** It runs on the host, and the collectors
reach it through `host.docker.internal`. The previous version of this file told you
to `docker compose up -d hms-backend`, which silently did nothing — and Alloy's
`hms-backend:8081` target never resolved, so no backend series were ever collected.

`/api/actuator/prometheus` is token-gated (see
[`docs/GRAFANA_CLOUD_SETUP.md`](../docs/GRAFANA_CLOUD_SETUP.md)), so the backend and
Alloy have to agree on the token. The compose default is `local-dev-scrape-token`:

```bash
# 1. Backend on the host (local profile, port 8081)
export METRICS_SCRAPE_TOKEN=local-dev-scrape-token
./gradlew :hospital-core:bootRun --args="--spring.profiles.active=local"

# 2. Collectors + UI (another shell)
docker compose up -d prometheus loki alloy grafana

# Full observability profile (adds postgres/redis/node/blackbox exporters):
docker compose --profile observability up -d
```

Confirm the scrape is actually landing — this should be non-empty within ~30s:

```bash
curl -s 'http://localhost:9090/api/v1/query?query=up{job="integrations/spring-boot"}'
```

Then:

- Grafana → http://localhost:3000  (admin/admin — change in compose)
- Prometheus → http://localhost:9090
- Loki API → http://localhost:3100/ready

The `GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH` env makes the **SLO &
Golden Signals** dashboard your home page.

## Production: Grafana Cloud

Production does **not** use this stack. Railway runs the OpenTelemetry Java agent,
which pushes traces, metrics and logs straight to the Grafana Cloud OTLP gateway —
nothing scrapes the backend, which is why the scrape endpoint stays closed there.

Setup is one command per environment; see
[`docs/GRAFANA_CLOUD_SETUP.md`](../docs/GRAFANA_CLOUD_SETUP.md):

```bash
./scripts/grafana-cloud-setup.sh dev  glc_your_token
./scripts/grafana-cloud-setup.sh prod glc_your_token
```

If instead you want *this* Alloy to remote-write to Grafana Cloud (e.g. shipping
from a self-hosted node), override these on the `alloy` service:

```yaml
environment:
  GRAFANA_METRICS_URL:   https://prometheus-prod-us-east-0.grafana.net/api/prom/push
  GRAFANA_METRICS_USER:  "1404501"
  GRAFANA_METRICS_TOKEN: "glc_…"
```

Same `config.alloy` keep-list applies — only the remote-write endpoint changes.

## SLO definition

- **Availability SLI**: `1 - (rate(http_5xx) / rate(http_total))`
- **Default SLO target**: `99.9%` (4.32 minutes downtime / month)
- **Latency SLI**: `% requests under target` (heatmap target via dashboard
  variable, default 1 s)

Burn-rate alerting follows the **multi-window multi-burn-rate** pattern
from the Google SRE Workbook:

| Window pair  | Burn rate | Action |
|--------------|-----------|--------|
| 5m AND 1h    | > 14.4×   | Page (fast burn — 30d budget gone in ~2h) |
| 30m AND 6h   | > 6×      | Ticket (slow burn) |

Both are encoded twice for redundancy:

- `grafana/rules/alerts.yml` — Prometheus-native (works without Grafana).
- `grafana/provisioning/alerting/alert-rules.yml` — Grafana Unified
  Alerting (UI state, history, silences).

## Adding a new dashboard

1. Edit live in Grafana (`allowUiUpdates: true`).
2. In the dashboard menu choose **Share → Export → Save to file**.
3. Drop the JSON into `grafana/dashboards/`.
4. Replace any hard-coded datasource UIDs with `${prometheus_ds}` /
   `${loki_ds}` (look for `"uid": "hms-prometheus"`).
5. Add `$application` + `$instance` template variables — copy from
   `hms-app-metrics.json` if you want the same conventions.
6. Verify with: `jq empty grafana/dashboards/<file>.json`.

The provisioner rescans every 30s — no Grafana restart needed.

## Adding a new alert

- Prefer **Prometheus rules** (`grafana/rules/alerts.yml`) for anything
  expressible in PromQL — they survive Grafana being down.
- Use **Grafana Unified Alerting** (`grafana/provisioning/alerting/`)
  when you need multi-datasource expressions, "no data" handling, or want
  the alert to show in the Grafana UI/Slack with the dashboard link.

## Documentation index

- [docs/GRAFANA_CLOUD_SETUP.md](../docs/GRAFANA_CLOUD_SETUP.md) — production setup, free-tier limits, why the scrape endpoint is closed.
- [docs/runbooks/grafana-observability.md](../docs/runbooks/grafana-observability.md) — page-by-page response runbook for each alert.
- [docs/runbooks/synthetic-monitoring.md](../docs/runbooks/synthetic-monitoring.md) — blackbox probe targets and multi-region plan.
