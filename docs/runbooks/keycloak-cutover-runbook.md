# Runbook — KC-5 backend cutover (`app.auth.oidc.required=true`)

> Companion to [keycloak-migration-runbook.md](keycloak-migration-runbook.md)
> (KC-4, user import) and the
> [docs/keycloak-implementation-gaps.md](../keycloak-implementation-gaps.md)
> Phase 3 plan. Executed by the backend on-call during the announced
> maintenance window.

## Summary

Flip the backend feature gate `app.auth.oidc.required` from `false`
to `true` so legacy `/api/auth/login` and `/api/auth/token/refresh`
respond **HTTP 410 Gone** with the runbook message. After this flip,
the only path to a session is the SSO button on portal / Android / iOS
against Keycloak. Existing legacy access tokens stay valid until they
expire (≤ 15 min) — no in-flight session is killed.

## When to run

| Env | Trigger |
|---|---|
| **dev** | Anytime, to exercise the 410 path locally. |
| **uat** | After KC-4 migration is green (zero `failed`, zero unexpected `orphaned`) and the OIDC integration suite passes against the uat realm. Soak for ≥ 5 business days before promoting to prod. |
| **prod** | During the announced maintenance window, with ops + clinical leads on the call. Do **not** flip on a Friday. |

## Preconditions checklist

- [ ] KC-4 user migration completed in this environment, dry-run +
      live both green (see
      [keycloak-migration-runbook.md](keycloak-migration-runbook.md)).
- [ ] Spot-checked at least three migrated users — `UPDATE_PASSWORD`
      and (where applicable) `VERIFY_EMAIL` required actions present;
      `hospital_id` and `role_assignments` user attributes populated.
- [ ] `OidcResourceServerIntegrationTest` (4/4) and
      `AuthControllerOidcRequiredTest` (2/2) green against the
      env's realm:
      ```bash
      OIDC_ISSUER_URI=https://keycloak.<env>.example.com/realms/hms \
      OIDC_AUDIENCE=hms-backend \
      ./gradlew :hospital-core:test \
        --tests OidcResourceServerIntegrationTest \
        --tests AuthControllerOidcRequiredTest
      ```
- [ ] Portal `environment.<env>.ts` has `oidc.enabled = true` and the
      build deployed to this env carries that flag.
- [ ] Android / iOS releases for this env have
      `KEYCLOAK_SSO_ENABLED=true` (BuildConfig / scheme env var).
- [ ] Keycloak SMTP healthy in this env — send a test email and
      confirm receipt within 5 min.
- [ ] On-call rotation on the maintenance bridge with rollback
      authority.
- [ ] Comms sent to users (≥ 24 h ahead): SSO becomes the only login
      path at `<window-start>`; legacy form will return a "use Single
      Sign-On" message after that.

## Procedure

### 1. Pre-flip snapshot (T-0:30 to T-0)

Capture baseline metrics so post-flip deltas are interpretable:

- Last 24 h request count and 4xx ratio on `/api/auth/login` and
  `/api/auth/token/refresh` (Grafana dashboard
  `api-latency` → "Auth endpoints" panel).
- Active legacy sessions: count of valid access tokens in flight.
  Used to estimate the natural "drain" window (≤ 15 min after flip,
  capped by access-token TTL).
- Keycloak admin → **Sessions** count, per realm — gives the
  baseline for the post-flip ramp.

### 2. Flip the flag (T-0)

Two supported ways depending on how this env runs:

**a) Spring Cloud Config server**

Set `app.auth.oidc.required: true` in the env's profile, push, then
trigger a `/actuator/refresh` on each backend node:

```bash
for host in $(echo $HMS_BACKEND_HOSTS | tr ',' ' '); do
  curl -fsSL -X POST -u "$REFRESH_USER:$REFRESH_PASS" \
    "https://${host}/actuator/refresh" || echo "FAILED: $host"
done
```

**b) Plain env-var rollout** (Railway, Docker compose, k8s)

Set the env var `OIDC_REQUIRED=true` on every backend replica and
restart them sequentially. Wait for `/actuator/health` to return
`UP` before moving to the next replica.

```bash
# Railway
railway variables set OIDC_REQUIRED=true --service hospital-core
railway redeploy --service hospital-core
```

Either way, the startup log on each backend node must carry both
lines below — copy them into the change log:

```
[OIDC] Keycloak resource-server is enabled — accepting JWTs alongside internal tokens
[OIDC] app.auth.oidc.required=true — legacy /auth/login + /auth/token/refresh will return 410 Gone
```

### 3. Smoke verify (T+0 to T+5)

From an external host (not the backend nodes):

```bash
# Legacy login must now return 410 Gone with the runbook copy.
curl -sS -o /tmp/login.json -w '%{http_code}\n' \
  -X POST "https://api.<env>.example.com/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"smoketest","password":"whatever"}'
# expect: 410
jq -r .message /tmp/login.json
# expect: "Legacy username/password login is disabled. Sign in via Single Sign-On."

# Legacy refresh must return 410 too.
curl -sS -X POST "https://api.<env>.example.com/api/auth/token/refresh" \
  -H "Content-Type: application/json" -d '{}' -o /dev/null -w '%{http_code}\n'
# expect: 410

# Resource server must still validate KC-issued JWTs.
TOKEN=$(curl -sS -X POST \
  "https://keycloak.<env>.example.com/realms/hms/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=hms-portal&username=$SMOKETEST_USER&password=$SMOKETEST_PASS&scope=openid profile email roles hms-claims" \
  | jq -r .access_token)
curl -sS -o /dev/null -w '%{http_code}\n' \
  "https://api.<env>.example.com/api/users/me" \
  -H "Authorization: Bearer $TOKEN"
# expect: 200
```

If any of the three checks deviate, **roll back** (§Rollback below).

### 4. Drive an SSO login from each surface (T+5 to T+15)

- Portal: `/login` → "Continue with Single Sign-On" → log in as a
  migrated user → land on dashboard, token in `sessionStorage`.
- Android: cold-start, tap SSO, complete on Chrome Custom Tab,
  protected API call returns 200.
- iOS: same on SFSafariViewController.

If any surface stalls on the redirect, check
[`keycloak/redirect-uris.md`](../../keycloak/redirect-uris.md) — a
post-flip mismatch usually surfaces as `invalid_redirect_uri` from
Keycloak.

## Post-flip monitoring (first 7 days)

Watch these signals on the `api-latency` Grafana dashboard:

| Metric | Expected | Page if |
|---|---|---|
| `/api/auth/login` 410 ratio | Ramps from 0 % to ≥ 95 % within 30 min, holds | < 80 % after 1 h (clients still calling legacy endpoint) |
| `/api/auth/login` 5xx ratio | < 0.1 % | > 1 % for ≥ 5 min |
| Keycloak `token` endpoint 4xx ratio | < 2 % steady state | > 10 % for ≥ 5 min (realm misconfig) |
| Backend JWKS fetch latency p99 | < 200 ms | > 1 s for ≥ 10 min |
| MFA enrolment completion rate | > 60 % of migrated active users by D+7 | < 30 % at D+3 (TOTP enrol UX broken) |
| `/api/auth/login` 410-text copy | Always equal to runbook string | Drift |

The MFA enrolment and password-reset ramps are surfaced by the
`required_actions_remaining` Keycloak admin metric — track it daily.

## Rollback

The cutover is **fully reversible** for the duration of the soak —
the legacy auth stack is still wired and only gated by this single
boolean.

1. Set `OIDC_REQUIRED=false` (or remove the property and rely on the
   default) on every backend replica.
2. Restart / `actuator/refresh` per §2 above.
3. Smoke check: `POST /api/auth/login` with valid creds returns 200,
   not 410.
4. Comms: notify users that legacy login is back on, SSO is still
   available alongside.
5. Triage: file a ticket capturing what broke (smoke check that
   failed, error-rate spike, etc.) and pause the next promotion
   until that ticket is resolved.

The Keycloak realm is left intact on rollback — migrated users keep
their Keycloak account, and a re-attempt only requires re-flipping
the flag. Do **not** delete realm or run a reverse migration.

## Acceptance criteria for "Phase 3 done"

The flip is considered durable when **all** of the below hold for
**≥ 30 consecutive days** in production:

- [ ] `/api/auth/login` 410 ratio is ≥ 95 % steady state.
- [ ] No P1/P2 auth incident attributed to the cutover.
- [ ] MFA enrolment completion ≥ 90 % of active migrated users.
- [ ] No rollback executed.

Hitting all four unblocks **Phase 4** (legacy auth tear-down) per
[keycloak-migration.md §3 Phase D](../keycloak-migration.md#3-user-migration-strategy).

## Ownership

- **Author:** backend team (S-03 sprint).
- **Executor:** backend on-call, paired with devops + clinical leads
  during the prod window.
- **Approver:** security lead (P-7 peer-review checkpoint) +
  product lead (user-comms gate).
