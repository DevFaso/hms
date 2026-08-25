// scripts/perf/synthetic-canary-k6.js
//
// Roadmap row 43 follow-on — Option B: k6 cloud synthetic canary
// from three load zones. Companion to grafana/prometheus-multigeo.example.yml
// (Option A) and docs/runbooks/synthetic-monitoring.md.
//
// Distinct from scripts/perf/dispense-baseline.js — that script is the
// performance baseline (50 VUs, p95 thresholds, write paths against
// seeded UAT data). This script is a LOW-traffic external availability
// canary: 1 VU per zone, hitting only the public surfaces a real
// uptime checker would hit (no auth, no PHI).
//
// How it runs in k6 Cloud:
//   k6 cloud --vus 1 --duration 5m \
//     -e HMS_PUBLIC_BASE_URL=https://api.e-keneya.com \
//     scripts/perf/synthetic-canary-k6.js
//
// In the k6 Cloud project settings, schedule via the "Scheduled tests"
// surface or via the GitHub Action k6-io/action@v0.3.x. The cloud
// options block below selects three default zones — Ashburn (US-East),
// Dublin (EU-West), Cape Town (AF-South). The Cape Town zone is the
// closest k6 cloud has to the ECOWAS footprint until row 39 lands.
//
// k6 Cloud emits `probe_success_*` time-series to the project's
// Grafana Cloud Mimir tenant under the same `application=hms,
// service=synthetic` labels Option A uses — so HmsSyntheticProbeFailureRate
// fires whether the geo signal arrives via Blackbox or via k6.
//
// What it does NOT cover (intentional):
//   - No authenticated endpoints. Synthetic canaries must not carry
//     credentials; rotating them across k6 cloud zones is a leak
//     surface that no monitoring value justifies.
//   - No write paths. Use scripts/perf/dispense-baseline.js for that.
//   - No PHI-bearing endpoints. All probes hit metadata / discovery /
//     health endpoints that return no patient data.

import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const HMS_PUBLIC_BASE_URL = __ENV.HMS_PUBLIC_BASE_URL || 'https://api.e-keneya.com';

// k6 Cloud reads the `ext.loadimpact.distribution` block to pick the
// load zones. Three zones, 1 VU each — the alert rule keys off the
// per-geo `probe_success` series, not on aggregate throughput.
export const options = {
  ext: {
    loadimpact: {
      projectID: parseInt(__ENV.K6_CLOUD_PROJECT_ID || '0', 10),
      name: 'HMS synthetic canary (row 43)',
      distribution: {
        'amazon:us:ashburn': { loadZone: 'amazon:us:ashburn', percent: 34 },
        'amazon:eu:dublin': { loadZone: 'amazon:eu:dublin', percent: 33 },
        'amazon:af:cape-town': { loadZone: 'amazon:af:cape-town', percent: 33 },
      },
      // Metadata so the Grafana panel filters cleanly.
      apm: [],
      note: 'Row 43 — multi-geo availability canary. See docs/runbooks/synthetic-monitoring.md.',
    },
  },
  // 1 VU per zone, ramping holds for 5 minutes per scheduled run.
  // Increase via the cloud scheduler, not here — local invocations
  // shouldn't auto-scale.
  vus: 3,
  duration: '5m',
  // Match the deliverable target: > 10 % probe failure for 5 minutes
  // pages on-call. We mirror that as a k6 threshold so the cloud run
  // itself fails when the probe success rate dips below 90 %.
  thresholds: {
    'probe_success': ['rate>0.9'],
    'http_req_failed': ['rate<0.1'],
    'http_req_duration{probe:liveness}': ['p(95)<2000'],
    'http_req_duration{probe:readiness}': ['p(95)<2000'],
    'http_req_duration{probe:fhir_metadata}': ['p(95)<5000'],
    'http_req_duration{probe:smart_config}': ['p(95)<5000'],
    'http_req_duration{probe:cds_discovery}': ['p(95)<5000'],
  },
};

// Custom metric that mirrors Blackbox's probe_success — k6 Cloud
// remote-writes this to Grafana Cloud Mimir alongside the built-in
// http_req_* metrics so the HmsSyntheticProbeFailureRate alert
// evaluates the same way regardless of probe source.
const probeSuccess = new Rate('probe_success');
const probeDuration = new Trend('probe_duration_seconds');

// Each probe target mirrors a grafana/prometheus.yml scrape job. The
// `probe` tag on http.get() is what the threshold block keys off —
// keep the tag values stable; renaming them silently degrades the
// per-probe p95 visibility in k6 cloud.
const PROBES = [
  { name: 'liveness', path: '/api/actuator/health/liveness', maxLatencyMs: 2000 },
  { name: 'readiness', path: '/api/actuator/health/readiness', maxLatencyMs: 2000 },
  { name: 'fhir_metadata', path: '/api/fhir/metadata', maxLatencyMs: 5000 },
  { name: 'smart_config', path: '/api/fhir/.well-known/smart-configuration', maxLatencyMs: 5000 },
  { name: 'cds_discovery', path: '/api/cds-services', maxLatencyMs: 5000 },
];

export default function () {
  for (const probe of PROBES) {
    const url = `${HMS_PUBLIC_BASE_URL}${probe.path}`;
    const res = http.get(url, {
      tags: { probe: probe.name },
      headers: {
        Accept: probe.name === 'fhir_metadata'
          ? 'application/fhir+json,application/json;q=0.8'
          : 'application/json',
        'User-Agent': 'hms-k6-canary/synthetic',
      },
      timeout: '15s',
    });

    const passed = check(res, {
      [`${probe.name}: status is 200`]: (r) => r.status === 200,
      [`${probe.name}: under latency budget`]: (r) => r.timings.duration < probe.maxLatencyMs,
    });

    probeSuccess.add(passed ? 1 : 0, { probe: probe.name });
    // duration_seconds keeps unit parity with Blackbox's
    // probe_duration_seconds; k6's r.timings.duration is in ms.
    probeDuration.add(res.timings.duration / 1000, { probe: probe.name });
  }
}

// k6 Cloud surfaces summary stats per-zone via the project dashboard;
// no custom handleSummary is needed. If running locally for smoke,
// the default text summary prints per-probe p95 + success rate.
