# P-2 — Production Keycloak on Railway

> Completes prerequisite **P-2** from
> [../../docs/tasks-keycloak.md](../../docs/tasks-keycloak.md). Unblocks
> KC-4 live migration, the 2.6 soak, KC-5, and KC-6.

## What this ships

- [`Dockerfile`](Dockerfile) — production Keycloak 26.0.7 image with the
  HMS realm baked in at build time.
- [`railway.toml`](railway.toml) — Railway service config pointing at the
  Dockerfile and wiring the `/health/ready` probe.
- This runbook for one-time provisioning + ongoing ops.

Dev-only users (`realm-export.dev-users.json`) are deliberately **not**
included — if they are ever needed in prod, import them manually through
the admin console, never through the auto-import path.

## One-time provisioning (Railway dashboard)

### 1. Create a new Railway service

1. In the HMS Railway project, **+ New → GitHub Repo** → select this repo.
2. Name the service `hms-keycloak`.
3. Under **Settings → Source → Config-as-code**, set the config path to
   `keycloak/prod/railway.toml`.
4. Under **Settings → Networking**, enable a public domain. Note the
   generated domain (e.g. `hms-keycloak-production.up.railway.app`).

### 2. Provision Keycloak's Postgres

1. **+ New → Database → Postgres** in the same project.
2. Name it `hms-keycloak-db`.
3. On the service, open **Variables** and copy the auto-generated
   `DATABASE_URL`.
4. **Important:** do NOT share this DB with `hms-db`. Keycloak owns its
   own schema and account store.

### 3. Set the `hms-keycloak` service env vars

Under **Variables**, add:

| Variable | Value | Notes |
|---|---|---|
| `KC_DB_URL` | `jdbc:postgresql://${{hms-keycloak-db.PGHOST}}:${{hms-keycloak-db.PGPORT}}/${{hms-keycloak-db.PGDATABASE}}` | Reference-style so Railway wires it automatically. |
| `KC_DB_USERNAME` | `${{hms-keycloak-db.PGUSER}}` | |
| `KC_DB_PASSWORD` | `${{hms-keycloak-db.PGPASSWORD}}` | |
| `KC_HOSTNAME` | `https://<your-railway-domain>` | Must match the public domain exactly, including scheme. |
| `KC_BOOTSTRAP_ADMIN_USERNAME` | `kc-admin` (or your convention) | Used once on first boot. |
| `KC_BOOTSTRAP_ADMIN_PASSWORD` | (generate 32 random bytes, base64) | **Rotate within 24 h** via the admin console. |
| `KC_LOG_LEVEL` | `INFO` | `DEBUG` only while troubleshooting — don't leave on. |

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
4. Optionally: enable Cloudflare Access or a Railway Private Network
   rule limiting `/admin` to known IPs. The public realm endpoint
   (`/realms/hms/**`) must stay open for OIDC discovery + token
   issuance.

### 6. Wire the backend

On the `hms-backend` Railway service, add:

```
OIDC_ISSUER_URI=https://<keycloak-domain>/realms/hms
OIDC_AUDIENCE=hms-backend
```

On next deploy, `OidcResourceServerConfig` activates and the app
accepts both legacy and Keycloak-issued bearer tokens (per phase 2.1,
already shipped).

### 7. Wire the portal + mobile apps

Update the respective runtime configs to point at the prod issuer and
flip the SSO flag ON in a separate PR once the soak is clean. Defaults
stay OFF until then.

## Ongoing operations

### Updating the realm export

Realm changes merged into `main` → `keycloak/realm-export.json` → next
deploy imports them on startup. Destructive changes (deleting a role or
client) require a manual migration — do not rely on `--import-realm` to
drop objects.

### Backups

Railway Postgres snapshots are automatic. Additionally, export the
realm once per release with:

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

Start with 1 instance. Keycloak scales horizontally but requires
`KC_CACHE=ispn` + a shared cache stack (infinispan) — **not** a
drop-in replacement for the default. Revisit when we see > 50 RPS on
the token endpoint.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Boot loops with `HostnameProvider ... strict` | `KC_HOSTNAME` does not match the public URL. | Set `KC_HOSTNAME=https://<railway-domain>`; redeploy. |
| `/realms/hms/.well-known/openid-configuration` returns 404 | Realm import didn't run (usually a JSON parse error). | Check boot logs for `Failed to import`; validate `realm-export.json`. |
| Backend rejects tokens with `JWT signature does not match` | JWKS cache stale after realm key rotation. | Restart the `hms-backend` service or wait 5 min (default Nimbus cache). |
| Admin console accessible from anywhere | Step 5 skipped. | Add an access restriction and rotate admin creds before proceeding. |

## Related

- Local dev Keycloak: [`docker-compose.yml`](../../docker-compose.yml) (profile `keycloak`)
- Realm source: [`../realm-export.json`](../realm-export.json)
- Redirect URIs: [`../redirect-uris.md`](../redirect-uris.md)
- User migration script: [`../../scripts/keycloak-migration/`](../../scripts/keycloak-migration/)
- Migration runbook: [`../../docs/runbooks/keycloak-migration-runbook.md`](../../docs/runbooks/keycloak-migration-runbook.md)
