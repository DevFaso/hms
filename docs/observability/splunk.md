# Splunk HEC Log Shipping

Companion to [`grafana-observability.md`](../runbooks/grafana-observability.md). Splunk
receives **logs** (via HTTP Event Collector) for SIEM/SOC and audit-retention use cases;
**metrics** continue to flow through the existing OTel → Grafana Cloud pipeline. The two
destinations are additive and independently controlled.

## What ships to Splunk

Every Logback event from the HMS backend, formatted as a single JSON envelope per the
HEC `/services/collector/event` contract:

```json
{
  "time": 1715252400.123,
  "host": "hms-backend-prod-7c84f",
  "source": "hms-backend",
  "sourcetype": "spring-boot:json",
  "index": "hms_prod",
  "event": {
    "level": "INFO",
    "logger": "com.example.hms.appointment.AppointmentService",
    "thread": "http-nio-8081-exec-3",
    "message": "Appointment 2347 confirmed",
    "application": "hms",
    "environment": "prod",
    "mdc": {"traceId": "abc123", "spanId": "def456", "userId": "42"}
  }
}
```

The `mdc` block carries OpenTelemetry trace/span IDs (the OTel Java Agent populates MDC
automatically), which lets Splunk join log events back to traces in Grafana Tempo via the
`traceId` field.

## Architecture

```text
┌─────────────────┐    ┌──────────────────────┐    ┌──────────────────┐
│ Spring Boot     │ ─► │ Logback root logger  │ ─► │ CONSOLE appender │ ─► stdout (Loki via Alloy)
│ (HMS backend)   │    │                      │ ─► │ SPLUNK_HEC       │ ─► Splunk HEC
└─────────────────┘    └──────────────────────┘    └──────────────────┘
```

The `SPLUNK_HEC` appender is registered unconditionally; its
[`SplunkHecAppender`](../../hospital-core/src/main/java/com/example/hms/logging/SplunkHecAppender.java)
class silently no-ops when `app.observability.splunk.enabled=false`. That keeps a single
`logback-spring.xml` working in dev (no Splunk) and prod (Splunk on) without conditional
profiles.

## Enabling Splunk in an environment

1. Provision an HEC token in Splunk: **Settings → Data inputs → HTTP Event Collector → New
   Token**. Pin the token to the `hms_prod` (or `hms_uat`) index and the
   `spring-boot:json` sourcetype.
1. Set the following env vars on the Railway service for that environment:

   | Variable | Required | Notes |
   | --- | --- | --- |
   | `SPLUNK_HEC_ENABLED` | yes | `true` flips the appender on. |
   | `SPLUNK_HEC_URL` | yes | HTTPS only. App fails to boot on plain HTTP. |
   | `SPLUNK_HEC_TOKEN` | yes | The HEC token created in step 1. Treat as a secret. |
   | `SPLUNK_HEC_INDEX` | no | Defaults to `hms_prod` / `hms_uat` per profile. |
   | `SPLUNK_HEC_SOURCE` | no | Defaults to `hms-backend`. |
   | `SPLUNK_HEC_SOURCETYPE` | no | Defaults to `spring-boot:json`. |
   | `SPLUNK_HEC_HOST` | no | Defaults to the container hostname when blank. |

1. Roll the container. On startup the
   [`SplunkLoggingProperties`](../../hospital-core/src/main/java/com/example/hms/config/observability/SplunkLoggingProperties.java)
   bean validates the config; the app refuses to start when `enabled=true` but URL or token
   are missing, or when URL is not HTTPS.

## Verifying it works

```spl
index=hms_prod sourcetype="spring-boot:json" application=hms
| head 50
| table _time level logger message mdc.traceId
```

A populated table within ~30 seconds of the first request after deploy is the smoke
signal.

## Failure modes

| Symptom | Where to look |
| --- | --- |
| App fails to start with `IllegalStateException: hec.url is blank` | `SPLUNK_HEC_ENABLED=true` but URL/token unset on Railway. Either set them or flip enabled back to `false`. |
| App boots, no events in Splunk | Check the container logs for `Splunk HEC POST returned status …` lines emitted by the appender's status manager. 401/403 → token wrong or expired. 404 → URL missing the HEC port (`:8088`) or the wrong host. |
| Burst of warnings, then quiet | HEC backpressure or transient outage. The appender increments an internal failure counter and never throws; logs continue to console + Loki. |

## Local dev

Splunk is **off by default in every profile**. Local devs keep using the docker-compose
Loki + Grafana stack (see `docker-compose.yml`). To exercise the Splunk path locally, run a
disposable HEC mock and set the env vars from `.env`:

```bash
docker run -d --name splunk-mock -p 8088:8088 mocoso/splunk-hec-mock:latest
SPLUNK_HEC_ENABLED=true \
SPLUNK_HEC_URL=https://localhost:8088 \
SPLUNK_HEC_TOKEN=any-token \
./gradlew :hospital-core:bootRun
```

(For the HTTPS validation to allow a local self-signed cert, point at the mock via SSH
tunnel from a stage Splunk Cloud trial instead — easier than wrangling a local TLS stack.)

## Why not the official `splunk-library-javalogging`?

It is not published to Maven Central — Splunk distributes it from GitHub releases. Pulling
in a non-Central artifact would force every CI runner to authenticate against a separate
Maven repo and would couple us to Splunk's release cadence. The
[`SplunkHecAppender`](../../hospital-core/src/main/java/com/example/hms/logging/SplunkHecAppender.java)
in this repo is &lt;200 LOC, uses only JDK 21's built-in `HttpClient`, and is fully unit-
tested with a mock. If we ever need batching, retries, or async flushing, we extend it
in-place.
