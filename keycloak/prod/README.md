# HMS Keycloak on Railway — per-environment provisioning

> Implements **P-2** from
> [../../docs/tasks-keycloak.md](../../docs/tasks-keycloak.md) and **Phase
> 2.8.A** from
> [../../docs/keycloak-implementation-gaps.md](../../docs/keycloak-implementation-gaps.md).
> One Dockerfile + one `railway.toml` serves all three environments via
> the `BUILD_CONFIG` build arg.

## What this ships

- [`Dockerfile`](Dockerfile) — Keycloak 26.0.7 with the HMS realm baked in.
  Accepts `ARG BUILD_CONFIG=prod` (override per env) and stamps both a
  `com.bitnesttechs.hms.env` Docker label and a `KC_HMS_ENV` runtime env
  var so the running image is grep-able from boot logs and metrics.
- [`railway.toml`](railway.toml) — Railway service config; the same file
  is referenced by all three `hms-keycloak-{env}` services.
- This runbook for one-time provisioning + ongoing ops.

Dev users (`../realm-export.dev-users.json`) are deliberately **not**
auto-imported in any environment — that file's `_comment` explicitly
forbids it to prevent stray staff accounts in shared envs. Apply manually
through the admin console's *Realm Settings → Action → Partial Import*
when needed.

## One-time provisioning (per environment)

Run this recipe once per environment. The order is **dev → uat → prod**:
each downstream env reuses settings proven in the previous one.

### 1. Create the Railway service

1. In the HMS Railway project, **+ New → GitHub Repo** → select this repo.
2. Name the service `hms-keycloak-<env>` (e.g. `hms-keycloak-dev`).
3. Under **Settings → Source → Config-as-code**, set the config path to
   `keycloak/prod/railway.toml`. (Yes, the same file for every env — the
   directory name is historical; this Dockerfile now serves all envs.)
4. Under **Settings → Networking**, enable a public domain. Note the
   generated domain (e.g. `hms-keycloak-dev.up.railway.app`).

### 2. Provision Keycloak's Postgres

1. **+ New → Database → Postgres** in the same project.
2. Name it `hms-keycloak-<env>-db`.
3. On the service, open **Variables** and copy the auto-generated
   `DATABASE_URL`.
4. **Important:** do NOT share this DB with `hms-db-<env>`. Keycloak owns
   its own schema and account store.

### 3. Set the `hms-keycloak-<env>` service env vars

Under **Variables**, add:

| Variable | Value | Notes |
| --- | --- | --- |
| `BUILD_CONFIG` | `dev` / `uat` / `prod` | Forwarded to the Dockerfile `ARG`. Drives the `KC_HMS_ENV` runtime tag and the `com.bitnesttechs.hms.env` image label. |
| `KC_DB_URL` | `jdbc:postgresql://${{hms-keycloak-<env>-db.PGHOST}}:${{hms-keycloak-<env>-db.PGPORT}}/${{hms-keycloak-<env>-db.PGDATABASE}}` | Reference-style so Railway wires it automatically. |
| `KC_DB_USERNAME` | `${{hms-keycloak-<env>-db.PGUSER}}` | |
| `KC_DB_PASSWORD` | `${{hms-keycloak-<env>-db.PGPASSWORD}}` | |
| `KC_HOSTNAME` | `https://hms-keycloak-<env>.up.railway.app` | Must match the public domain exactly, including scheme. |
| `KC_BOOTSTRAP_ADMIN_USERNAME` | `kc-admin` (or your convention) | Used once on first boot. |
| `KC_BOOTSTRAP_ADMIN_PASSWORD` | (generate 32 random bytes, base64) | **Rotate within 24 h** via the admin console. |
| `KC_LOG_LEVEL` | `INFO` (`DEBUG` only for `dev` while troubleshooting) | Don't leave `DEBUG` on in uat/prod. |

> The `PORT` env var is injected by Railway; the Dockerfile's entrypoint
> already maps it onto `KC_HTTP_PORT`. Do not set it manually.

### 4. First boot

Deploy. Expect ~3 min (Keycloak build step is cached; DB migration runs
on first start). Watch the logs for:

- `Listening on http://0.0.0.0:${PORT}`
- `Imported realm hms`
- `Keycloak ... started in ... ms`

Hit `https://<domain>/realms/hms/.well-known/openid-configuration` and
confirm the JSON comes back.

### 5. Lock down the admin console

1. Log into the admin console with `KC_BOOTSTRAP_ADMIN_USERNAME` +
   `KC_BOOTSTRAP_ADMIN_PASSWORD`.
2. Create a named per-person admin user (first + last name, unique
   email) with `realm-admin` role.
3. **Delete** (or at minimum disable + rotate password on) the
   bootstrap admin account. Remove the bootstrap env vars from the
   Railway service — Keycloak only reads them on first boot, they are
   dead weight afterwards.
4. **Prod only:** enable Cloudflare Access or a Railway Private Network
   rule limiting `/admin` to known IPs. The public realm endpoint
   (`/realms/hms/**`) must stay open for OIDC discovery + token
   issuance. Dev/uat may leave `/admin` open for engineering access; do
   not skip this step in prod.

### 6. Wire the matching backend

On the matching `hms-backend-<env>` Railway service, add:

```bash
OIDC_ISSUER_URI=https://hms-keycloak-<env>.up.railway.app/realms/hms
OIDC_AUDIENCE=hms-backend
```

On next deploy, `OidcResourceServerConfig` activates and the app
accepts both legacy and Keycloak-issued bearer tokens (per phase 2.1,
already shipped). Without these, the backend stays on the legacy auth
path — that is the correct rollback posture.

### 7. Run the migration script

Run [`scripts/keycloak-migration`](../../scripts/keycloak-migration/)
in dry-run, then live, against the new realm. Acceptance: zero
`failed`, zero unexpected `orphaned`, full round-trip
(`POST /users` → JWT → backend filter → `permittedHospitalIds`)
green per Phase 2.7's KC checks 1–5 in
[../../docs/keycloak-implementation-gaps.md](../../docs/keycloak-implementation-gaps.md).

### 8. Wire the portal + mobile apps

Update the respective runtime configs to point at the env's issuer URL
and flip the SSO flag ON. Dev/uat can flip immediately; prod stays OFF
until Phase 3 cutover (see Phase 2.8.B/C in the gaps doc).

## Ongoing operations

### Updating the realm export

Realm changes merged into `main` → `keycloak/realm-export.json` → next
deploy imports them on startup. Destructive changes (deleting a role or
client) require a manual migration — do not rely on `--import-realm` to
drop objects.

### Backups

Railway Postgres snapshots are automatic. Additionally, export the realm
from each env once per release with:

```bash
kc.sh export --dir /tmp/realm-snapshot --realm hms --users same_file
```

and archive the output alongside the release tag. This is the rollback
artefact referenced in
[../../docs/runbooks/keycloak-migration-runbook.md](../../docs/runbooks/keycloak-migration-runbook.md).

### Secret rotation

Rotate the admin account password every 90 days or after any credential
exposure. `KC_DB_PASSWORD` rotates with the linked Postgres service —
use Railway's built-in rotation; Keycloak reads the value on boot so a
restart is required after rotating.

### Scaling

Start with 1 instance per env. Keycloak scales horizontally but requires
`KC_CACHE=ispn` + a shared cache stack (infinispan) — **not** a drop-in
replacement for the default. Revisit when we see > 50 RPS on the prod
token endpoint.

## Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Boot loops with `HostnameProvider ... strict` | `KC_HOSTNAME` does not match the public URL. | Set `KC_HOSTNAME=https://hms-keycloak-<env>.up.railway.app`; redeploy. |
| `/realms/hms/.well-known/openid-configuration` returns 404 | Realm import didn't run (usually a JSON parse error). | Check boot logs for `Failed to import`; validate `realm-export.json`. |
| Backend rejects tokens with `JWT signature does not match` | JWKS cache stale after realm key rotation. | Restart the matching `hms-backend-<env>` service or wait 5 min (default Nimbus cache). |
| Admin console accessible from anywhere in **prod** | Step 5 access restriction skipped. | Add an access restriction and rotate admin creds before proceeding. |
| Don't know which env an image was built for | Inspect the running container. | `docker inspect <id> --format '{{ index .Config.Labels "com.bitnesttechs.hms.env" }}'` or check the `KC_HMS_ENV` env var inside the container / `[OIDC]` startup line. |

## Related

- Local dev Keycloak: [`docker-compose.yml`](../../docker-compose.yml) (profile `keycloak`)
- Realm source: [`../realm-export.json`](../realm-export.json)
- Redirect URIs: [`../redirect-uris.md`](../redirect-uris.md)
- User migration script: [`../../scripts/keycloak-migration/`](../../scripts/keycloak-migration/)
- Migration runbook: [`../../docs/runbooks/keycloak-migration-runbook.md`](../../docs/runbooks/keycloak-migration-runbook.md)
- Cutover runbook: [`../../docs/runbooks/keycloak-cutover-runbook.md`](../../docs/runbooks/keycloak-cutover-runbook.md)
- Implementation gaps + phase plan: [`../../docs/keycloak-implementation-gaps.md`](../../docs/keycloak-implementation-gaps.md)
