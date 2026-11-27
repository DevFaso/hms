# Railway Services — Configuration Runbook

> Canonical Railway dashboard configuration for every HMS service.
> If a prod build fails because Railway picks up the wrong
> `Dockerfile`, check this file first — the problem is almost always
> a dashboard override drifting away from the values below.
>
> For the project-loss / DB-restore / full-rebuild path, see
> [disaster-recovery.md](./disaster-recovery.md). This file owns the
> per-service config; that one owns the recovery sequence.
>
> **The values below are declared in
> [`.railway/railway.ts`](../../.railway/railway.ts)** and written to Railway
> with `railway config apply`; Railway does not read that file during a
> deploy. The three `railway.toml` files that used to drive the services are
> deleted (Railway stops reading them on 2026-12-01). Procedure, state of
> each environment and rollback:
> [railway-config-as-code.md](./railway-config-as-code.md). Until the prod
> cutover described there has been run, `main` still carries the toml files
> and the prod services still read them.

## Services

HMS runs as three independent Railway services in the production
project. Each one must have its own Root Directory and Dockerfile
Path set correctly.

### 1. Backend (Spring Boot, `hms-core`)

| Field | Value |
|---|---|
| Root Directory | *(blank — repo root)* |
| Builder | Dockerfile |
| Dockerfile Path | `Dockerfile` |
| Watch Paths | `hospital-core/**`, `Dockerfile`, `.dockerignore`, `build.gradle`, `settings.gradle`, `gradle.properties`, `gradle/**`, `gradlew` |
| Healthcheck | `/api/actuator/health` (180 s) |
| Restart policy | on failure, 3 retries |
| Declared in | [`.railway/railway.ts`](../../.railway/railway.ts) (`BACKEND_BUILD`, `BACKEND_HEALTHCHECK`) |
| Dockerfile | [`Dockerfile`](../../Dockerfile) |
| Entrypoint | [`entrypoint.sh`](../../entrypoint.sh) |

Build produces `/app/app.jar` from `hospital-core:bootJar` and runs
it as UID 10001 (`appuser`).

### 2. Frontend (Angular portal, `hospital-portal`)

| Field | Value |
|---|---|
| Root Directory | `hospital-portal` |
| Builder | Dockerfile |
| Dockerfile Path | `Dockerfile` *(relative to Root Directory — resolves to `hospital-portal/Dockerfile`)* |
| Watch Paths | `hospital-portal/**` |
| Healthcheck | `/` (60 s) |
| Restart policy | on failure, 3 retries |
| Declared in | [`.railway/railway.ts`](../../.railway/railway.ts) (`FRONTEND_BUILD`, `FRONTEND_HEALTHCHECK`) |
| Dockerfile | [`hospital-portal/Dockerfile`](../../hospital-portal/Dockerfile) |

**Do not** set Dockerfile Path to `../Dockerfile` or to the
repo-root `Dockerfile`. The repo-root `Dockerfile` is the backend
image and expects `entrypoint.sh` + the Gradle tree in its context
— neither is reachable from `hospital-portal/` and the build will
fail with:

```
failed to compute cache key: ... "/entrypoint.sh": not found
```

### 3. Keycloak (prod identity provider)

| Field | Value |
|---|---|
| Root Directory | *(blank — repo root)* |
| Builder | Dockerfile |
| Dockerfile Path | `keycloak/prod/Dockerfile` |
| Watch Paths | `keycloak/**`, `.dockerignore` |
| Healthcheck | `/realms/master/` (300 s) |
| Restart policy | always |
| Declared in | [`.railway/railway.ts`](../../.railway/railway.ts) (`KEYCLOAK_BUILD`, `KEYCLOAK_HEALTHCHECK`) |
| Dockerfile | [`keycloak/prod/Dockerfile`](../../keycloak/prod/Dockerfile) |

See [`keycloak/prod/README.md`](../../keycloak/prod/README.md) for
env vars, admin lockdown, and provisioning steps.

## When a build fails with the "wrong" Dockerfile

Symptom: Railway's build header resolves the correct service
Dockerfile (e.g. `found 'Dockerfile' at 'hospital-portal/Dockerfile'`),
but the actual build steps are from a different service's Dockerfile
(e.g. `RUN ./gradlew :hospital-core:bootJar` in a frontend build).

Cause: **Poisoned build cache.** An earlier deploy with the wrong
Root Directory / Dockerfile Path seeded the cache; Railway's
subsequent rebuilds replay those cached layers even after the
config is corrected.

Fix:

1. Verify the service's Root Directory and Dockerfile Path match
   the table above. Save if you changed them.
2. **Clear Build Cache** — in the service, Settings → Danger → Clear
   Build Cache (or redeploy from a fresh commit).
3. Trigger a deploy. The build header should now show the correct
   FROM image (e.g. `FROM node:20-alpine` for the frontend).

If the build header itself is wrong (e.g. frontend service loading
the backend Dockerfile), the Root Directory or Dockerfile Path is
misconfigured — go back to step 1.

## Guard rails

- No `railway.toml` or `railway.json` anywhere in this repo. A service
  with no stored config path reads one found at the repo root, whatever
  service it was written for: on 2026-11-26 the dev Keycloak built the
  backend image that way. `RailwayConfigTest` fails if one reappears.
- Service settings change in `.railway/railway.ts` followed by
  `railway config plan` and `railway config apply`, not in the dashboard
  (a dashboard edit shows up as a difference in the next plan).
- Do not cross-reference Dockerfiles between services.
- The backend Dockerfile lives at the repo root because its build
  context needs the multi-module Gradle tree. It must stay there.
- The frontend Dockerfile must stay inside `hospital-portal/` so the
  `COPY package*.json ./` and `COPY . .` instructions resolve.
- The Keycloak Dockerfile lives inside `keycloak/prod/` and bakes in
  [`keycloak/realm-export.json`](../../keycloak/realm-export.json)
  via a relative `COPY`.
