# Railway: from `railway.toml` to `.railway/railway.ts`

> Status on 2026-11-26 (evening): **dev is on `.railway/railway.ts`** (cutover and
> apply done; backend and frontend redeployed fine, the Keycloak redeploy failed
> and the previous Keycloak deployment still serves, see
> [What happened on dev](#what-happened-on-dev-2026-11-26)). The three
> `railway.toml` files are deleted from `develop` by the follow-up PR.
> **prod is still on Config as Code**: `main` still has the toml files and the prod
> services still point at them. prod is done on the owner's word, in the order
> given in [prod](#prod-on-the-owners-word-only): sync to `main` first, cutover and
> apply straight after.

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
| `RailwayConfigTest` | Fails if Keycloak stops being "always restart" in the file, and if a `railway.toml` or `railway.json` reappears anywhere in the repo. |
| `railway.toml`, `hospital-portal/railway.toml`, `keycloak/prod/railway.toml` | **Deleted** by the follow-up PR, once dev was on the new file. They stay on `main` until the next sync. |

How the new model differs from the old one:

- **Railway does not read `.railway/railway.ts` during a deploy.** Merging a change
  to it changes nothing. The file takes effect only when someone runs
  `railway config apply`, which writes the settings into the service on Railway.
  After that, an edit to the file needs another apply; a merge alone does nothing.
- **The file describes a whole environment.** Anything that exists on Railway and
  is missing from the file is deleted by apply, databases, volumes and buckets
  included. That is why the file lists the Postgres and Redis services, the
  volumes and the three prod buckets. Detached volumes turned out to behave
  differently, see [What happened on dev](#what-happened-on-dev-2026-11-26).
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

This section is the record of what was checked before anything was changed. The
plan output below still shows `"railway.toml"` among the backend watch patterns:
that pattern has since been removed from the file.

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

## What happened on dev (2026-11-26)

The owner approved the dev step and it was run the same day with CLI 5.75.0:
`config migrate cutover`, then `config apply --environment dev`.

- **Cutover** cleared the stored Config File path on `hms-frontend-dev` and
  `hms-keycloak-dev`.
- **Apply** succeeded, with a plan that showed **0 to destroy**. `hms-backend-dev`
  and `hms-frontend-dev` redeployed with the settings from the file.
- **The `hms-keycloak-dev` redeploy FAILED.** With its path cleared, the service
  has no root directory, so Railway looked for a config file at the repo root and
  found `railway.toml`, the *backend's* file. That file overrode the settings
  apply had just written: Railway built the root `Dockerfile` and started Spring
  Boot inside the Keycloak service. The first version of this page listed this
  as a possibility; it is what happens.
- **Nothing went down.** The previous `hms-keycloak-dev` deployment kept serving,
  so sign-in on dev went on working.
- **Detached volumes are not destroyed by apply.** The plan showed 0 to destroy
  with a detached volume present. The opposite is the trap: declaring a detached
  volume in the file does not adopt the existing one, the plan shows a **new**
  volume to create. Do not add a declaration for a detached volume, and treat
  any `Create` line for a volume as a reason to stop.

Consequences:

1. The three toml files have to go from `develop` before Keycloak can be
   redeployed on dev: that is the follow-up PR (see
   [Follow-up PR](#follow-up-pr-toml-files-deleted)).
2. On prod, "cutover, apply, then delete the files" would break the Keycloak
   redeploy the same way. The prod order is therefore reversed: the commit
   without toml files reaches `main` first, cutover and apply follow in the same
   sitting.

## Owner procedure

dev is done except for the last check (step 6). prod is still to do. Each
environment takes about 15 minutes. Pick a moment when nothing is being merged:
apply redeploys the three application services of that environment.

### Once: tooling

```powershell
npm install -g @railway/cli@5.75.0     # 5.47.1 has no cutover/undo/status
cd C:\dev\hms                          # a checkout that contains .railway/railway.ts
npm ci --prefix .railway               # installs the pinned SDK into .railway/node_modules
```

**On Windows, do not use the `railway` command that npm puts on the PATH.** It is
a `.cmd` shim, and the SDK's version check fails with it: plan and apply stop with
`This version of railway/iac requires Railway CLI 5.42.1 or newer` although the
CLI is recent enough. Run the native binary and set the environment variable `_`
to its path:

```powershell
$railway = "$env:APPDATA\npm\node_modules\@railway\cli\bin\railway.exe"
$env:_ = $railway
& $railway --version                   # 5.75.0 or newer
```

Every command below is written `& $railway …` for that reason. Run them from
PowerShell, at the repo root, in the same window (the two variables are gone in a
new window).

Flags, as used on dev:

- `config migrate cutover --yes` and `config apply --environment <env> --yes`.
  `--yes` answers the confirmation prompt.
- **Never add `--confirm-destructive`.** Nothing in this change deletes anything.
  That flag is what allows a destructive apply from a non-interactive session;
  leaving it out keeps a plan with a deletion from going through unnoticed. Read
  the plan first all the same.
- Pass `--environment` to plan and apply rather than relying on the linked
  environment.

### dev (run on 2026-11-26, kept as the record)

1. Link and confirm the environment.

   ```powershell
   & $railway link -p e-keneya -e dev
   & $railway status                    # must say Environment: dev
   & $railway config migrate status     # listed hms-frontend-dev and hms-keycloak-dev
   ```

2. Cut over. This cleared the stored Config File path on `hms-frontend-dev` and
   `hms-keycloak-dev`.

   ```powershell
   & $railway config migrate cutover --yes
   Copy-Item .railway\.cac-migration.json .railway\.cac-migration.dev.json   # keep the undo snapshot
   ```

3. Plan and read it against [the expected plan](#what-the-real-plan-should-show-after-cutover).

   ```powershell
   & $railway config plan --environment dev --verbose
   ```

4. Apply right away if, and only if, the plan matches (no `Delete`, no `Create`,
   0 to destroy).

   ```powershell
   & $railway config apply --environment dev --yes
   ```

5. Verify.

   ```powershell
   & $railway config plan --environment dev   # "Your Railway configuration is already up to date."
   & $railway service list                    # every service SUCCESS, no deployment stuck or FAILED
   curl.exe -s -o NUL -w "%{http_code}`n" https://api.dev.e-keneya.com/api/actuator/health
   curl.exe -s -o NUL -w "%{http_code}`n" https://dev.e-keneya.com/
   curl.exe -s -o NUL -w "%{http_code}`n" https://hms-keycloak-dev-dev.up.railway.app/realms/master/
   ```

   All three must return 200. Then sign in to the dev portal once, which exercises
   Keycloak and the backend together. In the dashboard, open the service settings
   of the three application services and check they show the values from the
   table at the top (Dockerfile builder, health check path, watch paths, restart
   policy).

   Result on 2026-11-26: the newest `hms-keycloak-dev` deployment FAILED, for the
   reason given in [What happened on dev](#what-happened-on-dev-2026-11-26).

6. **Still to do, after the follow-up PR is merged into `develop`.** The merge
   touches `hospital-core/**`, `hospital-portal/**` and `keycloak/**`, so Railway
   redeploys the three dev services from a commit without toml files. Re-run
   step 5 and look at the newest `hms-keycloak-dev` deployment in the dashboard:
   - built from `keycloak/prod/Dockerfile`, status SUCCESS: dev is finished;
   - no new deployment: redeploy `hms-keycloak-dev` from the dashboard;
   - built from the root `Dockerfile` again, or FAILED: stop. Sign-in still works
     on the old deployment. Do not start prod until this is understood.

   The settings stored on `hms-backend-dev` still list `railway.toml` among the
   watch paths, because apply ran before the pattern was removed from the file.
   It matches no file. The next `config apply --environment dev` removes it; the
   plan for it should show that one change and nothing else.

### prod (on the owner's word only)

This redeploys the production backend, portal and Keycloak. Do it only after
dev step 6 is green, at a quiet hour, and **do all of it in one sitting**. The
order is not the one used on dev:

1. **Recommended first, in the dashboard:** on `hms-backend-core`, Settings, set
   the health check path to `/api/actuator/health` (timeout 180 s) if it is empty.
   It was empty on 2026-11-26 (table at the top): the value only came from the
   root `railway.toml`, and the sync in the next step removes that file before
   apply has written the setting. Without it, a backend deploy triggered by the
   sync would take traffic before Spring Boot is up. Setting it early changes
   nothing while the toml is still read (same value).

2. **Sync `develop` to `main`**, with the commit that deletes the toml files in
   it. From that push on `main` has no toml file, and Railway starts a deploy of
   the prod services. Expected, not yet observed:
   - `hms-backend-core` has no stored config path: it builds with the settings
     stored on the service (Dockerfile builder, plus the health check from step 1);
   - `hms-frontend-prod` and `hms-keycloak-prod` still have a stored path to a
     file that no longer exists. Railway's documentation says such a build
     "reports that the config file was not found", so these two deployments
     should fail while the previous ones keep serving. This is the reason not to
     stop here: until cutover, neither service can be deployed.

3. **Straight after the push, cutover and apply**, from a checkout of the synced
   commit, so that the file applied is the one that was reviewed.

   ```powershell
   & $railway link -p e-keneya -e prod
   & $railway status                                    # must say Environment: prod
   & $railway config migrate status                     # lists hms-keycloak-prod and hms-frontend-prod
   & $railway config migrate cutover --yes
   Copy-Item .railway\.cac-migration.json .railway\.cac-migration.prod.json
   & $railway config plan --environment prod --verbose  # read it: no Delete, no Create, 0 to destroy
   & $railway config apply --environment prod --yes
   ```

4. **Verify.**

   ```powershell
   & $railway config plan --environment prod            # "already up to date"
   & $railway service list
   curl.exe -s -o NUL -w "%{http_code}`n" https://api.e-keneya.com/api/actuator/health
   curl.exe -s -o NUL -w "%{http_code}`n" https://e-keneya.com/
   curl.exe -s -o NUL -w "%{http_code}`n" https://hms-keycloak-prod-prod.up.railway.app/realms/master/
   ```

   All three 200, every service SUCCESS, and the newest `hms-keycloak-prod`
   deployment built from `keycloak/prod/Dockerfile`. If apply did not start a new
   deployment for the frontend or Keycloak after a failed one in step 2, redeploy
   them from the dashboard. Sign in to the production portal once.

5. Relink to dev (`& $railway link -p e-keneya -e dev`) so that the next command
   typed in this folder does not land on production.

Why not cutover first, as on dev: between cutover and the deletion reaching
`main`, any deploy of `hms-keycloak-prod` reads the root `railway.toml` and
builds the backend image, which is what failed on dev.

### Rollback

Before 2026-12-01 the old mechanism can be restored per environment, **as long
as the branch that environment deploys still has the toml files**. Once the
deletion is on `develop` (dev) or on `main` (prod), putting the stored paths back
points the services at files that are gone: revert the deletion commit on that
branch first, then follow the two steps below. Link the environment first.

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
plan says so. If a plan ever shows a deletion, stop, and never pass
`--confirm-destructive`.

## Follow-up PR: toml files deleted

Done in the PR that follows the dev apply:

- `railway.toml`, `hospital-portal/railway.toml` and `keycloak/prod/railway.toml`
  are deleted;
- `"railway.toml"` is dropped from the backend watch patterns in
  `.railway/railway.ts`;
- `RailwayConfigTest` reads `.railway/railway.ts` only and fails if a
  `railway.toml` or `railway.json` comes back anywhere in the repo;
- `railway-services.md`, `railway-env-matrix.md`, `keycloak/prod/README.md` and
  the other current pages that named the toml files point at `.railway/railway.ts`.

What its merge does:

- **dev:** the three dev services redeploy from a commit without toml files (dev
  step 6 above).
- **prod:** nothing, until the next sync to `main`. That sync is step 2 of the
  prod procedure and must be followed by cutover and apply immediately.

Do not add a `railway.toml` or `railway.json` back, in any folder.

## Found on the way, not changed here

- `hms-keycloak-prod` has "sleep when idle" (serverless) switched on, so the first
  sign-in after a quiet period waits for Keycloak to start. The file reproduces it
  as found. `hms-keycloak-dev` does not have it.
- Detached volumes that still hold data: `hospital-core-volume` in prod (about
  1 GB, the uploads from before object storage) and `redis-volume-GW5t` in dev.
  Both are declared in the file, which was written before the dev apply showed
  how detached volumes behave (see
  [What happened on dev](#what-happened-on-dev-2026-11-26)). The declarations are
  left as they are here. In the prod plan, look at `hospital-core-volume` in
  particular: a `Create` line under that name means the declaration would produce
  a second volume, and apply must not be run until the declaration is removed.
- `.github/workflows/deploy.yml` pins CLI 4.57.2 and uses `railway up`. It is a
  manual re-trigger that is not part of the normal deploy path and is not affected
  by this change.

## Troubleshooting

- `The Railway TypeScript SDK is not installed`: run `npm ci --prefix .railway`.
- `This version of railway/iac requires Railway CLI 5.42.1 or newer` with a recent
  CLI: the SDK's version check failed. This happens when the CLI is started
  through the npm shim (`railway.cmd`). Run the native `railway.exe` with the
  environment variable `_` set to its path, as in [Once: tooling](#once-tooling).
- `.railway/railway.ts only describes the "dev" and "prod" environments`: the
  folder is linked to another environment. `railway link -p e-keneya -e dev`.
- `... is already managed by /…/railway.toml`: cutover has not been run for that
  environment.
- A Keycloak deployment builds the root `Dockerfile` and starts Spring Boot: a
  `railway.toml` is at the repo root of the branch that service deploys. Delete it
  and redeploy.
