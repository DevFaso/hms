# Grafana Cloud Observability Setup

HMS uses the **OpenTelemetry Java Agent** for auto-instrumented traces, metrics,
and logs, exported to **Grafana Cloud** via OTLP. The agent is bundled in the
Docker image and activates only when `OTEL_EXPORTER_OTLP_ENDPOINT` is set.

## Architecture

```
┌──────────────┐    OTLP/gRPC     ┌─────────────────────────────┐
│  HMS Backend │ ───────────────► │  Grafana Cloud OTLP Gateway │
│  (Railway)   │                  │  otlp-gateway-prod-…        │
│              │                  ├─────────────────────────────┤
│  OTEL Agent  │                  │  Tempo   → Traces           │
│  (javaagent) │                  │  Mimir   → Metrics          │
│              │                  │  Loki    → Logs             │
└──────────────┘                  └─────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  Grafana Cloud Synthetic Monitoring                          │
│  DNS check: hms.dev.bitnesttechs.com every 5 min             │
│  Probe: US East (synthetic-monitoring-grpc-us-east-0)        │
└──────────────────────────────────────────────────────────────┘
```

## Railway Environment Variables

Set these in each Railway service environment (dev / uat / prod):

| Variable | Value | Notes |
|----------|-------|-------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `https://otlp-gateway-prod-us-east-2.grafana.net/otlp` | OTLP gateway URL |
| `OTEL_EXPORTER_OTLP_HEADERS` | `Authorization=Basic <base64>` | `base64(instanceId:accessToken)` |
| `OTEL_SERVICE_NAME` | `hms-backend` | Service name in Grafana |
| `OTEL_RESOURCE_ATTRIBUTES` | `deployment.environment=dev,service.namespace=hms` | Change `dev` per env |
| `OTEL_METRICS_ENABLED` | `true` | Enables Micrometer OTLP export |
| `GRAFANA_OTLP_AUTH_HEADER` | `Basic <base64>` | Same credentials for Micrometer |

### Generating the auth header

```bash
# Instance ID: 1404501 (from Grafana Cloud → OTLP connection)
# Token: your glc_* access policy token
echo -n "1404501:<your-glc-token>" | base64
```

Use the resulting base64 string as the `<base64>` placeholder above.

## What Gets Exported

### Traces (→ Grafana Tempo)
- All HTTP requests (Spring MVC)
- JDBC / Hibernate queries
- Kafka producer/consumer
- External HTTP calls (RestTemplate / WebClient)
- Custom spans (if any)

### Metrics (→ Grafana Mimir)
- JVM memory, GC, threads (via Micrometer)
- HTTP request rate, latency (p50/p95/p99), error rate
- Hikari connection pool stats
- Tomcat thread pool
- Custom business metrics (if registered)

### Logs (→ Grafana Loki)
- All application logs (correlated with trace IDs automatically)
- The OTEL agent injects `trace_id` and `span_id` into MDC

## Synthetic Monitoring

A DNS check is configured for `hms.dev.bitnesttechs.com`:
- **Probe**: US East (`synthetic-monitoring-grpc-us-east-0.grafana.net:443`)
- **Frequency**: 5 minutes
- **Protocol**: DNS/UDP → `dns.google:53`
- **Expected**: `NOERROR` response code

## Local Development

Local dev does **not** use Grafana Cloud. The existing `docker-compose.yml`
Prometheus + Grafana stack continues to work for local metrics.

To test OTLP export locally, set the environment variables before starting:

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp-gateway-prod-us-east-2.grafana.net/otlp
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <base64>"
export OTEL_SERVICE_NAME=hms-backend-local
export OTEL_RESOURCE_ATTRIBUTES=deployment.environment=local,service.namespace=hms

./gradlew :hospital-core:bootRun
```

## Dashboards

After data flows into Grafana Cloud, import or create dashboards for:
1. **Application Performance** — request rate, latency percentiles, error rate
2. **JVM Health** — heap usage, GC pauses, thread count
3. **Database** — query latency, connection pool utilization
4. **Business KPIs** — patient operations, appointments, encounters

The existing dashboard JSONs in `grafana/dashboards/` can be imported into
Grafana Cloud via the Grafana UI (Dashboards → Import).

## Security Notes

- **Never commit tokens** — all credentials are set as Railway env vars
- The `prometheus` actuator endpoint is exposed but requires no auth since
  Railway services are not publicly accessible on the management port
- OTLP export uses HTTPS with Basic auth
- The access policy token (`glc_*`) should have minimal scopes: `metrics:write`,
  `logs:write`, `traces:write`, `profiles:write`
