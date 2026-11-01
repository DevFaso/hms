# Grafana Cloud Observability Setup

HMS exports traces, metrics and logs to **Grafana Cloud** over OTLP using the
**OpenTelemetry Java Agent**. Everything needed is already in the image and the
config — the pipeline is dormant and activates the moment
`OTEL_EXPORTER_OTLP_ENDPOINT` is set on a Railway service.

> This document was reconstructed from commit `0addcefc3`, where it was added and
> then lost. The original referenced `hms.dev.bitnesttechs.com` and a `uat`
> environment, both retired by `f7980e97f`; the values below are current.

## Which parts already exist

| Piece | Where | State |
|---|---|---|
| OTEL Java agent v2.12.0 | [`Dockerfile`](../Dockerfile) (`ADD`, line 50) | In the image |
| Conditional `-javaagent` attach | [`Dockerfile`](../Dockerfile) (lines 104-112) | Fires when `OTEL_EXPORTER_OTLP_ENDPOINT` is set |
| Micrometer OTLP registry | [`hospital-core/build.gradle`](../hospital-core/build.gradle) | On the classpath |
| OTLP metrics export config | [`application-prod.yml`](../hospital-core/src/main/resources/application-prod.yml) | Reads `GRAFANA_OTLP_AUTH_HEADER` |
| 7 dashboards, recording + alert rules | [`grafana/`](../grafana/) | Written, importable |
| Per-alert response runbook | [`docs/runbooks/grafana-observability.md`](runbooks/grafana-observability.md) | Written |
| Railway env vars | Railway | **The only missing piece** |

As of 2026-11-01 neither `hms-backend-dev` nor `hms-backend-prod` had any
`OTEL_*` or `GRAFANA_*` variable set, so nothing had ever been exported.

## The stack

```
┌──────────────┐    OTLP/HTTP     ┌─────────────────────────────┐
│  HMS Backend │ ───────────────► │  Grafana Cloud OTLP Gateway │
│  (Railway)   │                  │  otlp-gateway-prod-us-east-0│
│              │                  ├─────────────────────────────┤
│  OTEL Agent  │                  │  Tempo   → Traces           │
│  (javaagent) │                  │  Mimir   → Metrics          │
│              │                  │  Loki    → Logs             │
└──────────────┘                  └─────────────────────────────┘
```

**Stack instance ID: `1404501`** · zone **`prod-us-east-0`**.

> The zone is the one correction to the recovered doc, and it matters. The original
> said `prod-us-east-2`; every access policy on this stack (`stack-1404501-alloy`,
> `-quickstart`, `-hl-read`, `-integration-hms-token`) is created in
> `prod-us-east-0`, and policies live in their stack's region. The other region's
> gateway also exists and answers a mis-regioned token with a bare `401` —
> indistinguishable from a bad token, which is a long afternoon of debugging.
>
> Confirm verbatim at **Grafana Cloud → your stack → OpenTelemetry**, which prints
> the endpoint and instance ID. If it disagrees, no code change is needed:
> `GRAFANA_ZONE=prod-us-east-2 ./scripts/grafana-cloud-setup.sh dev glc_…`

Production uses **push**, not scrape. That matters for security: see
[Why the scrape endpoint is closed](#why-the-scrape-endpoint-is-closed).

## Setup

### 1. Mint an access policy token

The console offers scope **sets** rather than individual scopes. The one you want
is **`set:alloy-data-write`**, which bundles `metrics:write`, `logs:write`,
`traces:write` and `profiles:write`.

Stack 1404501 already has policies, so you do not need a new one:

| Policy | Scopes | Tokens | Use for the backend? |
|---|---|---|---|
| `stack-1404501-alloy` | `set:alloy-data-write` | none active (1 expired) | **Yes — add a token here** |
| `stack-1404501-quickstart` | `set:alloy-data-write` | `windows-token`, in active use | No — don't share it |
| `stack-1404501-integration-hms-token` | `metrics:read` + `set:alloy-data-write` | 1 | Workable, but carries a read scope it doesn't need |
| `stack-1404501-hl-read` | read-only | — | No — cannot write |
| `pdc-tiegoouedraogo-default` | `set:pdc-signing` | none | No — Private Data source Connect |

Go to **Administration → Users and access → Access policies →
`stack-1404501-alloy` → Add token**, name it something like `hms-backend-otlp`,
and copy the `glc_…` value — it is shown exactly once.

Keeping the backend on its own token means you can revoke it without taking down
whatever `windows-token` is feeding.

### 2. Run the setup script

```bash
# dev first, verify, then prod
./scripts/grafana-cloud-setup.sh dev  glc_your_token_here
./scripts/grafana-cloud-setup.sh prod glc_your_token_here
```

The script builds the base64 auth header, sets every variable on the right
Railway service, and prints what it set (never the token). It does **not**
trigger a deploy — Railway redeploys on the next push, or you redeploy manually
when you are ready.

### 3. What the script sets

| Variable | dev | prod |
|---|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `https://otlp-gateway-prod-us-east-0.grafana.net/otlp` | same |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` | same |
| `OTEL_EXPORTER_OTLP_HEADERS` | `Authorization=Basic <base64(1404501:glc_…)>` | same |
| `GRAFANA_OTLP_AUTH_HEADER` | `Basic <base64(1404501:glc_…)>` | same |
| `OTEL_SERVICE_NAME` | `hms-backend` | `hms-backend` |
| `OTEL_RESOURCE_ATTRIBUTES` | `deployment.environment=dev,service.namespace=hms` | `deployment.environment=prod,service.namespace=hms` |
| `OTEL_METRICS_ENABLED` | `true` | `true` |
| `OTEL_TRACES_SAMPLER` | `parentbased_always_on` | `parentbased_traceidratio` |
| `OTEL_TRACES_SAMPLER_ARG` | *(unset)* | `0.1` |

`OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf` is required — the Grafana Cloud OTLP
gateway does not accept the agent's default `grpc`.

The two auth variables carry the same credential in two formats because two
exporters are active: the agent reads `OTEL_EXPORTER_OTLP_HEADERS`, and the
Micrometer OTLP registry reads `GRAFANA_OTLP_AUTH_HEADER` via
`application-prod.yml`. Keep them in sync, or metrics and traces will disagree
about whether they are authorized.

### 4. Verify

```bash
# Agent attached? Look for this line in the deploy logs:
railway logs --service hms-backend-dev | grep -i "OpenTelemetry agent enabled"
```

Then in Grafana Cloud → **Explore** → Mimir, query
`up{service_name="hms-backend"}` or
`http_server_requests_seconds_count{deployment_environment="dev"}`.

### 5. Import the dashboards

The seven dashboards in [`grafana/dashboards/`](../grafana/dashboards/) were
written against a self-hosted Prometheus/Loki pair, but every panel references
the `$prometheus_ds` / `$loki_ds` template variables, so they import into
Grafana Cloud unchanged — **Dashboards → New → Import → Upload JSON**, then pick
your Grafana Cloud Mimir and Loki datasources when prompted.

Start with `hms-slo-overview.json`.

## Why the scrape endpoint is closed

`/api/actuator/prometheus` was `permitAll()` until this change. Because Railway
serves the management endpoints on the same public hostname as the API, and
`management.server.port` is not set, that published roughly 1,800 unauthenticated
series on dev and prod: every API URI the app serves, JVM and Hikari internals,
and the business counters.

It is now gated by `SecurityConfig#metricsScrapeAuthorization`, which requires
`app.observability.metrics.scrape-token` (`METRICS_SCRAPE_TOKEN`) to be both
configured and presented:

- **Blank token (the default) → the endpoint is closed to everyone.** This is
  the correct posture for Railway, because production pushes via OTLP and never
  needs to be scraped.
- **Token set → `Authorization: Bearer <token>` or `X-Metrics-Token: <token>`
  is required.** Comparison is constant-time.

`/api/actuator/health` and `/api/actuator/info` remain public — Railway's
healthcheck needs them, and health detail is already restricted to an authorized
`SUPER_ADMIN` by `management.endpoint.health.show-details=when_authorized`.

Only set `METRICS_SCRAPE_TOKEN` on Railway if you deliberately switch from push
to a Grafana Cloud hosted-Prometheus scrape job.

## Free-tier fit

The Grafana Cloud free tier is genuinely usable here, and these are the numbers
that decide it (measured 2026-11-01 against the live services):

| Free-tier allowance | HMS actual |
|---|---|
| 10,000 active metric series | ~1,850 on dev, fewer on prod → roughly 3,500 for both envs |
| 50 GB logs / month | Well under, at current traffic |
| 50 GB traces / month | **Only with sampling** — see below |
| 14-day metrics retention | Enough for the SLO burn-rate windows (longest is 6 h) |
| 3 active users | Enough for the current team |

Traces are the one that can bite. The agent defaults to
`parentbased_always_on` — 100% of requests. That is fine on dev and is what the
script sets there, but prod gets `parentbased_traceidratio` at `0.1` so trace
volume scales with traffic rather than ambushing you. Raise it when you need
detail for an investigation, and lower it afterwards.

Metric retention of 14 days is far shorter than the **6-year audit-log
retention** this system targets — Grafana Cloud metrics are not the audit trail.
That obligation is met by `support.audit_event_logs` in Postgres; see
[`docs/compliance/hipaa-gap.md`](compliance/hipaa-gap.md).

**No BAA on the free tier.** Grafana Labs offers one only on paid plans. Traces
and logs can carry request paths and identifiers, so if HMS ever serves a US
covered entity, either move to a plan with a BAA or keep logs self-hosted. For
the current ECOWAS market this is a quality bar rather than a legal obligation —
see [`docs/compliance/ecowas-residency-decision-record.md`](compliance/ecowas-residency-decision-record.md).

## Local development

Local dev does **not** use Grafana Cloud — the docker-compose stack is the whole
pipeline. See [`grafana/README.md`](../grafana/README.md) for the quickstart.

The one thing to know: **the backend is not a compose service.** It runs on the
host under the `local` profile on port 8081, so Alloy and the blackbox exporter
reach it through `host.docker.internal`, and Alloy must present the same
`METRICS_SCRAPE_TOKEN` the backend was started with:

```bash
export METRICS_SCRAPE_TOKEN=local-dev-scrape-token
./gradlew :hospital-core:bootRun --args="--spring.profiles.active=local"

# in another shell — the compose default matches the token above
docker compose --profile observability up -d
```

## Security notes

- Never commit a `glc_` token. The setup script reads it from the command line
  and writes it only to Railway.
- The access policy token should hold `metrics:write`, `logs:write`,
  `traces:write` and nothing else.
- OTLP export is HTTPS with Basic auth.
- Rotating the token means re-running the setup script for both environments; it
  is on the service-account rotation list in
  [`docs/compliance/soc2-gap.md`](compliance/soc2-gap.md).
