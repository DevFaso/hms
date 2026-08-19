# Railway Operations

How the HMS stack is wired on Railway, and the moving parts an operator has to keep right per environment.

## Service layout

| Service | Build | Notes |
| --- | --- | --- |
| `hms-backend-{env}` | root `Dockerfile` (Spring Boot) | Health: `/api/actuator/health`. Single replica. |
| `hms-frontend-{env}` | `hospital-portal/Dockerfile` | Static Nginx serving Angular bundle + reverse-proxying `/api`. |
| `hms-postgres-{env}` | Railway Postgres plugin | One per environment, no cross-env sharing. |
| `hms-keycloak-{env}` | `keycloak/prod/Dockerfile` | Optional; KC-2b cutover gate. |

Each environment (`dev`, `uat`, `prod`) runs an isolated copy of every service.

## Required service variables

### `hms-frontend-{env}` — build-time (must be set as a Service Variable)

| Variable | Value per env |
| --- | --- |
| `BUILD_CONFIG` | `dev` / `uat` / `production` |

This is the single most common configuration drift on Railway. The Angular bundle hardcodes the API URL, Faro RUM endpoint, and OIDC issuer at build time via Angular's `fileReplacements`. If `BUILD_CONFIG` is missing, Docker uses the default (`production`) and the UAT/dev frontend ships with `environment.prod.ts` baked in:

- Faro RUM points at the prod collector → CORS-rejected from non-prod hostnames (you'll see `Failed to fetch` from `@grafana/faro-web-sdk` in DevTools).
- Telemetry that *does* land arrives tagged `environment: production`, polluting prod dashboards with UAT traffic.
- OIDC redirect URIs point at the prod IdP.

The runtime defence in `src/main.ts` will detect a hostname/env mismatch and refuse to initialise Faro, but the OIDC and API-base settings still come from the wrong file. Set `BUILD_CONFIG` correctly per service.

### `hms-frontend-{env}` — runtime

| Variable | Value |
| --- | --- |
| `PORT` | Set by Railway. Don't override. |
| `API_BASE_URL` | `http://${BACKEND_INTERNAL_HOST}:8081` (use Railway internal) |
| `NGINX_RESOLVER` | Optional — defaults to host's `/etc/resolv.conf` first ns. |

### `hms-backend-{env}`

| Variable | Value (UAT example) |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `uat` |
| `DATABASE_URL` | Railway-injected from the Postgres plugin |
| `SPRING_DATASOURCE_USERNAME` | from Postgres plugin |
| `SPRING_DATASOURCE_PASSWORD` | from Postgres plugin |
| `FRONTEND_BASE_URL` | `https://hms.uat.bitnesttechs.com` |
| `PUBLIC_BASE_URL` | `https://hms.uat.bitnesttechs.com` |
| `CORS_ALLOWED_ORIGINS` | `https://hms.uat.bitnesttechs.com,https://patient.uat.bitnesttechs.com` |
| `JWT_SECRET` | strong, env-specific |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Grafana Cloud OTLP endpoint (enables the OTel Java agent at startup) |

`CORS_ALLOWED_ORIGINS` doubles as the allowed-origin list for the SockJS WebSocket handshake (`/api/ws-chat`). If the frontend host is missing here, the WS upgrade is rejected before any auth check runs.

## Email link host pattern

Outbound email templates must use `hms.{env}.bitnesttechs.com` (dot-separated). The hyphenated form (`hms-{env}.bitnesttechs.com`) is **not** a real DNS record and produces `DNS_PROBE_FINISHED_NXDOMAIN` in users' browsers. Grep email templates for the wrong form before any release.

## Observability

### Production (Railway)

The OTel Java agent is auto-attached when `OTEL_EXPORTER_OTLP_ENDPOINT` is set (see `entrypoint.sh`). It exports traces, metrics, and logs directly to Grafana Cloud over OTLP — no separate Alloy/Loki deployment needed.

Frontend RUM goes to Grafana Cloud Faro. The collector URL is per-environment:

- prod: configured in `environment.prod.ts`.
- uat: blank by default — provision a separate Faro app in Grafana Cloud and put its URL in `environment.uat.ts` if you want UAT RUM. Do not reuse the prod URL — its allowed-origins list does not include UAT hostnames.

### Local dev (docker-compose)

`docker compose up` brings up the full observability stack:

- **Prometheus** scrapes `/api/actuator/prometheus` directly.
- **Loki** + **Alloy** for logs. Alloy mounts the docker socket and tails container stdout, applying `application=hms` to the backend container.
- **Grafana** at <http://localhost:3000> (admin/admin) auto-loads two provisioned dashboards: `HMS – Application Metrics & Logs` and `HMS – Business KPIs`.

## WebSocket / chat 401 troubleshooting

A 401 on `/api/ws-chat/info?ticket=...` is one of:

1. **Stale ticket on reconnect.** The frontend re-mints a ticket on every (re)connect (see `notification.service.ts → scheduleReconnect`); the backend's TTL is 5 minutes (`WsTicketService.TICKET_TTL_MS`). If you see persistent 401s, check that the ticket POST `/api/auth/ws-ticket` is succeeding before the SockJS attempt.
2. **CORS allowed-origins mismatch.** `app.cors.allowed-origins` (env: `CORS_ALLOWED_ORIGINS`) must include the public frontend host. The same value gates both the REST CORS filter and the SockJS handshake.
3. **Disabled / unverified user.** A redeemed ticket for a disabled account is rejected by `JwtAuthenticationFilter.handleWsTicketAuth`. Check the user's `enabled` flag and email verification status.
4. **Multi-replica deployment.** `WsTicketService` is in-memory and per-instance. If the backend is scaled > 1 replica, the ticket may be issued on instance A and validated on instance B → 401. Either pin to one replica or move the ticket store to Redis.

## Deployment cache busting

If a Railway build pulls a stale layer and the deployed image doesn't reflect a recent fix, bump a no-op comment in `entrypoint.sh` (or `hospital-portal/Dockerfile`) and re-deploy. The build context hash changes, the layer cache invalidates. Recent precedent: `6352ce7c` (entrypoint.sh comment bump) and `a4b68f12` (same, again).
