# Runbook — Keycloak realm-export sync after `realm-export.json` edits

> Companion to [keycloak-env-sync-audit-2026-05-12.md](keycloak-env-sync-audit-2026-05-12.md).
> Owns the "after a PR touches `keycloak/realm-export.json`, what
> happens in dev / uat / prod?" question. Without this discipline, the
> repo and the running realms drift silently and the only way to find
> out is during an outage.

## Why this exists

[`keycloak/prod/Dockerfile`](../../keycloak/prod/Dockerfile) runs
`kc.sh start --optimized --import-realm`. `--import-realm` is **one-shot
per realm lifetime** — it imports `realm-export.json` only when the
named realm does not already exist in the Postgres backing store. Once
the `hms` realm exists in `hms-keycloak-<env>-db`, every subsequent
container restart, redeploy, and Railway "Restart" button click runs
through this code path and *imports nothing*.

So an edit merged into `realm-export.json` lands in:

- ✅ The repo (visible to anyone)
- ✅ The Docker image baked at next build (visible from `docker inspect`)
- ❌ The running Keycloak realm (invisible until partial-imported)

That third bullet is the entire failure mode.

## When to follow this runbook

After **any** PR that modifies [`keycloak/realm-export.json`](../../keycloak/realm-export.json),
including but not limited to:

- Adding / removing a redirect URI on `hms-portal`, `hms-patient-android`, or `hms-patient-ios`.
- Adding / removing / renaming a realm role.
- Changing client scopes, mappers, or default scopes (especially `hms-claims` / `hms-profile`).
- Modifying the TOTP / password / brute-force policy.
- Adding a new client.
- Any structural change to `users`, `groups`, `clientScopes`, `components`, or `authenticationFlows`.

If a PR touches `realm-export.json` and this runbook is **not** followed,
the change ships to nowhere. The CI does not enforce this — there is no
"diff the running realm against the export" job (yet).

## The procedure

For each environment, in order **dev → uat → prod**:

### 1. Verify the deployed image carries the change

The Railway service for the env must be on a commit that includes the
PR. The MediHub project keeps **per-environment service names** (the
service in env `dev` is named `hms-keycloak-dev`, not bare `hms-keycloak`
— this matches [`keycloak/prod/README.md`](../../keycloak/prod/README.md) §1
and is the reason the public domain has the doubled `-dev-dev` suffix).
Check from a terminal that has Railway CLI access:

```bash
railway environment <env>                       # dev | uat | prod
railway service hms-keycloak-<env>              # hms-keycloak-dev | -uat | -prod
railway logs --tail 200 | grep -E '(Imported realm|Listening on|hms-keycloak-entrypoint)'
```

Or from the Railway UI: project `MediHub` → environment switcher →
`hms-keycloak-<env>` service → **Deployments** tab. The most-recent
"Succeeded" deployment must have a commit SHA at-or-after the PR's
merge commit.

If the SHA is older, redeploy from the Railway UI (Deployments →
**⋯** → **Redeploy**) — Railway's `watchPatterns` filter on
[`keycloak/prod/railway.toml`](../../keycloak/prod/railway.toml) only
fires on changes inside `keycloak/**`, so a `realm-export.json` edit
*will* trigger a build automatically; a non-keycloak commit on the
branch will not. Either way, the new image is built but the **realm
content is not updated** by that rebuild — that's what step 2 fixes.

### 2. Partial-import via the admin console

```text
1. Open https://hms-keycloak-<env>-<env>.up.railway.app/admin/master/console/
2. Switch to the "hms" realm in the realm-switcher.
3. Realm Settings → Action → Partial Import.
4. Upload keycloak/realm-export.json.
5. Strategy: select "Overwrite" (NOT "Skip" — Skip would leave the
   stale fields in place; that defeats the purpose).
6. Tick the resource categories that the PR actually changed:
     - Clients (for redirect URIs, web origins, scopes, mappers)
     - Realm Roles (for role add/rename/delete)
     - Identity Providers (rare)
     - Groups (rare)
7. Click "Import" — review the green / yellow / red counts. Yellow
   ("overwritten") on the resources you expected, zero red.
```

**Do NOT tick "Users"** under any circumstance for uat or prod. The
realm export ships zero users in the `users` array (verified — only
[`keycloak/realm-export.dev-users.json`](../../keycloak/realm-export.dev-users.json)
carries any users, and that file is dev-only by `_comment`), so a
Users-tick partial-import would do nothing in this state — but the
moment someone merges a `realm-export.json` change that *does* include
a `users` block, the same checkbox would silently overwrite live user
records in uat/prod. Treat the checkbox as off-limits regardless.

### 3. Verify with the cutover smoke script

Run the packaged smoke check:

```bash
API_BASE_URL=https://api.hms.<env>.bitnesttechs.com \
ISSUER_URI=https://hms-keycloak-<env>-<env>.up.railway.app/realms/hms \
  scripts/keycloak/cutover-smoke.sh
```

The smoke script does not yet diff the realm content against the
export — its scope is the cutover invariants (see
[keycloak-cutover-runbook.md §3](keycloak-cutover-runbook.md)). For
realm-content diffs, use:

```bash
# Token from the env's named admin (see keycloak-admin-recovery-2026-05-09.md).
# Read password into env (no echo), then send via stdin so it never appears
# in argv / shell history / `ps` output. `--data-urlencode "name@-"` reads
# the value from stdin.
read -rsp "Password for $KC_NAMED_ADMIN: " KC_NAMED_ADMIN_PASSWORD && echo
TOKEN=$(printf '%s' "$KC_NAMED_ADMIN_PASSWORD" | curl -sS -X POST \
  "https://hms-keycloak-<env>-<env>.up.railway.app/realms/master/protocol/openid-connect/token" \
  --data-urlencode "grant_type=password" \
  --data-urlencode "client_id=admin-cli" \
  --data-urlencode "username=$KC_NAMED_ADMIN" \
  --data-urlencode "password@-" \
  | jq -r .access_token)
unset KC_NAMED_ADMIN_PASSWORD

# Pull live client config and diff redirect URIs against the export
curl -sS -H "Authorization: Bearer $TOKEN" \
  "https://hms-keycloak-<env>-<env>.up.railway.app/admin/realms/hms/clients?clientId=hms-portal" \
  | jq '.[0] | { redirectUris, webOrigins, attributes."post.logout.redirect.uris" }'

# Compare to keycloak/realm-export.json:
jq '.clients[] | select(.clientId=="hms-portal") | { redirectUris, webOrigins, attributes."post.logout.redirect.uris" }' \
  keycloak/realm-export.json
```

The two outputs must match exactly. Any drift means step 2 was incomplete or
ticked the wrong resource categories.

### 4. Repeat for the next env

The dev → uat → prod ordering is not optional. If the partial-import on
dev exposes a problem (typo in the export, mapper that breaks an
existing token, redirect URI that conflicts with a currently-running
session), catching it on dev costs nothing; catching it on prod is an
auth incident.

## Edge cases

### A new client was added

A new client doesn't exist in the running realm, so partial-import
will *create* it. No risk to existing clients. Same procedure works.

### A client was deleted from the export

`--import-realm` and partial-import are both **additive** — they
never delete resources. Removing a client from `realm-export.json`
will NOT remove it from the running realm. Manual deletion via the
admin console is required, and that is a destructive action: it
invalidates every existing session for that client. Do not do this
on prod outside a maintenance window.

### Client secrets

Confidential clients (currently only `hms-backend`) have a `secret`
in `realm-export.json`. Partial-importing with "Overwrite" will
**overwrite the live secret with whatever is in the export** — which
is the dev placeholder. This is the single most dangerous footgun
in this runbook. Either:

1. Strip the `secret` field from the export before importing
   (Keycloak will preserve the live one), or
2. Confirm the live secret is the same as the export *before*
   importing (only safe in dev).

For uat / prod confidential-client secret rotation, do it through the
admin console UI under Clients → `hms-backend` → Credentials → Regenerate.
Do not roundtrip secrets through `realm-export.json`.

### Realm-level settings (TOTP policy, brute-force, password policy)

These live under top-level realm fields in the export, not under
`clients`. The Partial Import dialog does not expose them as a
checkbox category. To update them, edit each setting manually under
**Realm Settings → Login / Tokens / Sessions / Security Defenses /
Authentication** and confirm the value matches the export. There is
no automated path for realm-level settings short of a full
`kc.sh import --override true`, which requires shell access to the
Keycloak container that Railway does not provide on the standard
plan.

## After the procedure

1. Update [`keycloak/redirect-uris.md`](../../keycloak/redirect-uris.md) if
   the change touched URIs — that file is the source of truth for the
   matrix and must agree with the export.
2. If the env is uat or prod, snapshot the realm via `kc.sh export`
   per [keycloak-env-sync-remediation.md §1](keycloak-env-sync-remediation.md#step-1--baseline-export-per-environment)
   and stash the JSON in `docs/snapshots/` so the next drift audit
   has a baseline.

## Ownership

- **Author:** runbook authored 2026-05-12 as part of the
  Keycloak env-sync audit (branch `investigate/keycloak-env-sync-audit`).
- **Executor:** the engineer landing the `realm-export.json` PR. The
  PR description must reference this runbook and call out which
  envs have been synced.
- **Reviewer:** PR reviewer must check the description for the
  sync trail before approving.
