# Railway Services — Configuration Runbook

> Canonical Railway dashboard configuration for every HMS service.
> If a prod build fails because Railway picks up the wrong
> `Dockerfile`, check this file first — the problem is almost always
> a dashboard override drifting away from the values below.

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
| Watch Paths | `hospital-core/**`, `build.gradle`, `settings.gradle`, `entrypoint.sh`, `Dockerfile` |
| Healthcheck | `/api/actuator/health` |
| Driven by | [`railway.toml`](../../railway.toml) at repo root |
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
| Healthcheck | `/` |
| Driven by | [`hospital-portal/railway.toml`](../../hospital-portal/railway.toml) |
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
| Watch Paths | `keycloak/**` |
| Healthcheck | `/health/ready` |
| Driven by | [`keycloak/prod/railway.toml`](../../keycloak/prod/railway.toml) |
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

- Each `railway.toml` in this repo owns exactly one service. Do not
  cross-reference Dockerfiles between services.
- The backend Dockerfile lives at the repo root because its build
  context needs the multi-module Gradle tree. It must stay there.
- The frontend Dockerfile must stay inside `hospital-portal/` so the
  `COPY package*.json ./` and `COPY . .` instructions resolve.
- The Keycloak Dockerfile lives inside `keycloak/prod/` and bakes in
  [`keycloak/realm-export.json`](../../keycloak/realm-export.json)
  via a relative `COPY`.
