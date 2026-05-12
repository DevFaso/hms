# Keycloak Env-Sync Audit — 2026-05-12

> **Branch:** `investigate/keycloak-env-sync-audit`
> **Question asked:** "How is Keycloak not fully synced across develop / uat / main?"
> **Short answer:** The *tracked* Keycloak config is byte-identical across all three branches. The drift is entirely at the **runtime layer** — branch lag on Railway, the one-shot nature of `--import-realm`, opaque per-environment Railway variables, and one-off operator actions (admin lock-down, MFA enrolment, dev-user partial imports). This document enumerates each drift surface and what to check.
>
> **Status:** discovery doc (you are here). The remediation is split across:
> - [keycloak-realm-sync.md](keycloak-realm-sync.md) — the steady-state discipline for any future `realm-export.json` PR.
> - [railway-env-matrix.md](railway-env-matrix.md) — the per-env Railway variable contract.
> - [keycloak-env-sync-remediation.md](keycloak-env-sync-remediation.md) — the one-shot operator playbook (baseline export, uat resync, uat lock-down).
> - [keycloak-implementation-gaps.md §1.1](../keycloak-implementation-gaps.md#11-backend--hospital-core) — now carries the per-env `OIDC_REQUIRED` table.
>
> **Railway project structure note (corrected post-screenshot):** the `MediHub` Railway project uses **one project × three environments × N services per environment**, not three independent services as some older docs imply. Each environment (`dev`, `uat`, `prod`) is its own Railway environment within the same project, with the same service definitions and per-environment variables. The drift surfaces below are the same either way; the remediation runbooks above use the correct structure.

---

## 1. Tracked-config check (the reassuring part)

`keycloak/` and `scripts/keycloak/` resolve to **identical content** on all three branches:

```text
git diff --stat origin/develop origin/uat  -- keycloak/ scripts/keycloak/   → (empty)
git diff --stat origin/develop origin/main -- keycloak/ scripts/keycloak/   → (empty)
git diff --stat origin/uat     origin/main -- keycloak/ scripts/keycloak/   → (empty)
```

Every branch carries the same nine files:

| File | Role |
| ------ | ------ |
| [`keycloak/realm-export.json`](../../keycloak/realm-export.json) | Single source of truth for clients, roles, mappers, TOTP policy. Imported on every container start (`--import-realm`). |
| [`keycloak/realm-export.dev-users.json`](../../keycloak/realm-export.dev-users.json) | Dev-only seed users. **Never** auto-imported (file `_comment` forbids it). |
| [`keycloak/redirect-uris.md`](../../keycloak/redirect-uris.md) | Redirect-URI matrix per client × env. |
| [`keycloak/README.md`](../../keycloak/README.md) | Local-dev runbook. |
| [`keycloak/prod/Dockerfile`](../../keycloak/prod/Dockerfile) | The image used by **all three** Railway services (`hms-keycloak-{dev,uat,prod}`). |
| [`keycloak/prod/entrypoint.sh`](../../keycloak/prod/entrypoint.sh) | Idempotent admin bootstrap + `kc.sh start --optimized --import-realm`. |
| [`keycloak/prod/railway.toml`](../../keycloak/prod/railway.toml) | Same `railway.toml` for all three services. |
| [`keycloak/prod/README.md`](../../keycloak/prod/README.md) | Per-env Railway provisioning recipe. |
| [`scripts/keycloak/cutover-smoke.sh`](../../scripts/keycloak/cutover-smoke.sh) | Cutover smoke test. |

So *what the repo would deploy* is the same shape in every env. That is the easy half.

---

## 2. Branch divergence (where what gets deployed lives)

Even though the keycloak files are identical, the branches themselves are not:

```text
develop ←→ uat   :  0 / 3      (uat is 3 commits ahead of develop)
develop ←→ main  :  0 / 2      (main is 2 commits ahead of develop)
uat     ←→ main  :  3 / 2      (uat 3 ahead of main, main 2 ahead of uat)
```

`develop` is the **trailing** branch; `uat` and `main` have each pulled in commits the other does not have. The Railway service-to-branch mapping (per [`keycloak/prod/README.md`](../../keycloak/prod/README.md) §1) is:

| Service | Branch tracked | Currently HEAD |
| --- | --- | --- |
| `hms-keycloak-dev`  | `develop` | trailing both peers |
| `hms-keycloak-uat`  | `uat`     | unique commits not yet in `main` |
| `hms-keycloak-prod` | `main`    | unique commits not yet in `uat` |

**Implication for Keycloak specifically:** because the keycloak/ paths happen to be identical in this snapshot, the `watchPatterns = ["keycloak/**", ".dockerignore"]` filter in `railway.toml` means Railway will **not** rebuild any of the three services on a normal develop→uat or uat→main promote. Good — but the moment a Keycloak change lands, it deploys to the matching env on the next push to that branch *and not before*. There is no "promote Keycloak" pipeline; it rides whatever happens to be in `develop`/`uat`/`main`.

The only Keycloak-touching commit between `main` and `uat` right now is `6b38d75e promote(develop→uat) 2026-05-12: sonar sweep + v1.0.0-rc1 materials (#316)`, which produced **no net keycloak/ delta** (verified by the empty `git diff --stat` above). So in this snapshot, branch lag is not actively causing realm drift — but the mechanism is fragile by construction.

---

## 3. Runtime drift surfaces (the hard part)

These are the places where develop / uat / main *will* diverge regardless of the repo's state.

### 3a. `--import-realm` is one-shot per realm lifetime

The Dockerfile invokes `kc.sh start --optimized --import-realm`. Per [`keycloak/README.md`](../../keycloak/README.md#re-importing-after-edits-to-realm-exportjson) and [`keycloak/redirect-uris.md`](../../keycloak/redirect-uris.md) §3:

> `--import-realm` is a **one-shot** import: Keycloak only runs it when the realm does not already exist. Restarting the container does not re-apply edits to `realm-export.json`.

So:

- The **first** time each `hms-keycloak-{env}` service booted against an empty Postgres, it imported whatever `realm-export.json` was in that branch at that moment.
- Every subsequent edit to `realm-export.json` (adding a redirect URI, a client scope, a mapper, a role) is **not** picked up by re-deploys. Each env's running realm = first-import + manual partial-imports + admin-console drift since.

This is the largest single drift source. The fix is to deliberately *partial-import* the realm into each env after edits land, per the `redirect-uris.md` change procedure.

### 3b. Realm content drift in the live Postgres

Anything created via the admin console (users, role-assignments, identity providers, custom flows) lives only in that env's `hms-keycloak-<env>-db` Postgres and never round-trips to the repo. There is **no exporter** wired into CI. The README's §"Backups" suggests a manual `kc.sh export` per release, but that is opt-in.

Concrete consequences observed historically:

- **Master-realm admin membership.** Per the memory note `keycloak-recovery-2026-05-09.md`, prod has been locked down (named admin + TOTP MFA, `kc-admin` retired) but **uat lock-down is still pending**. So `master` realm membership in uat ≠ prod. This is by-design transitional state, but it is drift.
- **Dev users** (`realm-export.dev-users.json`). The README explicitly forbids importing this in uat/prod. If anyone partial-imported it via the admin console "to test something," `dev.admin` / `dev.doctor` / `dev.patient` would now exist in that env's `hms` realm. Verify with a direct user-search in the uat/prod admin console.
- **Manually-created users.** Any operator-created users (real engineers, demo accounts) only exist where they were created.

### 3c. Per-service env vars (invisible from the repo)

The `realm-export.json` carries one *concatenated* redirect-URI list for `hms-portal`:

```text
http://localhost:4200/*
https://hms.dev.bitnesttechs.com/*
https://hms.uat.bitnesttechs.com/*
https://hms.bitnesttechs.com/*
```

…and the same shape for `post.logout.redirect.uris`. So redirect-URI drift between envs at the *realm* level should not exist — but the **runtime KC_HOSTNAME** does:

| Service | `KC_HOSTNAME` (per provisioning recipe) |
| --- | --- |
| `hms-keycloak-dev`  | `https://hms-keycloak-dev.up.railway.app`  (Railway often appends env suffix → `…-dev-dev`) |
| `hms-keycloak-uat`  | `https://hms-keycloak-uat.up.railway.app`  |
| `hms-keycloak-prod` | `https://hms-keycloak-prod.up.railway.app` |

These are set in Railway's per-service Variables UI and are **not in the repo**. The frontend env files confirm the actual hostnames in use (Railway did append `-dev-dev` / `-uat-uat` / `-prod-prod`):

| File | `oidc.issuer` |
| --- | --- |
| [`hospital-portal/src/environments/environment.dev.ts`](../../hospital-portal/src/environments/environment.dev.ts) | `https://hms-keycloak-dev-dev.up.railway.app/realms/hms` |
| [`hospital-portal/src/environments/environment.uat.ts`](../../hospital-portal/src/environments/environment.uat.ts) | `https://hms-keycloak-uat-uat.up.railway.app/realms/hms` |
| [`hospital-portal/src/environments/environment.prod.ts`](../../hospital-portal/src/environments/environment.prod.ts) | `https://hms-keycloak-prod-prod.up.railway.app/realms/hms` |

If Railway's domain or KC_HOSTNAME for any one service drifts (rotation, custom-domain swap, accidental env-var edit) and the matching frontend env file isn't updated in lockstep, OIDC discovery breaks for that environment alone — and you can't tell from the repo because half of the contract lives outside it.

Other env vars in the same opaque-bucket: `KC_DB_*` (DB credentials), `KC_BOOTSTRAP_ADMIN_*` (only meaningful pre-lock-down), `BUILD_CONFIG`, `KC_LOG_LEVEL`.

### 3d. Backend OIDC posture per env

Backend OIDC config is **purely env-var-driven**; there are no per-profile YAML defaults. From [`hospital-core/src/main/resources/application.properties`](../../hospital-core/src/main/resources/application.properties):

```text
app.auth.oidc.issuer-uri=${OIDC_ISSUER_URI:}
app.auth.oidc.audience=${OIDC_AUDIENCE:}
app.auth.oidc.required=${OIDC_REQUIRED:false}
```

Empty `OIDC_ISSUER_URI` → resource-server bean graph is *off* and the backend behaves exactly as it did pre-S-03. So drift questions to ask of each `hms-backend-<env>` Railway service:

1. Is `OIDC_ISSUER_URI` set, and does it match the frontend env file's issuer above?
2. Is `OIDC_AUDIENCE=hms-backend` set?
3. Is `OIDC_REQUIRED` `true` or `false`? **This flag controls whether the legacy `/auth/login` issuer is disabled.** If dev has it `true` while uat/prod still have it `false` (or vice-versa), the auth contract differs in a way that won't show up in any test suite that hits only one env.

Per project memory and `docs/keycloak-implementation-gaps.md`, the intended posture as of Phase 2.7 is: dev/uat may flip to OIDC-required immediately; prod stays OFF until Phase 3 cutover. If that hasn't been verified recently, **this is the most likely behavioral drift**.

### 3e. Backend ↔ Keycloak signing-key rotation

`OidcResourceServerConfig` caches the JWKS via Nimbus (default 5 min). When a Keycloak service rotates its realm signing key (manual or automatic per realm policy), the matching `hms-backend-<env>` will reject all tokens with `JWT signature does not match` for up to 5 minutes. If the three Keycloak realms had their first-import key generated at different times (they did — first-boot timestamp per env), key-rotation calendars are also out of sync. Not a steady-state drift, but a per-env synchronisation hazard worth tracking.

---

## 4. The drift map (TL;DR)

| Drift surface | Tracked in repo? | Current state | Action to verify |
| --- | --- | --- | --- |
| `keycloak/` files on each branch | ✅ | **In sync** — byte-identical | none |
| Branch lag (develop trails uat/main) | ✅ (visible via git) | develop -3 vs uat, -2 vs main; no keycloak delta in arrears | watch on next Keycloak-touching PR |
| Live realm content (clients, mappers, redirect URIs) | ❌ | unknown — `--import-realm` is one-shot | for each env: admin console → Realm Settings → diff against `realm-export.json` |
| Master-realm admin membership | ❌ | known-uneven (prod locked, uat pending) | finish uat lock-down per `keycloak-admin-recovery-2026-05-09.md` |
| Dev users in uat/prod realms | ❌ | unknown | admin console user search for `dev.admin`/`dev.doctor`/`dev.patient` in uat + prod |
| `KC_HOSTNAME` per service | ❌ | inferred from frontend env files | Railway → each `hms-keycloak-<env>` → Variables; cross-check vs `environment.<env>.ts` |
| Backend `OIDC_ISSUER_URI` / `OIDC_AUDIENCE` | ❌ | unknown | Railway → each `hms-backend-<env>` → Variables |
| Backend `OIDC_REQUIRED` flag | ❌ | suspected divergent (per phase plan) | same as above; document the intended per-env value |
| JWKS / signing-key rotation cadence | ❌ | per-env first-boot drift | snapshot current key IDs per realm; document next rotation date |

---

## 5. Recommended next steps

1. **One-time: take a baseline.** For each of `hms-keycloak-{dev,uat,prod}`, `kc.sh export --realm hms` and stash the JSON in `docs/snapshots/` so future drift is *measurable*.
2. **One-time: sync uat to the repo.** Partial-import current `realm-export.json` into uat with `Overwrite` strategy to flush any post-first-boot drift.
3. **Procedural fix:** add a "Realm sync verification" step to the cutover runbook — after every PR that edits `realm-export.json`, partial-import into all three envs and re-run `scripts/keycloak/cutover-smoke.sh`.
4. **Make the env-var contract auditable.** Mirror the expected per-env Railway variables into a *non-secret* checked-in matrix (`docs/runbooks/railway-env-matrix.md` or extend [`railway-services.md`](railway-services.md)) so deviations are findable from the repo.
5. **Close out the uat admin lock-down** (memory `keycloak-recovery-2026-05-09.md`) so master-realm membership matches prod.
6. **Document the `OIDC_REQUIRED` per-env intended value** explicitly in `docs/keycloak-implementation-gaps.md` — today it lives only in phase narrative.

---

## 6. What this audit does *not* cover

- Live realm comparison — needs admin-console access to each env (out of scope for a static-repo audit).
- Railway env-var snapshot — same reason.
- Patient mobile app config (`patient-android-app`, `patient-ios-app`) — verified the redirect URIs in the realm-export match `redirect-uris.md`, but did not cross-check the apps' own `applicationId` / bundle ID against any env-specific build flavor.
- Cloudflare Access / IP allow-list status on prod admin console (step 5d in `keycloak/prod/README.md`).
