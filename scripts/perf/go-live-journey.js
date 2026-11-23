import http from "k6/http";
import { check, sleep } from "k6";

/** Scripts only: run by the dev acceptance owner, never automatically against a shared environment. */
export function configuration(lane) {
  return {
    scenarios: {
      [lane]: {
        executor: "ramping-vus",
        startVUs: 0,
        stages: [
          { duration: "30s", target: 10 },
          { duration: "2m", target: 10 },
          { duration: "30s", target: 0 },
        ],
        gracefulRampDown: "20s",
      },
    },
    thresholds: {
      http_req_failed: ["rate<0.01"],
      "http_req_duration{operation:read}": ["p(95)<1000"],
      ...(__ENV.ALLOW_SYNTHETIC_WRITES === "yes"
        ? { "http_req_duration{operation:write}": ["p(95)<2000"] }
        : {}),
    },
    discardResponseBodies: true,
  };
}

export function validateTarget(env) {
  const base = (env.BASE_URL || "").replace(/\/$/, "");
  // No default host, production host, redirect, query, credentials or arbitrary external destination.
  if (
    !/^https:\/\/dev\.e-keneya\.com\/api$|^http:\/\/(localhost|127\.0\.0\.1)(:[0-9]+)?\/api$/.test(
      base,
    )
  )
    throw new Error("Use an explicit localhost or approved dev API base URL.");
  if (!env.AUTH_TOKEN || !env.HOSPITAL_ID)
    throw new Error(
      "Provide a scoped test token and hospital id via the environment.",
    );
  if (!/^[0-9a-f-]{36}$/i.test(env.HOSPITAL_ID))
    throw new Error("Hospital id must be a fixture UUID.");
  if (
    env.ALLOW_SYNTHETIC_WRITES === "yes" &&
    (env.MOCK_GATEWAYS_CONFIRMED !== "yes" || !env.RUN_ID)
  )
    throw new Error(
      "Writes require mocked gateways confirmed by the operator and a unique run id.",
    );
  return base;
}

export function journey(lane, readPath, writePath, fixture) {
  const base = validateTarget(__ENV);
  const headers = {
    Authorization: `Bearer ${__ENV.AUTH_TOKEN}`,
    "X-Hospital-Id": __ENV.HOSPITAL_ID,
    "Content-Type": "application/json",
  };
  const response = http.get(`${base}${readPath}`, {
    headers,
    timeout: "15s",
    redirects: 0,
    tags: { lane, operation: "read", name: `${lane}:read` },
  });
  check(response, {
    "read succeeds": (r) => r.status >= 200 && r.status < 300,
  });
  if (__ENV.ALLOW_SYNTHETIC_WRITES === "yes") {
    if (!fixture)
      throw new Error("Provide a synthetic fixture file for writes.");
    const substitutions = {
      RUN_ID: __ENV.RUN_ID,
      VU: String(__VU),
      ITER: String(__ITER),
    };
    const body = fixture.replace(
      /\{\{(RUN_ID|VU|ITER)\}\}/g,
      (_, key) => substitutions[key],
    );
    const payload = JSON.parse(body); // reject an invalid fixture before any write
    if (lane === "billing" && payload.status !== "DRAFT")
      throw new Error("Billing load tests create draft invoices only.");
    // Spring requires the double-submit cookie even with Bearer authentication.
    // k6 retains this response cookie in the current virtual user's cookie jar.
    const bootstrap = http.get(`${base}/auth/csrf-token`, {
      headers,
      timeout: "15s",
      redirects: 0,
      tags: { lane, operation: "csrf", name: `${lane}:csrf` },
    });
    const csrf = bootstrap.cookies?.["XSRF-TOKEN"]?.[0]?.value;
    if (bootstrap.status < 200 || bootstrap.status >= 300 || !csrf)
      throw new Error("CSRF bootstrap failed; no synthetic write was sent.");
    const written = http.post(`${base}${writePath}`, body, {
      headers: { ...headers, "X-XSRF-TOKEN": csrf },
      timeout: "15s",
      redirects: 0,
      tags: { lane, operation: "write", name: `${lane}:write` },
    });
    check(written, {
      "synthetic draft write succeeds": (r) =>
        r.status >= 200 && r.status < 300,
    });
  }
  sleep(1);
}
