# Copilot review — `investigate/keycloak-env-sync-audit` (2026-05-12)

Working notes on the Copilot review that landed on the env-sync
audit PR. Each comment is summarised, then marked ✅ (fixed in this
branch), ⚠️ (acknowledged with caveat / partial fix), or ❌ (disagree
— left unchanged with reasoning).

---

## ✅ #1 — Service naming: `railway service hms-keycloak` vs `hms-keycloak-<env>` (Medium)

**File:** `docs/runbooks/keycloak-realm-sync.md` lines +52 to +56
**Copilot:** This runbook assumes Railway uses environments with
shared service names; other repo docs describe per-env services
named `hms-keycloak-<env>`. Please align.

**Resolution:** Fixed. The audit doc already used `hms-keycloak-dev/-uat/-prod`
(matching `keycloak/prod/README.md` §1 and the doubled-suffix Railway
domain like `hms-keycloak-dev-dev.up.railway.app`); my new realm-sync
runbook had drifted to bare `hms-keycloak`. Rewrote the CLI examples
to use `railway service hms-keycloak-<env>` and added a short
explainer of why the names are per-env (legacy from the
pre-environments setup, preserved for continuity).

---

## ❌ #2 — "Tables use `||` row prefix" in audit doc (Medium)

**File:** `docs/runbooks/keycloak-env-sync-audit-2026-05-12.md` lines +29-33, also +55, +90
**Copilot:** Tables start with `||` so GitHub renders an extra empty
column. Replace `||` with `|`.

**Resolution:** Disagree — false positive. Verified with
`grep -nF '||' docs/runbooks/keycloak-env-sync-audit-2026-05-12.md`:
zero matches. The on-disk tables use single pipes consistently.
Whatever Copilot saw in its rendered preview was not `||` in the
source. **Did not change** — the tables render correctly on GitHub
when viewed manually.

> Sanity check anyone can run from the repo root:
> `grep -nF '||' docs/runbooks/keycloak-env-sync-audit-2026-05-12.md` → empty.

---

## ❌ #3 — Same "double pipe" claim on the `OIDC_REQUIRED` table (Medium)

**File:** `docs/keycloak-implementation-gaps.md` lines +348-352
**Copilot:** The `OIDC_REQUIRED` intent table starts with `||`.

**Resolution:** Disagree — same false positive as #2. Same grep
applied to `docs/keycloak-implementation-gaps.md` returns zero `||`
matches. **Did not change.**

---

## ✅ #4 — Snapshots may contain secrets, but runbook says "check them in" (HIGH)

**File:** `docs/runbooks/keycloak-env-sync-remediation.md` lines +62-70
**Copilot:** Realm exports can include `clients[].secret`, SMTP
creds, signing keys. Runbook recommended committing them. Add
sanitization or avoid committing.

**Resolution:** Fixed. This was the most important comment and the
original wording was straightforwardly wrong. Restructured Step 1
into a two-tier flow:

1. **Raw exports** are written to `docs/snapshots/keycloak/raw/`
   which is **gitignored** (the runbook now creates the gitignore
   alongside the directory in §1a). These carry the live secrets
   and stay on the operator's laptop.
2. **Redacted exports** are produced by a `jq` filter that strips
   every known secret-bearing field (`secret`, `password`,
   `privateKey`, `certificate`, `trustStorePassword`, `smtpServer`,
   `credentials`) and written to `docs/snapshots/keycloak/` as
   `*.redacted.json`. Only these are committed.
3. A **verification loop** runs after redaction and prints any
   leaked field name; the runbook gates the commit on that loop
   producing zero output.
4. The §"After all three steps" commit invocation now uses
   `git status --short` to confirm nothing from `raw/` slipped in.

Also added an explicit warning at the top of Step 1 calling out the
known secret-bearing fields, plus a reminder to extend the redaction
filter if a future Keycloak release introduces a new one.

---

## ✅ #5 — "Full realm export" claim doesn't match what the curl actually fetches (Medium)

**File:** `docs/runbooks/keycloak-env-sync-remediation.md` lines +98-106
**Copilot:** Comment says "Full realm export including roles/scopes/components"
but procedure only hits `GET /admin/realms/hms` (and later `/clients`).

**Resolution:** Fixed. Copilot was correct — `GET /admin/realms/<realm>`
returns realm-level config only (no clients, roles, scopes, groups,
identity providers). Switched to the actual `kc.sh export` API
equivalent: `POST /admin/realms/hms/partial-export?exportClients=true&exportGroupsAndRoles=true`,
which is what we want for a full snapshot. Kept the separate
`/clients` call for per-client detail (operator-friendly attributes
the partial-export trims). Updated the surrounding prose to match
what the calls actually produce.

---

## ✅ #6 — `memory/keycloak-recovery-2026-05-09.md` does not exist in the repo (Medium)

**File:** `docs/runbooks/keycloak-env-sync-remediation.md` lines +375-379
**Copilot:** Refers to a `memory/` path that isn't in the repo.
Update to the actual in-repo doc.

**Resolution:** Fixed in two places. The `memory/` path was leaking
the operator's private notes location (Claude memory directory) and
is not findable from the repo. Replaced both references with the
in-repo runbook `docs/runbooks/keycloak-admin-recovery-2026-05-09.md`:

- §5g of the remediation runbook now updates the in-repo
  admin-recovery doc and includes a short note explaining the
  earlier draft's bad reference.
- §3b of the audit doc replaced "Per the memory note ..." with a
  direct link to `keycloak-admin-recovery-2026-05-09.md` plus a
  forward link to the remediation runbook §5 that closes the drift.

Verified `find . -name 'keycloak-recovery-2026-05-09*'` returns
nothing; the canonical file is `keycloak-admin-recovery-...`.

---

## ✅ #7 — Token-fetch curl puts admin password on the command line (Medium)

**File:** `docs/runbooks/keycloak-realm-sync.md` lines +115-119
**Copilot:** Embeds `password=$KC_NAMED_ADMIN_PASSWORD` in the curl
command line — leaks via shell history / `ps`. Use `read -rsp`
and stdin.

**Resolution:** Fixed in both runbooks (the same anti-pattern was
present in `keycloak-env-sync-remediation.md` Step 1b — Copilot
flagged only the realm-sync version, but the fix applies to both).
New shape:

```bash
read -rsp "Password for $ADMIN_USER on $ENV: " ADMIN_PASS && echo
TOKEN=$(printf '%s' "$ADMIN_PASS" | curl -sS -X POST \
  "${KC_HOST}/realms/master/protocol/openid-connect/token" \
  --data-urlencode "grant_type=password" \
  --data-urlencode "client_id=admin-cli" \
  --data-urlencode "username=${ADMIN_USER}" \
  --data-urlencode "password@-" \
  | jq -r .access_token)
unset ADMIN_PASS
```

The password value comes from stdin via `--data-urlencode "password@-"`
(curl's stdin form for that flag), so it never appears in `argv`,
shell history, or `ps` output. `unset ADMIN_PASS` clears the env
var afterwards.

---

## ✅ #8 — Same naming inconsistency in env-matrix tree diagram + table (Medium)

**File:** `docs/runbooks/railway-env-matrix.md` lines +24-28, +63
**Copilot:** Tree shows `hms-keycloak` / `hms-backend` / `hospital-portal`
as services; older docs use per-env names. Standardize.

**Resolution:** Fixed. Same root cause as #1. Updated:

- The tree diagram (§Project structure) to show
  `hms-keycloak-<env>`, `hms-backend-<env>`, `hospital-portal-<env>`.
- All three section headers (`## 1. hms-keycloak-<env>`, etc.).
- Cross-references inside the matrix that previously said "the
  `hms-keycloak` `KC_HOSTNAME`" now say "the `hms-keycloak-<env>`
  `KC_HOSTNAME`".
- The drift-detection loop's `for svc in ...` list now expands
  `hms-keycloak-$env hms-backend-$env hospital-portal-$env`.
- Added an explicit § "Why per-env service names" explainer with
  the same justification as #1's fix (legacy from pre-environments
  Railway setup, doubled-suffix domain as the visible tell).

Also retroactively updated the audit doc's structure-note paragraph,
which previously claimed "shared service names across envs" and
contradicted both the older docs and the screenshot the user
provided. The audit now cites the env-matrix as the canonical layout.

---

## Net-net

| Severity | Count | Status |
| --- | --- | --- |
| HIGH | 1 | ✅ fixed |
| Medium | 7 | 5 ✅ fixed, 2 ❌ disagreed (false positives on the double-pipe claim — see #2 / #3) |

Files touched in the follow-up commit:

- `docs/runbooks/keycloak-realm-sync.md` (#1, #7)
- `docs/runbooks/railway-env-matrix.md` (#8)
- `docs/runbooks/keycloak-env-sync-remediation.md` (#4, #5, #6, #7)
- `docs/runbooks/keycloak-env-sync-audit-2026-05-12.md` (#6, structure-note correction from #8)

No code changes. The two disagreements (#2, #3) are documented above
with the grep that proves the `||` claim is false; if Copilot still
flags them on re-review, the response is "see this section."
