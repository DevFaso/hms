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
   Token**. Pin the token to the `hms_prod` (or `hms_dev`) index and the
   `spring-boot:json` sourcetype.
1. Set the following env vars on the Railway service for that environment:

   | Variable | Required | Notes |
   | --- | --- | --- |
   | `SPLUNK_HEC_ENABLED` | yes | `true` flips the appender on. |
   | `SPLUNK_HEC_URL` | yes | HTTPS only. App fails to boot on plain HTTP. |
   | `SPLUNK_HEC_TOKEN` | yes | The HEC token created in step 1. Treat as a secret. |
   | `SPLUNK_HEC_INDEX` | no | Defaults to `hms_prod` / `hms_dev` per profile. |
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

`logback-spring.xml` registers an `OnConsoleStatusListener`, so the appender's
internal warnings (HEC unreachable, 4xx/5xx, etc.) are printed to **stdout** alongside
regular application logs. They are tagged `WARN in [SplunkHecAppender]` and won't be
indexed by Splunk itself (the listener is outside the logging pipeline by design — it
cannot recurse back into the HEC appender). Container log shippers that capture both
stdout and stderr (Alloy, Loki driver, Datadog Agent, etc.) will pick these lines up;
shippers that capture only stderr will not.

| Symptom | Where to look |
| --- | --- |
| App fails to start with `IllegalStateException: hec.url is blank` | `SPLUNK_HEC_ENABLED=true` but URL/token unset on Railway. Either set them or flip enabled back to `false`. |
| App fails to start with `must be HTTPS` | URL begins with `http://`. Either fix the URL or set `SPLUNK_HEC_ALLOW_INSECURE_URL=true` (local dev only — never set this in hosted dev/prod). |
| App boots, no events in Splunk | Check container `stdout` for `WARN in [SplunkHecAppender]` lines from the status listener. `401`/`403` → token wrong or expired; `404` → URL missing the HEC port (`:8088`) or the wrong host; connect-timeout → network ACL between Railway and the HEC. |
| Burst of warnings, then quiet | HEC backpressure or transient outage. The appender increments an internal failure counter and never throws; console + Loki paths are unaffected. |

## Local dev

Splunk is **off by default in every profile**. Local devs keep using the docker-compose
Loki + Grafana stack (see `docker-compose.yml`). The HTTPS check in
`SplunkLoggingProperties.validateWhenEnabled` will reject a plain-HTTP URL on boot, so a
bare HTTP mock is not enough. Pick whichever local-dev path matches what you're trying
to exercise:

### Option 1 — The unit tests already cover the wire format (fastest)

`SplunkHecAppenderTest` verifies URI, headers, body shape, MDC + exception handling, and
the failure paths with a stubbed `HttpClient`. For everything except "is my real Splunk
token valid", the unit tests are the right harness — no Docker needed.

### Option 2 — `SPLUNK_HEC_ALLOW_INSECURE_URL=true` against an HTTP mock

A local-only escape hatch lets the URL be plain HTTP. Use only on a developer machine —
Railway env vars MUST NOT set this in hosted dev/prod.

```bash
docker run -d --name splunk-mock -p 8088:8088 mocoso/splunk-hec-mock:latest
SPLUNK_HEC_ENABLED=true \
SPLUNK_HEC_ALLOW_INSECURE_URL=true \
SPLUNK_HEC_URL=http://localhost:8088 \
SPLUNK_HEC_TOKEN=any-token \
./gradlew :hospital-core:bootRun
```

### Option 3 — Real HTTPS via a Caddy reverse proxy in front of the mock

If you specifically want to exercise the HTTPS path end-to-end (cert validation, TLS
handshake), put Caddy in front of the mock:

```bash
docker network create splunk-local
docker run -d --network splunk-local --name splunk-mock mocoso/splunk-hec-mock:latest
docker run -d --network splunk-local --name splunk-tls -p 8443:443 \
  caddy:2 caddy reverse-proxy --from splunk-local.localhost --to splunk-mock:8088
SPLUNK_HEC_ENABLED=true \
SPLUNK_HEC_URL=https://splunk-local.localhost:8443 \
SPLUNK_HEC_TOKEN=any-token \
./gradlew :hospital-core:bootRun
```

Caddy issues a self-signed cert under `*.localhost`; you may need to add it to your
trust store (`security add-trusted-cert` on macOS) for the JDK to validate it. For most
dev work Option 1 or 2 is enough.

### Option 4 — Splunk Cloud free trial

Sign up for a 14-day trial at `splunk.com/free-trials` and use its real HTTPS HEC. This
is the only option that catches token-permission and index-mapping mistakes.

## Why not the official `splunk-library-javalogging`?

It is not published to Maven Central — Splunk distributes it from GitHub releases. Pulling
in a non-Central artifact would force every CI runner to authenticate against a separate
Maven repo and would couple us to Splunk's release cadence. The
[`SplunkHecAppender`](../../hospital-core/src/main/java/com/example/hms/logging/SplunkHecAppender.java)
in this repo is &lt;200 LOC, uses only JDK 21's built-in `HttpClient`, and is fully unit-
tested with a mock. If we ever need batching, retries, or async flushing, we extend it
in-place.
