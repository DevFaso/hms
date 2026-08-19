# Keycloak cutover sequence — UAT → prod playbook

> Wraps roadmap rows **18** (KC-4 uat user migration), **19** (KC-4 prod user migration), and **8** (KC-5 backend cutover — `OIDC_REQUIRED=true` flip + legacy `/auth/login` → 410 Gone) into a single ordered sequence.
> Each step has a hard go/no-go gate against the previous step's soak window. **Do not skip a gate** — the existing component runbooks are referenced inline and remain the authoritative procedure for each individual step; this document is the conductor.

**Status:** foundation pass shipped on `chore/v1.0-keycloak-cutover-and-cost-obs`. Rows 8 / 18 / 19 stay `started` until the actual cutover is executed in UAT and prod with the soak gates met — the rows flip to `completed` after the prod cutover smoke is green for 48 h.

---

## Sequence overview

```
1.  KC-4 uat migration (row 18)          ──┐
                                            │  5 business-day soak
2.  KC-4 prod migration (row 19)         ──┤
                                            │  observe prod migration metrics ≥ 24 h
3.  Phase C UAT cutover (row 8 — uat)    ──┤
                                            │  7-calendar-day soak per docs/keycloak-implementation-gaps.md
4.  Phase C prod cutover (row 8 — prod)  ──┘
                                            │  48-hour post-cutover smoke ⇒ flip 8 / 18 / 19 to completed
```

Each step is a separate change-control window. The expected minimum end-to-end duration from step 1 kickoff to all three rows `completed` is **~3 weeks** under a clean run.

---

## Pre-sequence verification

Before kicking off step 1, run the preflight harness:

```bash
HMS_KC_ENV=uat ./scripts/keycloak/preflight.sh
```

The script wraps every precondition in [keycloak-migration-runbook.md](keycloak-migration-runbook.md) and [keycloak-cutover-runbook.md](keycloak-cutover-runbook.md):

- Realm import diff vs `keycloak/realm-export.json` HEAD (R1 from `env-sync-verify.sh`).
- Per-env redirect-URI coverage (P3 from `env-sync-verify.sh`).
- Frontend env-file agreement (R4).
- SMTP roundtrip (sends one test email and tails the inbox via IMAP if `HMS_KC_SMOKE_INBOX` is set).
- `hms_read` read-only Postgres role exists on the env's HMS DB with the three required `SELECT` grants.
- Authenticated A1-A3 checks against the env's realm if `HMS_KC_ADMIN_TOKEN` is set.

Stop and escalate on the first non-zero exit. The script is idempotent and safe to re-run after a fix.

---

## Step 1 — Row 18: KC-4 user migration to UAT

**Goal:** every active HMS DB user has a Keycloak account on `hms-keycloak-uat` with their hospital scope + role assignments preserved, password-reset + email-verify required actions queued.

Procedure: [keycloak-migration-runbook.md](keycloak-migration-runbook.md), applied with `HMS_KC_ENV=uat`. Notable uat-specific overrides:

- Dedicated `migration-admin` user in the master realm (gitignored credential rotated post-migration).
- `hms_read` role on `hms-Postgres-uat` with `SELECT` on `security.users`, `security.user_role_hospital_assignment`, `security.roles`.
- SMTP configured on the uat `hms` realm (Gmail App Password via the same recipe used for hosted-dev in row 17).
- After live run, partial-import realm clients from `keycloak/realm-export.json` to align redirect URIs and web origins (HMS portal + Android + iOS for uat).

**Exit gate:** dry-run summary then live-run summary both show `failed: 0`, expected `created: N`, plausible `orphaned: X`. Spot-check at least three users in the Keycloak admin UI (required actions, attributes, role mappings).

**Soak before step 2:** **5 business days.** No SSO traffic is required during soak; the gate is that no operator manually edits Keycloak state and no incident is opened against the uat realm.

---

## Step 2 — Row 19: KC-4 user migration to PROD

**Goal:** same playbook as step 1, applied to `hms-keycloak-prod` during the announced maintenance window.

Procedure: same [keycloak-migration-runbook.md](keycloak-migration-runbook.md), `HMS_KC_ENV=prod`. Prod-specific overrides:

- Window scheduled per [keycloak-cutover-runbook.md](keycloak-cutover-runbook.md) — **never on a Friday.**
- `pg_dump` of the Keycloak DB taken immediately before the live run (rollback dependency).
- On-call rotation on the bridge with rollback authority + at least one clinical lead.

**Exit gate:** identical to step 1 (failed: 0, etc.) + a 24-hour observation against prod migration metrics (Keycloak admin Sessions count, `hms-keycloak-prod` SMTP egress, no auth-related Sentry events).

**Soak before step 3:** **48 hours.** Step 3 is the OIDC flip — if step 2 left a partial state, step 3 will surface as login failures, not as orphaned accounts.

---

## Step 3 — Row 8 (uat): `OIDC_REQUIRED=true` flip in UAT

**Goal:** flip the backend feature gate `app.auth.oidc.required` from `false` to `true` on uat so legacy `/api/auth/login` and `/api/auth/token/refresh` respond **HTTP 410 Gone** with the runbook message.

Procedure: [keycloak-cutover-runbook.md](keycloak-cutover-runbook.md), `HMS_KC_ENV=uat`. This is the Phase C cutover from `docs/keycloak-implementation-gaps.md`.

**Exit gate:** the smoke script confirms SSO end-to-end, legacy endpoints return 410, and `OidcResourceServerIntegrationTest` + `AuthControllerOidcRequiredTest` are green against the uat realm:

```bash
HMS_KC_ENV=uat ./scripts/keycloak/cutover-smoke.sh
```

**Soak before step 4:** **7 calendar days.** The published Phase C target in [docs/keycloak-implementation-gaps.md](../keycloak-implementation-gaps.md) is "soak 7 days; promote to prod". Use the soak to surface every login-path edge case (Android cold launch, iOS background refresh, browser cookie expiry, the lab printer's machine account if it still uses `/auth/login`).

---

## Step 4 — Row 8 (prod): `OIDC_REQUIRED=true` flip in PROD

**Goal:** apply step 3 in prod. After this flip, the only path to a session is the SSO button. Legacy access tokens stay valid for ≤ 15 min (the access-token TTL) and naturally drain.

Procedure: same [keycloak-cutover-runbook.md](keycloak-cutover-runbook.md), `HMS_KC_ENV=prod`. Prod-specific overrides:

- Announced maintenance window (≥ 24 h comms ahead).
- Comms: "SSO becomes the only login path at `<window-start>`; legacy form will return a 'use Single Sign-On' message after that."
- On-call rotation on the bridge with rollback authority.

**Exit gate:** smoke green, 4xx ratio on `/api/auth/login` returns to ≤ 5 / hour (only the bot scanners) within 6 hours of the flip, no auth-related Sentry / PagerDuty escalation.

**Post-cutover smoke window:** **48 hours.** When the window closes green:

- Flip roadmap rows 8 / 18 / 19 to `completed` in `docs/roadmap.csv` (and re-export the xlsx).
- Tag the prod cutover commit (`kc-cutover-prod-YYYY-MM-DD`) and write the post-mortem under `docs/runbooks/keycloak-cutover-postmortem-YYYY-MM-DD.md`.

---

## Rollback decision tree

| Stage | Symptom | Action |
|---|---|---|
| After step 1 | uat migration metric anomalies (high orphaned count, missing role mappings) | Re-run dry-run against the offending subset; fix in HMS DB; re-run live (idempotent). Soak clock restarts. |
| After step 2 | prod migration `failed > 0` or post-run spot-check fails | Restore the Keycloak DB from the pre-run `pg_dump`. Cancel step 3. Open postmortem. |
| After step 3 | uat login failures during the 7-day soak | Flip `app.auth.oidc.required=false` on uat. Diagnose. Re-flip when fix lands. Soak clock restarts. |
| After step 4 | prod login failures in the 48 h smoke window | Flip `app.auth.oidc.required=false` on prod. Open SEV-1 postmortem. Do NOT roll back the KC-4 migration — those records are stable. |

---

## Reference

- [keycloak-migration-runbook.md](keycloak-migration-runbook.md) — KC-4 procedure (rows 17 / 18 / 19)
- [keycloak-cutover-runbook.md](keycloak-cutover-runbook.md) — KC-5 procedure (row 8)
- [keycloak-env-sync-audit-2026-05-12.md](keycloak-env-sync-audit-2026-05-12.md) — env-sync preconditions
- [keycloak-env-sync-remediation.md](keycloak-env-sync-remediation.md) — when preflight fails
- [keycloak-realm-sync.md](keycloak-realm-sync.md) — realm-export partial-import procedure
- `scripts/keycloak/env-sync-verify.sh` — repo-wide + per-env public + authenticated checks
- `scripts/keycloak/preflight.sh` (new) — wraps every precondition in a single command
- `scripts/keycloak/cutover-smoke.sh` — post-flip smoke test
- `docs/keycloak-implementation-gaps.md` — Phase 3 plan + per-env OIDC_REQUIRED intent table
