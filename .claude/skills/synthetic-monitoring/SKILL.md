---
name: synthetic-monitoring
description: Use when adding or modifying external HTTP probes against HMS public surfaces, Blackbox-exporter probe modules, Prometheus scrape jobs for synthetic targets, or the synthetic-probe alert group. Triggers on changes under grafana/ (prometheus.yml, blackbox.yml, rules/), the observability docker-compose profile, or docs/runbooks/synthetic-monitoring.md.
---

# Synthetic monitoring

HMS runs external HTTP probes against its public surfaces via
Blackbox-exporter (roadmap row 43 foundation pass shipped on
`feat/v2.0-synthetic-monitoring`). The pattern is shared with the
local observability stack — Prometheus scrapes the exporter, alerts
in `grafana/rules/alerts.yml` page on > 10% failure for 5 min.

## What the foundation pass shipped

- Blackbox-exporter service in `docker-compose.yml` under the existing
  `observability` profile. The row-43 foundation pass used
  `9115:9115` — see "Localhost-only port binding" below for the P0
  correction. Mounts `grafana/blackbox.yml`.
- Four probe modules in `grafana/blackbox.yml`:
  - `http_health_2xx` — Spring Boot Actuator (see "Actuator probe
    targets must be enabled" below).
  - `http_smart_2xx` — longer timeout for `.well-known/smart-configuration`
  - `http_fhir_metadata_2xx` — accepts `application/fhir+json` + json
  - `http_cds_discovery_2xx` — `/cds-services` discovery
- Four scrape jobs in `grafana/prometheus.yml` (one per surface). The
  standard relabel idiom: `__address__` → `__param_target` →
  `instance`; per-job **target labels** (NOT Prometheus
  `global.external_labels` — those are a different concept applied to
  `remote_write` / federation) `application=hms`, `service=synthetic`,
  `geo=local`.
- Three alert rules in `grafana/rules/alerts.yml` group `hms.synthetic`:
  - `HmsSyntheticProbeFailureRate` — `> 10% failure for 5m` (the
    deliverable target, severity=page).
  - `HmsSyntheticProbeAllGeosFailing` — every vantage point at 0 for
    2m (hard-outage signal).
  - `HmsSyntheticProbeLatencyHigh` — `probe_duration_seconds > 5`
    for 10m (severity=ticket).

## What's deferred (operational, not code)

The deliverable says "probes from 3 geos". The PR lands single-geo
local probes so the alert math is identical regardless of cardinality.
Multi-geo rollout has two options — pick before flipping row 43 to
`completed`:

- **Option A — Blackbox in 3 cloud regions.** Three independent
  Blackbox instances (e.g. AWS eu-west-1, AWS us-east-1, OVH-Africa),
  each with `external_labels: { geo: <region> }` override + a
  shared remote_write to Grafana Cloud Mimir. The
  `avg by (probe_target)` aggregation in
  `HmsSyntheticProbeFailureRate` absorbs single-region misbehavior.
- **Option B — Grafana k6 cloud.** Reuse the existing
  `scripts/perf/dispense-baseline.js` pattern (already wired into
  `.github/workflows/perf-baseline.yml`) and schedule a smaller
  `synthetic-canary.js` from k6 cloud's three default load zones
  (`amazon:us:ashburn`, `amazon:eu:dublin`,
  `amazon:af:cape-town`). The k6 cloud → Grafana metrics integration
  writes `probe_success_*` series under the same `application=hms,
  service=synthetic` labels; the same alert rule fires.

Option A is closer to local-stack ergonomics; Option B is closer to
the existing perf-baseline CI wiring.

## Adding a new probe target

1. Pick a module (or add one to `grafana/blackbox.yml`). Module names
   are namespaced by HTTP semantics — accept header, expected status
   code, timeout. Reuse before adding.
2. Add a `blackbox_<surface>` scrape job in `grafana/prometheus.yml`.
   Copy the existing relabel block — `instance` MUST carry the human-
   readable target URL, not the `blackbox-exporter:9115` address.
3. The new target inherits the existing
   `HmsSyntheticProbeFailureRate` and `HmsSyntheticProbeLatencyHigh`
   alerts via the `application=hms,service=synthetic` label set.
   No alert-rule edit is required unless you need a per-target
   threshold.

## Don't alert per-geo

A single misbehaving regional ISP routinely posts > 0% probe failure
and would flood pages without aggregation. The alert math uses
`avg by (probe_target)`, which **aggregates away** `geo` — so a
single-region failure does not alert on its own. Preserve that when
extending. If you genuinely need a per-geo alert (e.g. ECOWAS region
degraded specifically), add it as a separate rule with a longer
`for:` (≥ 30 min) and severity=ticket.

## TLS validation

Modules ship with `http.tls_config.insecure_skip_verify: false`
(in blackbox-exporter the option lives under each module's
`http.tls_config`, not at the module top level — spelling out the
path here so a future reader can grep for the right key). **Don't**
flip it on to silence a cert-chain failure in production — fix the
cert chain. The only acceptable carve-out is the local
docker-compose probe path (hitting `http://` URLs), which never
invokes TLS validation.

## Localhost-only port binding (P0 follow-on)

The blackbox-exporter `/probe?target=...` endpoint can be coerced into
making the container issue HTTP requests to **any** target — a
classic SSRF / network-scanning primitive on the LAN. Bind 9115 to
loopback only for the local stack:

```yaml
ports:
  - "127.0.0.1:9115:9115"   # not "9115:9115"
```

The row-43 foundation pass shipped with the wide-open binding and was
Copilot-flagged (PR #342); flip it before any contributor brings the
observability profile up on a shared Wi-Fi network.

## Actuator probe targets must be enabled

`grafana/prometheus.yml` probes `/api/actuator/health/liveness` and
`/api/actuator/health/readiness`. Outside Kubernetes those endpoints
are **not exposed by default** — Spring Boot only mounts them when
`management.endpoint.health.probes.enabled=true`. The row-43
foundation pass set only `add-additional-paths=true`, which exposes
the paths IF probes are also enabled. Either:

- Set `management.endpoint.health.probes.enabled=true` in
  `application.properties` so the existing scrape jobs work outside
  Kubernetes, OR
- Repoint the probes at `/api/actuator/health` (the always-on
  composite check) and accept the loss of liveness/readiness
  separation.

Caught in PR #342 Copilot review (High severity).

## Grafana-provisioning alert mirror

The local stack routes alerts through **Grafana Unified Alerting**,
not Prometheus Alertmanager. The notification policies live in
`grafana/provisioning/alerting/` — see `grafana/README.md` lines
110-114. Alerts added only to `grafana/rules/alerts.yml` evaluate in
Prometheus but never follow the existing Grafana notification routes,
so a paged alert won't make it to PagerDuty / Slack / email.

When adding a Prometheus rule, **also mirror it** in the Grafana
provisioning directory so the notification policy fires. The row-43
foundation pass shipped only the Prometheus rule and was
Copilot-flagged (PR #342, High severity) — fix this before declaring
the row complete.

## Alert annotation discipline

- `runbook_url` fragments (e.g. `#hmssyntheticprobefailurerate`) MUST
  match an actual heading in the linked runbook. The row-43
  foundation pass used invented anchors; responders following the
  alert link land on the page top instead of alert-specific guidance.
  Either add matching headings to the runbook or drop the fragment.
- `description:` lines that reference a "Synthetic Probes dashboard"
  must point to a real dashboard. The row-43 pass references a
  dashboard that doesn't exist; add the dashboard or reword the
  annotation to reference the metrics directly
  (`probe_success`, `probe_duration_seconds`).
- Caught in PR #342 Copilot review.

## Container name consistency

`docker-compose.yml` defines `container_name: hms-blackbox-exporter`,
but the row-43 foundation pass's `grafana/blackbox.yml` header
comment referred to `hms-blackbox`. Operators copy-pasting the
container-name from the comment for `docker logs` / `docker exec`
will hit "no such container". Keep references consistent with the
compose-defined name. Caught in PR #342 Copilot review.

## PHI inventory carve-out

Synthetic probes never carry patient data and are exempt from the
PHI-encryption controls in `docs/compliance/phi-inventory.md`. They
are still subject to the standard audit/access logging that applies
to any HMS endpoint — Actuator + Alloy capture every probe response
just like any other request.

## Reference files

- `docker-compose.yml` — `blackbox-exporter` service (profile=observability)
- `grafana/blackbox.yml` — probe modules
- `grafana/prometheus.yml` — `blackbox_*` scrape jobs
- `grafana/rules/alerts.yml` — `hms.synthetic` alert group
- `docs/runbooks/synthetic-monitoring.md` — operational runbook
- `docs/runbooks/disaster-recovery.md` — row 9 (the dependency that
  gates row 43)
- `scripts/perf/dispense-baseline.js` — existing k6 baseline (related
  but distinct from synthetic monitoring)

## Roadmap context

- Row 9: DR runbook — dependency for row 43 (shipped, see
  `docs/runbooks/disaster-recovery.md`).
- Row 43: synthetic monitoring — foundation pass shipped on
  `feat/v2.0-synthetic-monitoring`. Stays `started` until the
  multi-geo rollout lands and an alert dry-run succeeds against the
  chosen vantage points.
