// scripts/perf/dispense-baseline.js
//
// k6 baseline for the pharmacy dispense path. Roadmap row #6 (T-72).
//
// Goal: with 50 concurrent virtual users hitting the dispense workflow on a
// representative dataset, the p95 server latency for the read path stays under
// 800 ms and the write path under 1500 ms, with < 1% HTTP errors. Numbers are
// recorded in docs/observability/performance-baseline.md so future drift can be
// detected.
//
// Why three scenarios instead of one: a real pharmacist's day is overwhelmingly
// reads (browse the queue, open an order) with the occasional write (dispense,
// cancel). A pure write-only test would over-stress the DB writer and under-
// stress the query plans we actually care about.
//
// How to run (locally against a seeded UAT):
//   k6 run \
//     -e BASE_URL=https://api.hms.uat.bitnesttechs.com \
//     -e AUTH_TOKEN="$KC_PHARMACIST_JWT" \
//     -e PRESCRIPTION_ID=00000000-0000-0000-0000-000000000001 \
//     -e PATIENT_ID=00000000-0000-0000-0000-000000000002 \
//     -e PHARMACY_ID=00000000-0000-0000-0000-000000000003 \
//     -e STOCK_LOT_ID=00000000-0000-0000-0000-000000000004 \
//     -e MEDICATION_CATALOG_ITEM_ID=00000000-0000-0000-0000-000000000005 \
//     scripts/perf/dispense-baseline.js
//
// In CI the script is invoked from .github/workflows/perf-baseline.yml on
// workflow_dispatch — never on push, because UAT credentials must not leak
// into PR runs.

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// ─── Config ────────────────────────────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const AUTH_TOKEN = __ENV.AUTH_TOKEN || '';
// Seeded fixture IDs — set in the perf-baseline workflow's UAT secrets.
// When unset the write scenario is skipped so the script still runs as a
// read-only smoke test against an unseeded environment.
const PRESCRIPTION_ID = __ENV.PRESCRIPTION_ID || '';
const PATIENT_ID = __ENV.PATIENT_ID || '';
const PHARMACY_ID = __ENV.PHARMACY_ID || '';
const STOCK_LOT_ID = __ENV.STOCK_LOT_ID || '';
const MEDICATION_CATALOG_ITEM_ID = __ENV.MEDICATION_CATALOG_ITEM_ID || '';

const HEADERS = {
  'Content-Type': 'application/json',
  Accept: 'application/json',
  ...(AUTH_TOKEN ? { Authorization: `Bearer ${AUTH_TOKEN}` } : {}),
};

const writeScenarioEnabled = Boolean(
  PRESCRIPTION_ID && PATIENT_ID && PHARMACY_ID && STOCK_LOT_ID && MEDICATION_CATALOG_ITEM_ID,
);

// ─── Custom metrics ────────────────────────────────────────────────────────
// Per-endpoint trends so the threshold report tells us which call regressed.
const workQueueLatency = new Trend('dispense_workqueue_latency_ms', true);
const getDispenseLatency = new Trend('dispense_get_latency_ms', true);
const postDispenseLatency = new Trend('dispense_post_latency_ms', true);
const dispenseErrors = new Rate('dispense_error_rate');

// ─── Scenarios + thresholds ────────────────────────────────────────────────
// Ramp 0 → 50 over 1 min, hold 3 min, ramp down — matches the agreed peak of
// 50 concurrent dispensers per hospital cluster.
const rampStages = [
  { duration: '1m', target: 50 },
  { duration: '3m', target: 50 },
  { duration: '30s', target: 0 },
];

export const options = {
  scenarios: {
    work_queue_browse: {
      executor: 'ramping-vus',
      stages: rampStages,
      gracefulRampDown: '15s',
      startVUs: 0,
      exec: 'browseWorkQueue',
      tags: { scenario: 'work_queue_browse' },
    },
    get_dispense_by_id: {
      executor: 'ramping-vus',
      stages: rampStages,
      gracefulRampDown: '15s',
      startVUs: 0,
      exec: 'getDispenseById',
      tags: { scenario: 'get_dispense_by_id' },
    },
    ...(writeScenarioEnabled
      ? {
          post_dispense: {
            executor: 'constant-vus',
            vus: 5,
            duration: '4m30s',
            exec: 'postDispense',
            tags: { scenario: 'post_dispense' },
          },
        }
      : {}),
  },
  thresholds: {
    // Read path — agreed target.
    'dispense_workqueue_latency_ms': ['p(95)<800'],
    'dispense_get_latency_ms': ['p(95)<800'],
    // Write path — wider, includes stock decrement + audit emission.
    ...(writeScenarioEnabled ? { 'dispense_post_latency_ms': ['p(95)<1500'] } : {}),
    // Overall hygiene.
    http_req_failed: ['rate<0.01'],
    dispense_error_rate: ['rate<0.01'],
  },
};

// ─── Scenario implementations ─────────────────────────────────────────────
export function browseWorkQueue() {
  group('GET /pharmacy/dispense/work-queue', () => {
    const res = http.get(`${BASE_URL}/pharmacy/dispense/work-queue?page=0&size=20`, {
      headers: HEADERS,
      tags: { endpoint: 'work_queue' },
    });
    workQueueLatency.add(res.timings.duration);
    const ok = check(res, {
      'work-queue 2xx': (r) => r.status >= 200 && r.status < 300,
    });
    if (!ok) dispenseErrors.add(1);
  });
  sleep(1);
}

export function getDispenseById() {
  // We re-use PRESCRIPTION_ID as the target for the listByPrescription endpoint
  // when no specific dispense ID has been seeded. This matches what the UI does
  // when a pharmacist reopens an in-flight order.
  if (!PRESCRIPTION_ID) {
    sleep(1);
    return;
  }
  group('GET /pharmacy/dispense/prescription/{id}', () => {
    const res = http.get(
      `${BASE_URL}/pharmacy/dispense/prescription/${PRESCRIPTION_ID}?page=0&size=20`,
      { headers: HEADERS, tags: { endpoint: 'get_by_prescription' } },
    );
    getDispenseLatency.add(res.timings.duration);
    const ok = check(res, {
      'list-by-prescription 2xx': (r) => r.status >= 200 && r.status < 300,
    });
    if (!ok) dispenseErrors.add(1);
  });
  sleep(1);
}

export function postDispense() {
  group('POST /pharmacy/dispense', () => {
    const body = JSON.stringify({
      prescriptionId: PRESCRIPTION_ID,
      patientId: PATIENT_ID,
      pharmacyId: PHARMACY_ID,
      stockLotId: STOCK_LOT_ID,
      medicationCatalogItemId: MEDICATION_CATALOG_ITEM_ID,
      quantityDispensed: 1,
      // Tiny notes string keeps the row small — we are measuring infra, not text.
      notes: 'k6-baseline',
    });
    const res = http.post(`${BASE_URL}/pharmacy/dispense`, body, {
      headers: HEADERS,
      tags: { endpoint: 'post_dispense' },
    });
    postDispenseLatency.add(res.timings.duration);
    // 201 created is the happy path. 400 for "insufficient stock" is expected
    // once the seeded lot is depleted, so we count anything else as an error.
    const acceptableStatus = res.status === 201 || res.status === 400;
    const ok = check(res, {
      'post-dispense expected status': () => acceptableStatus,
    });
    if (!ok) dispenseErrors.add(1);
  });
  sleep(2);
}

// k6 calls this once at the end. Emits a one-line summary so the workflow log
// is grep-friendly without needing to parse JSON.
export function handleSummary(data) {
  const checks = data.metrics.checks?.values?.rate ?? 0;
  const httpFailRate = data.metrics.http_req_failed?.values?.rate ?? 0;
  const wqp95 = data.metrics.dispense_workqueue_latency_ms?.values?.['p(95)'] ?? 0;
  const getp95 = data.metrics.dispense_get_latency_ms?.values?.['p(95)'] ?? 0;
  const postp95 = data.metrics.dispense_post_latency_ms?.values?.['p(95)'] ?? 0;
  const summary =
    `\n[perf-baseline]` +
    ` checks=${(checks * 100).toFixed(2)}%` +
    ` http_fail=${(httpFailRate * 100).toFixed(2)}%` +
    ` work_queue_p95=${wqp95.toFixed(0)}ms` +
    ` get_p95=${getp95.toFixed(0)}ms` +
    ` post_p95=${postp95.toFixed(0)}ms\n`;
  return {
    stdout: summary,
    'perf-baseline-summary.json': JSON.stringify(data, null, 2),
  };
}
