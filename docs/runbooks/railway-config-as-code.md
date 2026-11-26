# Railway: from `railway.toml` to `.railway/railway.ts`

> Status on 2026-11-26: the new file is written and checked read-only against
> dev and prod. **Nothing has been changed on Railway.** The three `railway.toml`
> files are still in the repo and still drive every deploy. The owner runs the
> steps in [Owner procedure](#owner-procedure): dev first, prod on the owner's word.

## What happens on 2026-12-01

Railway has deprecated Config as Code (`railway.toml` / `railway.json`). Its
documentation says existing files "stop being read on 2026-12-01 (hard cutoff)"
([Infrastructure as Code](https://docs.railway.com/infrastructure-as-code#iac-vs-config-as-code),
[Config as Code](https://docs.railway.com/config-as-code)).

Deploys are not blocked. The settings in the files are **silently ignored**: from
the first deploy after the cutoff, each service is built and run with the settings
stored on the service in Railway (what the dashboard shows). Today the files
override those stored settings on every deploy, and the stored settings were never
kept in step. A deployment that is already running is not touched; the change lands
at the next deploy of each service.

Stored settings today, read with `railway config pull --json` on 2026-11-26, and
therefore what each service would fall back to:

| Service | Setting | From `railway.toml` today | Stored on Railway (fallback) |
|---|---|---|---|
| `hms-backend-dev` | Builder | Dockerfile | **Railpack** |
| both backends | Health check | `/api/actuator/health`, 180 s | **none**: a deploy takes traffic before Spring Boot is up |
| both backends | Watch paths | 9 patterns | none: every push rebuilds the backend |
| both backends, both frontends | Restart policy | on failure, 3 retries | on failure, 10 retries |
| both frontends | Health check | `/`, 60 s | `/health`, 300 s (nginx serves `/health`, so it still passes) |
| `hms-frontend-prod` | Watch paths | `hospital-portal/**` | none |
| both Keycloaks | Restart policy | **always** | **on failure, 10 retries**: the D16 outage can come back |
| both Keycloaks | Watch paths | `keycloak/**`, `.dockerignore` | none: Keycloak restarts on every push |

Two things the documentation does not say, so treat them as unknown:

- whether `hms-backend-dev` on the Railpack builder still picks up the root
  `Dockerfile` or tries to build the Gradle project itself;
- what a deploy does after the cutoff when a service still has a stored
  **Config File path** pointing at a file Railway no longer reads. Four services
  have one (see below). The only documented failure is "path set and file missing
  from the commit: the build reports that the config file was not found".

The procedure below removes both unknowns before the date.

## What changes in this repo

| File | Role |
|---|---|
| `.railway/railway.ts` | The whole project, both environments. Replaces the three toml files. |
| `.railway/package.json`, `package-lock.json` | Pins the `railway` SDK the CLI needs to read the file. Operator tooling only. |
| `RailwayConfigTest` | Now also fails if Keycloak stops being "always restart" in the new file. |
| `railway.toml`, `hospital-portal/railway.toml`, `keycloak/prod/railway.toml` | **Unchanged, still authoritative.** Removed in a follow-up PR after a verified apply. |

How the new model differs from the old one:

- **Railway does not read `.railway/railway.ts` during a deploy.** Merging this PR
  changes nothing. The file takes effect only when someone runs
  `railway config apply`, which writes the settings into the service on Railway.
  After that, an edit to the file needs another apply; a merge alone does nothing.
- **The file describes a whole environment.** Anything that exists on Railway and
  is missing from the file is deleted by apply, databases, volumes and buckets
  included. That is why the file lists the Postgres and Redis services, every
  volume (two of them detached) and the three prod buckets.
- **One file, two environments.** dev and prod hold different services, so the
  file returns a different list for each and throws for any other environment.
- **Variables stay in the dashboard.** The file sets `variables: { managed: false }`
  and declares no variable. Plan reports them as "64 on Railway not managed" (dev)
  and "84 on Railway not managed" (prod); apply does not read, write or delete any.

### Where the generated file was wrong

`railway config migrate` (CLI 5.75.0) produced the starting point. Three things
in its output did not reproduce the current state and were corrected by hand:

1. **Restart policy dropped.** It emitted no restart policy at all, which would
   have reset Keycloak from "always" to the default. Added for all six services.
2. **Application database moved to PostgreSQL 18.** It declared
   `hms-Postgres-dev` and `hms-Postgres-prod` with the stock `postgres()` helper,
   which pins PostgreSQL 18; both run **PostgreSQL 17**. The plan showed
   `source.image (null → "postgres:18")` on both. A PostgreSQL 18 image does not
   start on a 17 data directory. All database images are now pinned to what runs
   today (app databases 17, Keycloak databases 18, Redis 8.2).
3. **One environment only.** Its output describes the linked environment, so
   applying the dev output to prod (or the reverse) would have offered to delete
   every service of the other environment. The file now branches per environment.

`[deploy.healthcheck]` (`interval`, `timeout`) in two toml files is not a Railway
setting and never reached a deployment, so it has no equivalent in the new file.

## Plan results (read-only, 2026-11-26)

`railway config plan` only reads. It was run with CLI 5.75.0 against both
environments.

### 1. The full plan cannot run before cutover

Four services have a stored Config File path, and plan refuses them until it is
cleared (clearing it is the `cutover` step, which changes Railway and was not run):

```text
$ railway config migrate status        # dev
Still on Config as Code (2):
  - hms-frontend-dev  /hospital-portal/railway.toml
  - hms-keycloak-dev  /keycloak/prod/railway.toml

$ railway config migrate status        # prod
Still on Config as Code (2):
  - hms-keycloak-prod  /keycloak/prod/railway.toml
  - hms-frontend-prod  /hospital-portal/railway.toml
```

```text
$ railway config plan                  # dev (prod: same two errors with -prod names)
Environment dev
variables: unmanaged · 64 on Railway not managed
Error error: resources.service.hms-frontend-dev.configFile: hms-frontend-dev is already managed by /hospital-portal/railway.toml. Remove or migrate the repo config before managing this service from .railway/railway.ts.
Error error: resources.service.hms-keycloak-dev.configFile: hms-keycloak-dev is already managed by /keycloak/prod/railway.toml. Remove or migrate the repo config before managing this service from .railway/railway.ts.
```

The two backends have no stored path: Railway finds the root `railway.toml` by
itself.

### 2. Plan of everything except those four services

To get a real diff for the rest, the same file was planned from a scratch copy
with the frontend and Keycloak services left out of the list. That copy is not in
the repo; the "delete" lines it produced for the two omitted services are an
artefact of leaving them out and are removed below.

```text
Environment dev
variables: unmanaged · 64 on Railway not managed
  ~ Update hms-backend-dev build.builder, build.watchPatterns
    └ build.builder (null → "DOCKERFILE")
    └ build.watchPatterns (null → ["hospital-core/**","Dockerfile",".dockerignore","build.gradle","settings.gradle","gradle.properties","gradle/**","gradlew","railway.toml"])
  ~ Update hms-backend-dev deploy.healthcheckPath, deploy.healthcheckTimeout, deploy.restartPolicyMaxRetries
    └ deploy.healthcheckPath (null → "/api/actuator/health")
    └ deploy.healthcheckTimeout (null → 180)
    └ deploy.restartPolicyMaxRetries (null → 3)
```

```text
Environment prod
variables: unmanaged · 84 on Railway not managed
  ~ Update hms-backend-core build.watchPatterns
    └ build.watchPatterns (null → ["hospital-core/**","Dockerfile",".dockerignore","build.gradle","settings.gradle","gradle.properties","gradle/**","gradlew","railway.toml"])
  ~ Update hms-backend-core deploy.healthcheckPath, deploy.healthcheckTimeout, deploy.restartPolicyMaxRetries
    └ deploy.healthcheckPath (null → "/api/actuator/health")
    └ deploy.healthcheckTimeout (null → 180)
    └ deploy.restartPolicyMaxRetries (null → 3)
```

No database, volume, bucket, domain or variable appears in either plan.

So the plan is **not empty, and that is correct**: it lists exactly the settings
that live only in `railway.toml` today and are being written into the service.
What a deployment actually runs with does not change.

### 3. Every setting compared with what is deployed

Each service setting in the file was compared with the manifest of the deployment
currently running (`railway status --json`): builder, Dockerfile path, watch
patterns, health check path and timeout, restart policy and retries, CPU and
memory limits, sleep setting, replicas and region, start command, branch, root
directory, and the image of each database. Result for the 12 services across both
environments: **no difference**. The declared services, databases and volumes are
the same set as on Railway in each environment.

### What the real plan should show after cutover

Section 2 plus these lines, predicted from the stored settings. They cannot be
observed before cutover.

| Service | Expected lines |
|---|---|
| `hms-frontend-dev` | `build.watchPatterns` (`/hospital-portal/**` → `hospital-portal/**`), `deploy.healthcheckPath` (`/health` → `/`), `deploy.healthcheckTimeout` (→ 60), `deploy.restartPolicyMaxRetries` (→ 3) |
| `hms-frontend-prod` | `build.watchPatterns` (null → `hospital-portal/**`), `deploy.healthcheckPath` (`/health` → `/`), `deploy.healthcheckTimeout` (→ 60), `deploy.restartPolicyMaxRetries` (→ 3) |
| `hms-keycloak-dev`, `hms-keycloak-prod` | `build.watchPatterns` (null → `keycloak/**`, `.dockerignore`), `deploy.healthcheckTimeout` (→ 300), `deploy.restartPolicyType` (→ `ALWAYS`), possibly `build.dockerfilePath` (leading slash removed) |

**Stop and do not apply** if the plan shows any of these: a `Delete` or `Create`
line; a line for a database, a volume or a bucket; `source.image`; a variable;
a domain; a change on a service that is not one of the three application services.

## Owner procedure

Do dev completely, then prod. Each environment takes about 15 minutes. Pick a
moment when nothing is being merged: apply is expected to redeploy the three
application services of that environment.

### Once: tooling

```powershell
npm install -g @railway/cli@5.75.0     # the installed 5.47.1 has no cutover/undo/status
railway --version                      # 5.75.0 or newer
cd C:\dev\hms                          # a checkout that contains .railway/railway.ts
npm ci --prefix .railway               # installs the pinned SDK into .railway/node_modules
```

Run the commands below from PowerShell, at the repo root.

### dev

1. Link and confirm the environment. Every later command acts on the linked one.

   ```powershell
   railway link -p e-keneya -e dev
   railway status                       # must say Environment: dev
   railway config migrate status        # lists hms-frontend-dev and hms-keycloak-dev
   ```

2. Cut over. This clears the stored Config File path on `hms-frontend-dev` and
   `hms-keycloak-dev`. It deploys nothing and changes no other setting.

   ```powershell
   railway config migrate cutover
   Copy-Item .railway\.cac-migration.json .railway\.cac-migration.dev.json   # keep the undo snapshot
   ```

3. Plan and read it against [the expected plan](#what-the-real-plan-should-show-after-cutover).

   ```powershell
   railway config plan --verbose
   ```

4. Apply right away if, and only if, the plan matches. Answer the confirmation
   prompt by hand. Do not pass `--yes` or `--confirm-destructive`: nothing in this
   change is destructive, and without those flags a destructive plan cannot go
   through unnoticed.

   ```powershell
   railway config apply
   ```

5. Verify.

   ```powershell
   railway config plan                  # "Your Railway configuration is already up to date."
   railway service list                 # every service SUCCESS, no deployment stuck or FAILED
   curl.exe -s -o NUL -w "%{http_code}`n" https://api.dev.e-keneya.com/api/actuator/health
   curl.exe -s -o NUL -w "%{http_code}`n" https://dev.e-keneya.com/
   curl.exe -s -o NUL -w "%{http_code}`n" https://hms-keycloak-dev-dev.up.railway.app/realms/master/
   ```

   All three must return 200 (they did before the change, 2026-11-26). Then sign in
   to the dev portal once, which exercises Keycloak and the backend together.
   In the dashboard, open the service settings of the three application services
   and check they now show the values from the table at the top (Dockerfile
   builder, health check path, watch paths, restart policy).

6. **Check which config the Keycloak deployment used.** This is the one thing that
   could not be tested in advance. Once its path is cleared, `hms-keycloak-dev`
   has no root directory, and Railway's documentation says a service without a
   stored path reads a `railway.toml` found at the repo root, which is the
   *backend's* file. If apply redeploys Keycloak while that file is still in the
   branch, the build may use the backend Dockerfile and fail its health check; the
   previous Keycloak deployment then stays in service. Look at the newest
   `hms-keycloak-dev` deployment in the dashboard:
   - built from `keycloak/prod/Dockerfile`, status SUCCESS: no issue, carry on;
   - built from the root `Dockerfile` or FAILED: sign-in still works on the old
     deployment. Merge the follow-up PR that deletes the toml files (next step),
     then redeploy `hms-keycloak-dev` from the dashboard and re-run step 5.
     Tell whoever prepares the prod step: prod must then be done as cutover, apply
     and the sync that carries the deletion in one sitting.

7. Merge the follow-up PR that removes the three toml files into `develop` as soon
   as dev is verified. Until that merge (or 2026-12-01, whichever comes first) do
   not merge anything that touches `keycloak/**` or `.dockerignore`, for the reason
   in step 6. The merge redeploys the three dev services from a commit without
   toml files; re-run step 5 after it.

### prod (on the owner's word only)

Applying to prod redeploys the production backend, portal and Keycloak. Do it
only after dev has been verified, and plan it together with the sync to `main`
that carries the toml deletion, so that production spends as little time as
possible between cutover and deletion.

Same steps as dev with:

```powershell
railway link -p e-keneya -e prod
railway status                          # must say Environment: prod
railway config migrate status           # lists hms-keycloak-prod and hms-frontend-prod
railway config migrate cutover
Copy-Item .railway\.cac-migration.json .railway\.cac-migration.prod.json
railway config plan --verbose
railway config apply
railway config plan
railway service list
curl.exe -s -o NUL -w "%{http_code}`n" https://api.e-keneya.com/api/actuator/health
curl.exe -s -o NUL -w "%{http_code}`n" https://e-keneya.com/
curl.exe -s -o NUL -w "%{http_code}`n" https://hms-keycloak-prod-prod.up.railway.app/realms/master/
```

Then relink to dev (`railway link -p e-keneya -e dev`) so that the next command
typed in this folder does not land on production.

### Rollback

Before 2026-12-01 the old mechanism can be restored per environment. Link the
environment first.

1. `railway config migrate undo` puts the stored Config File paths back from
   `.railway/.cac-migration.json` (copy the `.dev` / `.prod` snapshot back to that
   name first if the other environment was cut over in between). If the snapshot
   is lost, set the path by hand in the dashboard, service → Settings → Config as
   Code: `/hospital-portal/railway.toml` on the frontend,
   `/keycloak/prod/railway.toml` on Keycloak.
2. Redeploy the affected service from the dashboard. The toml files then override
   the stored settings again, exactly as before the change.

The settings apply wrote do not need to be reverted: they equal the toml values,
and they are only a fallback while the toml files are read.

If a deployment fails after apply, the previous deployment keeps serving as long
as the new one has not passed its health check; in the dashboard, "Redeploy" on
the last good deployment restores it. A wrong setting can also be corrected in
the file followed by `railway config plan` and `railway config apply`, or directly
in the dashboard (the next plan then shows that edit as a difference).

From 2026-12-01 there is no way back to the toml files. Fix forward with the file
or the dashboard.

Apply cannot delete a database, a volume or a bucket in this procedure unless the
plan says so and someone confirms it. If a plan ever shows one, stop.

## After both environments are verified

Follow-up PR (not in this one):

- delete `railway.toml`, `hospital-portal/railway.toml`, `keycloak/prod/railway.toml`;
- drop `"railway.toml"` from the backend watch patterns in `.railway/railway.ts`,
  then plan and apply (a one-line change on each backend);
- remove the toml half of `RailwayConfigTest`;
- point `railway-services.md`, `railway-env-matrix.md` and `keycloak/prod/README.md`
  at `.railway/railway.ts`.

## Found on the way, not changed here

- `hms-keycloak-prod` has "sleep when idle" (serverless) switched on, so the first
  sign-in after a quiet period waits for Keycloak to start. The file reproduces it
  as found. `hms-keycloak-dev` does not have it.
- `railway-services.md` lists the Keycloak health check as `/health/ready` and
  watch paths that no longer match; the deployed values are the ones in the table
  at the top of this page.
- Detached volumes that still hold data: `hospital-core-volume` in prod (about
  1 GB, the uploads from before object storage) and `redis-volume-GW5t` in dev.
  Both are declared in the file so that apply keeps them.
- `.github/workflows/deploy.yml` pins CLI 4.57.2 and uses `railway up`. It is a
  manual re-trigger that is not part of the normal deploy path and is not affected
  by this change.

## Troubleshooting

- `The Railway TypeScript SDK is not installed`: run `npm ci --prefix .railway`.
- `This version of railway/iac requires Railway CLI 5.42.1 or newer` with a recent
  CLI: the SDK could not run `railway --version`. This happens when the CLI is
  started through an npm shim from Git Bash. Use PowerShell.
- `.railway/railway.ts only describes the "dev" and "prod" environments`: the
  folder is linked to another environment. `railway link -p e-keneya -e dev`.
- `... is already managed by /…/railway.toml`: cutover has not been run for the
  linked environment.
