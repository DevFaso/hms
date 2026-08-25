# Pharmacy Dispense — Performance Baseline

> Roadmap row: **v1.0 / Pharmacy / T-72 perf baseline** ([`docs/roadmap.csv`](../roadmap.csv))
>
> What this is: a recorded, reproducible measurement of how the dispense path
> behaves under a realistic peak load (50 concurrent virtual pharmacists). The
> numbers below are the floor we promise — any future change that regresses them
> by more than 15 % must be discussed in the PR before merge.

## Headline numbers (target)

| Operation | Endpoint | p95 target | Why this number |
| --- | --- | --- | --- |
| Browse the work queue | `GET /pharmacy/dispense/work-queue` | **< 800 ms** | Pharmacist refreshes this constantly; perceptible UI lag begins around 1 s. |
| Open one prescription | `GET /pharmacy/dispense/prescription/{id}` | **< 800 ms** | Same UX justification — opening a card should feel instant. |
| Dispense (write) | `POST /pharmacy/dispense` | **< 1500 ms** | Includes stock decrement + `Dispense` insert + audit event emit; some headroom is fine. |
| HTTP error rate (overall) | — | **< 1 %** | A pharmacist losing one in a hundred clicks is unacceptable. |

These are server-side timings as reported by k6 (`http_req_duration`). Browser
RUM measured via Faro should land within ~50 ms of these on a healthy LAN.

## Load model

The baseline simulates the agreed peak of **50 concurrent dispensers per
hospital cluster**:

- 1 minute ramp 0 → 50 VUs
- 3 minutes hold @ 50 VUs (the actual measurement window)
- 30 second ramp down

Within that window three scenarios run in parallel, weighted to mirror what the
[Faro RUM dashboard](https://grafana.bitnesttechs.com/d/hms-pharmacy-rum) shows
during a typical morning shift:

| Scenario | Executor | VUs | Operation | Share |
| --- | --- | --- | --- | --- |
| `work_queue_browse` | ramping | 0 → 50 | `GET /pharmacy/dispense/work-queue` | ≈ 60 % |
| `get_dispense_by_id` | ramping | 0 → 50 | `GET /pharmacy/dispense/prescription/{id}` | ≈ 30 % |
| `post_dispense` | constant | 5 | `POST /pharmacy/dispense` | ≈ 10 % |

The write scenario is intentionally smaller (5 VUs) because each call mutates
stock — a 50-VU write storm would deplete the seeded lot in seconds and start
returning expected `400 insufficient stock` responses, which would be noise in
the latency distribution.

## Reproducing the baseline

### Local (against a `docker-compose up` backend)

```bash
brew install k6   # or apt-get install k6 / choco install k6
k6 run \
  -e BASE_URL=http://localhost:8081 \
  -e AUTH_TOKEN="$(./scripts/seed-keycloak.ps1 -PrintTokenFor pharmacist)" \
  -e PRESCRIPTION_ID=00000000-0000-0000-0000-000000000001 \
  -e PATIENT_ID=00000000-0000-0000-0000-000000000002 \
  -e PHARMACY_ID=00000000-0000-0000-0000-000000000003 \
  -e STOCK_LOT_ID=00000000-0000-0000-0000-000000000004 \
  -e MEDICATION_CATALOG_ITEM_ID=00000000-0000-0000-0000-000000000005 \
  scripts/perf/dispense-baseline.js
```

The IDs map to the seeded fixtures in
[`hospital-core/src/main/resources/db/migration`](../../hospital-core/src/main/resources/db/migration);
update them when the seed migration changes.

### Read-only smoke (no seeded fixtures)

If you don't have seeded IDs, omit them. The script auto-detects this and skips
the `post_dispense` scenario, running the two read scenarios only:

```bash
k6 run -e BASE_URL=https://api.dev.e-keneya.com \
       -e AUTH_TOKEN="$KC_PHARMACIST_JWT" \
       scripts/perf/dispense-baseline.js
```

This is what the manual `perf-baseline` GitHub Actions workflow runs against
dev — see [`.github/workflows/perf-baseline.yml`](../../.github/workflows/perf-baseline.yml).

### CI invocation

The workflow is `workflow_dispatch` only — it never runs on push, because environment
credentials must not be exposed in PR runs from forks. Trigger it from the
Actions tab and pass the environment (`dev` or `prod`) as input. Output:

- A one-line summary in the workflow log (`[perf-baseline] work_queue_p95=…`).
- The full k6 JSON summary uploaded as the `perf-baseline-summary` artifact.

## Recorded results

> Re-run after every infra change (DB upgrade, JVM flag tweak, Hikari pool
> resize). Append a row — never delete — so we can see drift.

| Date | Branch / commit | Environment | Work-queue p95 | Get p95 | Post p95 | HTTP fail % | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-05-10 | `feat/v1.0-finishing-rows-4-5-6-8` | UAT (initial) | _to be filled by first scheduled run_ | | | | First baseline. |

## What we deliberately do **not** measure here

- **Cold start** — k6 pre-warms with a 1-minute ramp; we want steady-state numbers.
  Cold-start is tracked separately in the Railway service health check.
- **End-to-end browser timing** — that is Faro RUM's job; this script is
  back-end / API-only.
- **Database internals** — slow query analysis lives in the
  [Grafana Postgres dashboard](https://grafana.bitnesttechs.com/d/hms-pg-slow);
  this script will surface the symptom (latency spike) but not the cause.

## Related runbooks

- [Grafana observability](../runbooks/grafana-observability.md) — where these
  metrics show up in dashboards.
- [Pharmacy runbook](../pharmacy-runbook.md) — operational context for the
  dispense workflow being measured.
