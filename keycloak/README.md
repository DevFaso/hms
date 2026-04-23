# Keycloak — Local Dev

> S-03 Phase 1 is already shipped (backend is a resource server). This
> directory lands the local infra so Phase 2 work can start. See
> [../docs/tasks-keycloak.md](../docs/tasks-keycloak.md) for the full
> plan and sprint slicing.

## Contents

| File | Purpose |
|------|---------|
| `realm-export.json` | Full HMS realm (4 clients, 24 roles, TOTP policy, custom claim mappers for `hospital_id` + `role_assignments`, 3 seeded dev users). Imported on first boot. |
| `redirect-uris.md`  | Registered redirect URI matrix per client and environment. Keep this file in sync with `realm-export.json`. |

## Boot

Keycloak is parked behind a docker-compose profile so it never starts
by default (it is a heavy JVM image and most HMS work doesn't need it).

```powershell
docker compose --profile keycloak up -d keycloak
```

Verify:

```powershell
# OIDC discovery
curl http://localhost:8081/realms/hms/.well-known/openid-configuration

# Admin console
start http://localhost:8081/   # admin / admin
```

## Seeded users (dev only)

| Username     | Password        | Roles                            | `hospital_id` attribute |
|--------------|-----------------|----------------------------------|-------------------------|
| `dev.admin`  | `DevAdmin#2026`  | `ROLE_SUPER_ADMIN`, `ROLE_STAFF` | `00000000-…-000000000000` |
| `dev.doctor` | `DevDoctor#2026` | `ROLE_DOCTOR`, `ROLE_STAFF`      | `11111111-…-111111111111` |
| `dev.patient`| `DevPatient#2026`| `ROLE_PATIENT`                   | — |

All three are imported with `temporary: true` — first login forces a
password reset.

## Hooking up the backend

Export the issuer URI and start the backend:

```powershell
$env:OIDC_ISSUER_URI = "http://localhost:8081/realms/hms"
$env:OIDC_AUDIENCE   = "hms-backend"
.\gradlew :hospital-core:bootRun
```

When `OIDC_ISSUER_URI` is unset, the resource-server bean graph is
disabled (see [OidcResourceServerConfig.java](../hospital-core/src/main/java/com/example/hms/config/OidcResourceServerConfig.java))
and the backend behaves exactly as it did before S-03 — so you can
stop and start Keycloak independently of the HMS stack.

## Sanity check — get a token

After Keycloak is healthy:

```powershell
# Direct Access Grants are OFF by default — this uses the admin-cli
# client purely for local smoke tests. Production clients MUST use
# Authorization Code + PKCE.
curl -X POST http://localhost:8081/realms/hms/protocol/openid-connect/token `
  -d "grant_type=password" `
  -d "client_id=admin-cli" `
  -d "username=dev.doctor" `
  -d "password=DevDoctor#2026"
```

(`admin-cli` is not in our realm export; for a real smoke test enable
`Direct Access Grants` on `hms-portal` temporarily, or use the admin
console's "Evaluate" tool to inspect a freshly-issued token.)

## Re-importing after edits to `realm-export.json`

```powershell
docker compose --profile keycloak rm -sf keycloak
docker volume rm hms_keycloak_pgdata     # wipe realm DB — dev only
docker compose --profile keycloak up -d keycloak
```

The `--import-realm` flag on startup is one-shot: it only imports when
the realm does not already exist. To apply changes without wiping the
DB, use the admin console's **Realm → Partial import** with
`Overwrite` strategy.

## Production

**Do not** run this compose service in production. The prod Keycloak
instance is a managed Railway service with:
- HTTPS + trusted certificate.
- Admin console restricted to VPN / IP allow-list.
- Managed Postgres (not the `keycloak-db` container).
- `KC_BOOTSTRAP_ADMIN_*` replaced by a real admin user and the
  bootstrap admin disabled.
- Realm import driven from this same `realm-export.json` by CI/CD,
  not by container startup.

Provisioning of that service is tracked under **P-2** in
[../docs/tasks-keycloak.md](../docs/tasks-keycloak.md).
