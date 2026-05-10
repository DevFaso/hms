# Disaster Recovery Runbook

> v1.0 / Operations / DR runbook (roadmap row 9). Closes the v1.0 GA
> exit criterion **"DR drill executed — restore from snapshot in
> <30 min, RPO ≤ 24 h"**.
>
> Companion to:
> - [`railway-services.md`](./railway-services.md) — service map,
>   redeploy mechanics, build cache fixes.
> - [`grafana-observability.md`](./grafana-observability.md) — alert
>   rules and SLO dashboards.
> - [`docs/observability/splunk.md`](../observability/splunk.md) — log
>   ingestion path.
> - [`keycloak-cutover-runbook.md`](./keycloak-cutover-runbook.md) — KC
>   realm export/import path used in step 3 of full-stack restore.

## When to use this runbook

- **Database corruption / accidental destructive query** — Postgres
  PITR (§ 3).
- **A region or single Railway service is offline beyond 30 min** —
  service-level redeploy from last known good (§ 4).
- **Whole project loss** (Railway project deleted, root account
  compromised) — full rebuild (§ 5).
- **Observability stack lost** but the application is healthy — § 6.
- **Encryption key (`APP_ENCRYPTION_KEY`) lost or rotated incorrectly**
  — § 7. PHI-bearing columns become unreadable; this is a separate
  recovery from a generic data restore.

If none of the above match, this is probably not a DR scenario — check
the [Grafana observability runbook](./grafana-observability.md) for
the matching alert.

---

## 1. RPO / RTO targets (v1.0)

| Metric | Target | Source |
|---|---|---|
| **RPO** (max acceptable data loss) | ≤ 24 h | `docs/roadmap.md` v1.0 exit criterion #3 |
| **RTO** (time to restore) | ≤ 30 min for service-level restore; ≤ 4 h for full project rebuild | Same. The 30-min figure is for **redeploy from a known-good build** plus DB restore from the latest hourly Railway snapshot. |
| **Backup cadence — Postgres** | Continuous WAL archiving + automated base snapshots ≥ every 6 h (Railway Pro) | Railway Postgres add-on default. |
| **Backup cadence — application code** | Per-commit, `origin` is the canonical store | GitHub. |
| **Backup cadence — Keycloak realm** | Manual export weekly + before every realm config change | `keycloak/prod/Dockerfile` + `keycloak-cutover-runbook.md` § "Export realm before cutover". |
| **Backup cadence — Splunk HEC events** | 365-day retention on the HEC index (Splunk Cloud default) | `docs/observability/splunk.md`. |
| **Backup cadence — Grafana dashboards & alert rules** | File-provisioned (`grafana/provisioning/**`), `origin` is canonical | Repo. |

> If a real incident exceeds either target, file a P1 post-mortem and
> link it back to this runbook so the cadence / scripts can be tightened.

## 2. DR drill — semi-annual checklist

Run this **at least every 6 months** and within 14 days of any of:
- Adding a new Railway service to the `hms` project.
- Adding or changing a PHI-bearing column / encryption key.
- Hiring or off-boarding anyone with access to the Railway / Splunk /
  GitHub / Keycloak admin consoles.

**Drill scope** (uncheck and reset before each run):

- [ ] **§ 3.1 Postgres PITR to a fresh database** — restore the
      production WAL stream into a *new* Railway Postgres instance
      and confirm Liquibase `databasechangelog` ends at the changeset
      that was current at the chosen restore point. Verify the latest
      `id`, `author`, and `filename` values match what the production
      backend reports on `/api/actuator/info` for the same release.
      (HMS uses Liquibase exclusively; Flyway is disabled in every
      profile and there is no `flyway_schema_history` table.)
- [ ] **§ 4 Service redeploy** — pick the `hms-backend` service in
      Railway, redeploy the last green build from one week prior, and
      observe the `HmsErrorBudgetSlowBurn` alert reset within 5 min.
- [ ] **§ 5 Full rebuild dry-run** — provision a `hms-dr-test`
      Railway project, point the three services at the production
      Dockerfiles, restore the test Postgres, and curl
      `/api/actuator/health` returns `200 {"status":"UP"}`.
- [ ] **§ 6 Observability rebuild** — re-provision Grafana from
      `grafana/provisioning/**` against a fresh stack and confirm at
      least the **SLO & Golden Signals** dashboard renders with data.
- [ ] **§ 7 Encryption-key rotation rehearsal** — rotate
      `APP_ENCRYPTION_KEY` in `hms-dr-test` per the offline
      re-encryption procedure and confirm a sample patient row's
      `address` field decrypts correctly post-rotation.

Record the drill result + total wall-clock time in
`docs/observability/dr-drill-log.md` (create on first run).

---

## 3. Postgres restore (PITR)

The most common DR scenario: a destructive query, a corrupt migration,
or an attacker dropping rows. Railway's managed Postgres ships
continuous WAL archiving + scheduled base backups; restore is
non-destructive (always to a *new* database) so the live one stays
intact for forensics.

### 3.1 Restore to a fresh Postgres

1. **Identify the restore point.** Open the Postgres service in the
   Railway dashboard → **Data** tab → **Backups**. Pick the latest
   automated snapshot **before** the destructive event. Confirm the
   timestamp is within RPO (≤ 24 h ago).
2. **Trigger the PITR restore.** Click **Restore** → **Point-in-time
   restore** → set the target timestamp ≤ 1 minute before the bad
   write. Railway provisions a *new* `hms-postgres-restore-<ts>`
   instance with its own DATABASE_URL.
3. **Validate the restored DB before cutover.** From a local shell:
   ```bash
   psql "$RESTORE_DB_URL" -c "SELECT COUNT(*) FROM clinical.patients WHERE is_deleted = FALSE;"
   psql "$RESTORE_DB_URL" -c "SELECT id, author, filename, dateexecuted FROM databasechangelog ORDER BY dateexecuted DESC LIMIT 5;"
   ```
   The `databasechangelog` query confirms Liquibase state. If the most
   recent entry is the migration *before* the bad one, you're good.
4. **Cut over.** Two paths:
   - **Hot-cutover (RTO ≤ 30 min):** in the `hms-backend` service
     → **Variables** tab → update `DATABASE_URL` to the restored
     instance. Railway redeploys automatically on env-var change.
     Watch `/api/actuator/health` come back UP.
   - **Cold-cutover (RTO ≤ 4 h):** if the issue is application-side
     (bad migration, schema drift) re-tag the prior application
     image first, then point at the restored DB. See § 4.
5. **Decommission the corrupted DB** only after at least one full
   business-day soak with the restored instance. Keep the corrupt
   instance read-only for forensics.

### 3.2 If Liquibase changelog is ahead of the restored data

Symptom: `databasechangelog` references migrations whose tables don't
exist in the restored data, or vice versa.

Cause: the destructive event happened *during* a migration apply.

Fix:
1. Identify the offending changeset id (last entry in
   `databasechangelog`).
2. `DELETE FROM databasechangelog WHERE id = '<bad-id>';` on the
   restored DB.
3. Boot the application; Liquibase will replay the changeset on next
   startup.
4. If the migration is itself the bug, revert the changeset XML in a
   hotfix branch off `main` and redeploy.

### 3.3 Encrypted columns after restore

The restored DB carries the same ciphertext as the source. As long as
`APP_ENCRYPTION_KEY` on the cut-over backend matches the key used
when the rows were *written*, decryption works transparently.

If the key rotated between the write and the restore (rare, but
possible during incident response): **§ 7**.

### 3.4 Export-to-file (out-of-Railway backup)

Used by **§ 5** (full project rebuild) when the Railway project itself
is gone and you need a Postgres dump that lives outside Railway's
managed snapshot store.

**Cadence:** at least monthly, ideally weekly. Drop the resulting
`backup.dump` file into the secret-manager / sealed offline store
(same place that holds `APP_ENCRYPTION_KEY`).

**Procedure:**

1. Get the production `DATABASE_URL` from Railway → Postgres service →
   **Variables**. The connection string already includes the password;
   keep it in the on-call laptop's keychain, not the shell history.
2. Run `pg_dump` from a machine with network reach to the Railway
   Postgres (or via a Railway shell session):

   ```bash
   pg_dump --format=custom --no-owner --no-privileges \
     --file "backup-$(date -u +%Y%m%dT%H%M%SZ).dump" \
     "$DATABASE_URL"
   ```

   `--format=custom` produces the file `pg_restore` reads in § 5; the
   `--no-owner --no-privileges` flags strip Railway-side role
   ownership so the restore on a fresh project works without role
   pre-creation.
3. Verify the dump opens: `pg_restore --list backup-*.dump | head -50`
   should show every schema (`billing`, `clinical`, `hospital`, `lab`,
   `platform`, `reference`, `scheduling`, `security`, `support`,
   `governance`, `empi`, `integration`).
4. Hash the dump (`sha256sum backup-*.dump`) and record the hash
   alongside the dump in the secret store so a tampered restore is
   detectable later.
5. Encrypt at rest before uploading to anywhere that isn't the secret
   store — `gpg --symmetric --cipher-algo AES256 backup-*.dump` is
   the project's documented baseline.

**Restore back from the dump (during § 5 step 3):**
```bash
pg_restore --clean --no-owner --no-privileges \
  --dbname "$NEW_DATABASE_URL" backup.dump
```
The `--clean` flag drops any pre-existing matching objects in the
target so the new project starts identical to the source. Skip
`--clean` if you're restoring into a known-empty database.

---

## 4. Service-level redeploy

Use when a single Railway service (backend / frontend / Keycloak) is
unhealthy but the database is fine.

### 4.1 Roll back to a known-good build

1. Open the service in Railway → **Deployments** tab.
2. Find the last deployment marked SUCCESS *before* the regression.
3. Click the kebab menu → **Redeploy**.
4. Watch the build progress. Healthcheck endpoints:
   - `hms-backend`: `/api/actuator/health` → 200 `{"status":"UP"}`.
   - `hospital-portal`: `/` → 200 (serves the SPA `index.html`).
   - `hms-keycloak`: `/health/ready` → 200.
5. Confirm the user-facing flow:
   ```bash
   curl -i https://<frontend-url>/api/auth/csrf-token
   curl -i https://<frontend-url>/api/actuator/health
   ```

### 4.2 If the redeploy is also failing

The build cache may be poisoned (see **railway-services.md** §
"When a build fails with the wrong Dockerfile"). Clear the cache via
Railway dashboard → service settings → **Clear build cache** → then
redeploy.

### 4.3 If the issue is the new image itself

Redeploy from the **commit before** the regression. From local:
```bash
git checkout <last-good-sha>
git push origin HEAD:hotfix/dr-rollback
```
Then point the Railway service's **Watch Branch** at
`hotfix/dr-rollback` (temporary) and trigger a deploy. Revert the
watch-branch back to `main` once the real fix lands.

---

## 5. Full project rebuild

Use when the entire Railway project is gone (deleted, root account
compromised, org dispute).

**Prerequisites** — these must be recoverable from outside Railway:
- GitHub repo access (`origin = https://github.com/DevFaso/hms.git`).
- The current production secrets (see § 7 for the inventory).
- The latest Postgres backup file produced by **§ 3.4** (out-of-Railway
  `pg_dump`), retrieved from the secret-manager / sealed offline store.
- The latest Keycloak realm export (`keycloak/prod/realm-export.json`
  in the repo, or the most recent manual export).

**Steps:**
1. **Provision a new Railway project** named `hms`.
2. **Add the three services** per
   [`railway-services.md`](./railway-services.md):
   - `hms-backend` — repo root, `Dockerfile`.
   - `hospital-portal` — root dir `hospital-portal`, its own
     `Dockerfile`.
   - `hms-keycloak` — repo root, `keycloak/prod/Dockerfile`.
3. **Add the Postgres add-on**, then restore from the backup file:
   ```bash
   pg_restore --clean --no-owner --no-privileges \
     --dbname "$NEW_DATABASE_URL" backup.dump
   ```
4. **Wire env vars** — every secret listed in § 7. Railway → service
   → **Variables**. Spot-check `DATABASE_URL`, `JWT_SECRET`,
   `APP_ENCRYPTION_KEY`, `OIDC_ISSUER_URI`, `KC_DB_URL`.
5. **Boot Keycloak** and import the realm (the `entrypoint.sh` reads
   `keycloak/prod/realm-export.json` automatically when the realm
   is missing).
6. **Boot the backend** — Liquibase reapplies any post-backup
   migrations idempotently (every changeset is guarded by `IF NOT
   EXISTS` / `pg_constraint` lookups; see V63, V92, V93 for the
   pattern).
7. **Boot the frontend** — env-var-only change, no DB.
8. **Cut DNS over** to the new project's public domains.
9. **Validate end-to-end** (login, doctor dashboard load, one
   prescription create) before announcing recovery.

Expected wall-clock: ~4 h with two on-call engineers, dominated by
DNS propagation + Postgres restore time for a multi-GB backup.

---

## 6. Observability stack rebuild

Use when the Grafana / Splunk / OTel side is lost but the application
is healthy. None of these are on the critical path for clinical
operation; restore them on a normal-business-hours timeline.

### 6.1 Grafana (file-provisioned)

```bash
# Local stack (development) — used by the team for dashboard editing.
cd grafana/
docker compose up -d
```

Production runs Grafana Cloud. Dashboards and alert rules live in the
repo under [`grafana/provisioning/**`](../../grafana/provisioning) so
the canonical source survives any incident. There is **no automated
sync script** — re-applying after a Grafana-side loss is a manual
import:

1. Open Grafana Cloud → **Dashboards** → **New** → **Import**.
2. For each `*.json` file under `grafana/provisioning/dashboards/`,
   click **Upload JSON file**, pick the file, then **Load** → set the
   target folder to match the file's repo subdirectory (e.g.
   `slo`, `api`, `postgres`) → **Import**.
3. For alert rules, open **Alerting** → **Alert rules** → **New rule
   from file** and upload each YAML under
   `grafana/provisioning/alerting/`. The contact-point and
   notification-policy YAMLs go through the same surface (**Contact
   points** and **Notification policies** tabs).
4. Smoke-test by opening the **SLO & Golden Signals** dashboard — it
   should render with backend metrics within 1–2 minutes of the
   re-import once the OTel exporter is also live (see § 6.3).

If the team automates this later (a `grafana-sync.sh` driving the
Grafana Cloud HTTP API would be the natural shape), update this
section to point at the script and keep the manual fallback below it
as a last resort.

### 6.2 Splunk HEC

Backend logging fails open: with `SPLUNK_HEC_ENABLED=true` but the HEC
endpoint unreachable, Logback drops the events to the local console
appender. Application stays healthy.

Recovery:
1. Confirm the HEC token is still valid (Splunk Cloud → **Data
   Inputs** → **HTTP Event Collector**).
2. Update `SPLUNK_HEC_TOKEN` and `SPLUNK_HEC_URL` env vars on
   `hms-backend` if they rotated.
3. Restart the backend service so the logback config re-resolves the
   token.

### 6.3 OpenTelemetry

OTel exporter pushes to Grafana Cloud OTLP endpoint. Same fail-open
behaviour as Splunk. Re-validate the `OTEL_EXPORTER_OTLP_ENDPOINT`
env var on `hms-backend`.

---

## 7. Encryption-key recovery

PHI-bearing columns (V53/V54/V55 — `dispenses.notes`, `prescription`
PHI, `patient` PHI) are encrypted at rest with `APP_ENCRYPTION_KEY`
via `EncryptedStringConverter`. Losing this key means **none of the
ciphertext can be decrypted**, even from a clean DB snapshot.

### 7.1 Inventory of encrypted columns (15 total — keep in sync with V53/V54/V55)

| Schema | Table | Column |
|---|---|---|
| clinical | dispenses | notes |
| clinical | prescriptions | notes |
| clinical | prescriptions | override_reason |
| clinical | prescriptions | instructions |
| clinical | patients | address |
| clinical | patients | address_line1 |
| clinical | patients | address_line2 |
| clinical | patients | emergency_contact_name |
| clinical | patients | emergency_contact_phone |
| clinical | patients | emergency_contact_relationship |
| clinical | patients | allergies |
| clinical | patients | medical_history_summary |
| clinical | patients | care_team_notes |
| clinical | patients | chronic_conditions |
| security | user_mfa_enrollments | totp_secret |

(See `docs/security-hardening-plan.md` for the original column
inventory and `EncryptedStringConverter` for the implementation.)

### 7.2 Lost-key recovery

1. **Check secret backups** — production secrets are mirrored to a
   sealed offline store (refer to your team's secret-manager runbook;
   this repo deliberately does not document where it is).
2. **If the key is genuinely unrecoverable**, the affected columns
   are gone. The non-PHI columns of every affected row are still
   readable, but the encrypted ones must be re-collected from the
   patient (re-intake) or zeroed out.
3. **File a Tier-4 incident** per `docs/security-hardening-plan.md`
   and notify the data-protection officer per Burkina Faso 2021 data
   law / ANSSI requirements.

### 7.3 Planned key rotation

Single-key converter today. Rotation requires offline re-encryption:
1. Stop the backend (maintenance window).
2. Run a one-shot Spring Boot CommandLineRunner that reads every row
   in the 15 columns above, decrypts with the OLD key, re-encrypts
   with the NEW key, and writes back.
3. Update `APP_ENCRYPTION_KEY` to the NEW key.
4. Restart the backend.

A multi-key converter (read OLD or NEW, write NEW) is the better
design and is tracked in
[`docs/security-hardening-plan.md`](../security-hardening-plan.md) as
a follow-up. Do not attempt online rotation with the current
single-key converter — the half-rotated state is unreadable.

---

## 8. Tabletop scenarios

Run one of these per drill (rotate through them):

### Scenario A — Dropped patient table

> "An ops engineer ran `DELETE FROM clinical.patients WHERE
> hospital_id = '<uuid>';` on prod against the wrong tenant."

**Expected response:** § 3.1 PITR to a timestamp 1 minute before the
DELETE, validate row counts, hot-cutover. RTO target: 30 min.

### Scenario B — Bad migration shipped to prod

> "PR #X added a destructive ALTER TABLE that wiped a column."

**Expected response:** § 3.2 (changelog ahead of data) — restore
Postgres to before the migration applied, revert the migration in a
hotfix branch off `main`, redeploy backend.

### Scenario C — Railway project deleted

> "An admin clicked 'Delete project' on the wrong Railway project."

**Expected response:** § 5 full rebuild. RTO target: 4 h.

### Scenario D — `APP_ENCRYPTION_KEY` rotated without re-encryption

> "Operator rotated the key but forgot the offline re-encryption
> step."

**Expected response:** § 7.3 — roll back the key to the prior value
from the secret store; PHI columns become readable again. If the
prior key is gone, § 7.2 lost-key recovery (Tier-4 incident).

### Scenario E — Splunk HEC down for 24 h

> "Splunk Cloud had a regional outage."

**Expected response:** § 6.2. Application stays healthy throughout
(Logback fails open). After the outage, recent events are gone from
HEC but local console logs remain in the Railway runtime — pull them
from the Railway "Logs" tab if forensics are needed before retention
expires.

---

## 9. Decision tree

```
Is the application responding to /api/actuator/health?
├── No → § 4 service redeploy.
│        Still down after redeploy? → § 5 full rebuild.
│
├── Yes, but data looks wrong / recent rows missing / corrupted
│   ├── Is the corruption < 24 h old?  → § 3.1 PITR.
│   └── Is it a migration?              → § 3.2 changelog repair.
│
├── Yes, but PHI columns return ciphertext / 500 → § 7 key recovery.
│
├── Yes, but Grafana / Splunk dashboards empty → § 6 observability.
│
└── Yes, healthy across the board → not a DR scenario.
                                   See grafana-observability.md.
```

---

## 10. Drill log template

Create `docs/observability/dr-drill-log.md` on the first drill run
and append per-drill rows:

```markdown
| Date       | Drill type   | Operators       | RTO actual | RPO actual | Notes |
|------------|--------------|-----------------|------------|------------|-------|
| 2026-XX-XX | § 3.1 PITR   | dev-ops on-call | 22 min     | 17 min     | OK    |
```

If RTO or RPO exceeded targets, file a follow-up issue and link it in
the **Notes** column.
