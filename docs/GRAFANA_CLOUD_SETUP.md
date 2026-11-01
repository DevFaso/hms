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
| OTLP metrics export config | [`application.properties`](../hospital-core/src/main/resources/application.properties) | Reads `GRAFANA_OTLP_AUTH_HEADER` + `OTEL_METRICS_EXPORT_URL`. Global, not prod-only — dev boots the `dev` profile and would never load `application-prod.yml`. |
| 7 dashboards, recording + alert rules | [`grafana/`](../grafana/) | Written, importable |
| Per-alert response runbook | [`docs/runbooks/grafana-observability.md`](runbooks/grafana-observability.md) | Written |
| Railway env vars | Railway | **The only missing piece** |

As of 2026-11-01 neither `hms-backend-dev` nor `hms-backend-core` (the prod
backend — it does *not* follow the `-<env>` pattern) had any
`OTEL_*` or `GRAFANA_*` variable set, so nothing had ever been exported.

## The stack

```
┌──────────────┐    OTLP/HTTP     ┌─────────────────────────────┐
│  HMS Backend │ ───────────────► │  Grafana Cloud OTLP Gateway │
│  (Railway)   │                  │  otlp-gateway-prod-us-east-2│
│              │                  ├─────────────────────────────┤
│  OTEL Agent  │                  │  Tempo   → Traces           │
│  (javaagent) │                  │  Mimir   → Metrics          │
│              │                  │  Loki    → Logs             │
└──────────────┘                  └─────────────────────────────┘
```

**Stack instance ID: `1404501`** · zone **`prod-us-east-2`**.

> **The zone is counter-intuitive, so it is worth stating how it was established.**
> Every access policy on this stack is labelled `prod-us-east-0`, which looks like
> it should settle the question — it does not. The access-policy region label does
> **not** determine the OTLP gateway zone. Posting to both gateways with the real
> token gave:
>
> | Gateway | Result |
> |---|---|
> | `otlp-gateway-prod-us-east-2` | **`200`** — authenticated and accepted |
> | `otlp-gateway-prod-us-east-0` | `502` |
>
> So `prod-us-east-2` is correct, and the value in the original (deleted) version of
> this document was right. Do not "correct" it to `us-east-0` on the basis of the
> policy labels without re-running this probe:
>
> ```powershell
> $sec = Read-Host -AsSecureString "Grafana token"
> $GLC = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
> $B64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("1404501:$GLC"))
> foreach ($Z in 'prod-us-east-2','prod-us-east-0') {
>   $code = 0
>   try {
>     $r = Invoke-WebRequest -Uri "https://otlp-gateway-$Z.grafana.net/otlp/v1/metrics" -Method POST -Headers @{Authorization = "Basic $B64"} -ContentType 'application/x-protobuf' -Body ([byte[]]@()) -UseBasicParsing
>     $code = [int]$r.StatusCode
>   } catch { if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode } }
>   "{0,-16} HTTP {1}" -f $Z, $code
> }
> Remove-Variable GLC, B64, sec
> ```
>
> A `401` on both means the token is wrong, not the region. Override without a code
> change: `GRAFANA_ZONE=prod-us-east-0 ./scripts/grafana-cloud-setup.sh dev`.

Production uses **push**, not scrape. That matters for security: see
[Why the scrape endpoint is closed](#why-the-scrape-endpoint-is-closed).

## Setup

### 1. Mint an access policy token

The console offers scope **sets** rather than individual scopes. The one you want
is **`set:alloy-data-write`**, which bundles `metrics:write`, `logs:write`,
`traces:write` and `profiles:write`.

Stack 1404501 carries two access policies (the rest were deleted 2026-11-01):

| Policy | Scopes | Use for the backend? |
|---|---|---|
| **`stack-1404501-alloy`** | `set:alloy-data-write` | **Yes** — this is the one |
| `pdc-tiegoouedraogo-default` | `set:pdc-signing` | No — Private Data source Connect |

**Administration → Users and access → Access policies → `stack-1404501-alloy` →
Add token**, name it something like `hms-backend-otlp`, and copy the `glc_…`
value — it is shown exactly once.

> **Token expiry is an operational trap.** The current token on this policy
> expires **8 February 2027**. Grafana Cloud does not warn you, and the failure
> mode is silent: the agent keeps running, exports keep 401-ing, dashboards
> simply go flat. Either mint a non-expiring token, or put the renewal on the
> service-account rotation list in
> [`docs/compliance/soc2-gap.md`](compliance/soc2-gap.md) and add an alert on the
> metrics going absent. Deleting the other policies means this is now the single
> credential the whole pipeline depends on.

### 2. Run the setup script

```bash
# dev first, verify, then prod. Each prompts for the token with echo off.
./scripts/grafana-cloud-setup.sh dev
./scripts/grafana-cloud-setup.sh prod
```

The token is **not** an argument: argv is readable in the process list and lands
in shell history, and this token can write every signal in the stack. The script
reads it from `GRAFANA_CLOUD_TOKEN`, or prompts for it, and passes the two
credential-bearing variables to Railway over stdin.

It builds the base64 auth header, sets every variable on the right Railway
service, and prints what it set (never the token). It does **not** trigger a
deploy — `--skip-deploys` keeps that your decision.

### 3. What the script sets

| Variable | dev | prod |
|---|---|---|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `https://otlp-gateway-prod-us-east-2.grafana.net/otlp` | same |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` | same |
| `OTEL_EXPORTER_OTLP_HEADERS` | `Authorization=Basic <base64(1404501:glc_…)>` | same |
| `GRAFANA_OTLP_AUTH_HEADER` | `Basic <base64(1404501:glc_…)>` | same |
| `OTEL_SERVICE_NAME` | `hms-backend` | `hms-backend` |
| `OTEL_RESOURCE_ATTRIBUTES` | `deployment.environment=dev,service.namespace=hms` | `deployment.environment=prod,service.namespace=hms` |
| `OTEL_METRICS_ENABLED` | `true` | `true` |
| `OTEL_METRICS_EXPORTER` | `none` | `none` |
| `OTEL_METRICS_EXPORT_URL` | `…/otlp/v1/metrics` | same |
| `OTEL_TRACES_SAMPLER` | `parentbased_always_on` | `parentbased_traceidratio` |
| `OTEL_TRACES_SAMPLER_ARG` | *(unset)* | `0.1` |

`OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf` is required — the Grafana Cloud OTLP
gateway does not accept the agent's default `grpc`.

**One exporter owns each signal.** The agent does traces and logs; the Micrometer
OTLP registry does metrics. `OTEL_METRICS_EXPORTER=none` turns off the agent's own
metrics exporter — leaving both on publishes every series twice and doubles what
you consume of the 10,000-series free allowance.

`OTEL_METRICS_EXPORT_URL` exists because Micrometer wants a **full signal URL**
(Boot's own default is `http://localhost:4318/v1/metrics`), while the agent wants
the gateway **base**. They cannot share one variable: pointing Micrometer at the
bare `/otlp` base turns every 60-second push into a rejected POST, and the
business counters never reach Mimir while everything else looks healthy.

The two auth variables carry the same credential in two formats because two
exporters are active: the agent reads `OTEL_EXPORTER_OTLP_HEADERS`, and the
Micrometer OTLP registry reads `GRAFANA_OTLP_AUTH_HEADER` via
`application.properties`. Keep them in sync, or metrics and traces will disagree
about whether they are authorized.

### 4. Verify

```bash
# Agent attached? Look for this line in the deploy logs:
railway logs --service hms-backend-dev | grep -i "OpenTelemetry agent enabled"
```

Then in Grafana Cloud → **Explore** → your Mimir datasource.

Do **not** check with `up{…}`. `up` is synthesised by a scraper, and this is a
push pipeline — it will never exist here, so a perfectly healthy setup reads as a
total failure. Query a metric the application actually emits:

```promql
jvm_memory_used_bytes{deployment_environment="dev"}
http_server_requests_seconds_count{deployment_environment="dev"}
```

Allow about two minutes: the Micrometer push step is 60s. Traces land in Tempo
under `service.name = hms-backend`, logs in Loki under the same resource
attributes.

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
| 10,000 active metric series | ~1,850 on dev, fewer on prod → roughly 3,500 for both envs, **counted once** because `OTEL_METRICS_EXPORTER=none` leaves a single exporter per signal. Leave the agent's metrics exporter on and this doubles. |
| 50 GB logs / month | Well under, at current traffic |
| 50 GB traces / month | **Only with sampling** — see below |
| 14-day metrics retention | Enough for the SLO burn-rate windows (longest is 6 h) |
| 3 active users | Enough for the current team |

One thing to check before switching HMS on: anything *else* already pushing to
this stack counts against the same 10,000 series. A Grafana Alloy quickstart on a
workstation ships host and integration metrics by default, and a single host can
be a few thousand series — potentially more than both HMS environments combined.
Look at **Billing/Usage → active series** first, and trim or stop any collector
you are not using.

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

## Synthetic checks

Stack 1404501 already runs a Grafana Cloud synthetic check, and it is green for a
host that has been dead since August — it is a DNS check against the retired
`hms.dev.bitnesttechs.com`, whose stale record still resolves. Retarget it to
`https://api.dev.e-keneya.com/api/actuator/health` as an HTTP check. Full detail and
the prod settings: [`docs/runbooks/synthetic-monitoring.md`](runbooks/synthetic-monitoring.md).

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

- Never commit a `glc_` token. The setup script never takes it as an argument —
  it reads `GRAFANA_CLOUD_TOKEN` or prompts with echo off, and hands it to
  Railway over stdin, so it stays out of argv, the process list and shell
  history. It is written only to Railway.
- The access policy token should hold `metrics:write`, `logs:write`,
  `traces:write` and nothing else.
- OTLP export is HTTPS with Basic auth.
- Rotating the token means re-running the setup script for both environments; it
  is tracked as row 16a of the remediation backlog in
  [`docs/compliance/soc2-gap.md`](compliance/soc2-gap.md) — an open item, not a solved one.
