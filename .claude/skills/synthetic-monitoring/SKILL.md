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
  `observability` profile. Binds 9115; mounts `grafana/blackbox.yml`.
- Four probe modules in `grafana/blackbox.yml`:
  - `http_health_2xx` — Spring Boot Actuator (liveness + readiness)
  - `http_smart_2xx` — longer timeout for `.well-known/smart-configuration`
  - `http_fhir_metadata_2xx` — accepts `application/fhir+json` + json
  - `http_cds_discovery_2xx` — `/cds-services` discovery
- Four scrape jobs in `grafana/prometheus.yml` (one per surface). The
  standard relabel idiom: `__address__` → `__param_target` →
  `instance`; per-job external labels `application=hms`,
  `service=synthetic`, `geo=local`.
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
and would flood pages without aggregation. The alert math averages
`probe_success` over `probe_target × geo` and only pages on the
aggregate — preserve that when extending. If you genuinely need a
per-geo alert (e.g. ECOWAS region degraded specifically), add it as
a separate rule with a longer `for:` (≥ 30 min) and severity=ticket.

## TLS validation

Modules ship with `insecure_skip_verify: false`. **Don't** flip it on
to silence a cert-chain failure in production — fix the cert chain.
The only acceptable carve-out is the local docker-compose probe path
(hitting `http://` URLs), which never invokes TLS validation.

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
