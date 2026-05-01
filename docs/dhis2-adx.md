# DHIS2 ADX export (P1 #11)

HMS can push aggregate clinical metrics — v0 scope = **immunization
counts** — to a DHIS2 instance using the IHE / HL7 ADX 1.0 XML payload
posted at `/api/dataValueSets`. Per-hospital configuration; no global
mapping.

## v0 scope

- Domain: immunizations only (CVX-coded counts from
  `clinical.patient_immunization` where `status='COMPLETED'` and
  `active=true`).
- Trigger: manual via `/admin/integrations/dhis2`. The cron scheduler
  (`DhisAdxScheduler`) is **off by default**. Set
  `dhis2.export.scheduler.enabled=true` and a cron expression to
  enable the monthly auto-push.
- Authoritative wire format: lower-case DHIS2 period tokens (e.g.
  `202604` for monthly, `2026W17` for weekly, `2026` for yearly).

## Data model

| Table | Purpose |
|---|---|
| `integration.dhis2_facility_config` | Per-hospital DHIS2 endpoint + auth pointer |
| `integration.dhis2_dataelement_mapping` | HMS code (CVX/LOINC/...) → DHIS2 dataElement UID |
| `integration.dhis2_export_run` | One row per triggered export |
| `integration.dhis2_export_outbox` | One row per data value sent (idempotency by UNIQUE(run, period, orgUnit, dataElement, COC)) |
| `hospital.hospitals.dhis2_org_unit_uid` | DHIS2 org-unit UID bound to the HMS facility |

## Auth secrets — environment variables only

The DHIS2 password / personal access token is **never persisted**.
`integration.dhis2_facility_config.auth_secret_env_var` stores the
*name* of the environment variable, and `DhisHttpClient` resolves it at
call time via `System.getenv(...)`.

Convention for env-var names:

```
DHIS2_FACILITY_<HOSPITAL_CODE>_TOKEN     (PAT mode)
DHIS2_FACILITY_<HOSPITAL_CODE>_BASIC     (BASIC mode, value = user:pass)
```

CHECK constraint on the column rejects anything that isn't
`^[A-Z][A-Z0-9_]*$` so a misconfigured operator can't paste a raw
secret into the database.

## Railway operator runbook

1. Configure the DHIS2 endpoint in the admin UI:
   `https://hms.<env>.bitnesttechs.com/admin/integrations/dhis2` →
   **Facility config** tab. Fill `baseUrl`, `authMode`, `authSecretEnvVar`,
   `defaultPeriodType`, `defaultDatasetUid`. Save.
2. On Railway, set the env var named in `authSecretEnvVar` on
   `hms-backend-<env>`. Trigger a redeploy so the secret is in the
   process environment.
3. Bind the hospital to its DHIS2 organisation-unit UID:
   `hospital.hospitals.dhis2_org_unit_uid` (set via the existing hospital
   admin UI; the column is nullable for non-exporting facilities).
4. Author dataElement mappings on the **Mappings** tab. Repeat per
   dataset.
5. Verify with a manual trigger (**Exports** tab → Trigger). The status
   pill shows `SUCCESS`, `PARTIAL`, or `FAILED`. Failures land in the
   row's `errorMessage` and in the per-value `dhis2_export_outbox.last_error`.
6. (Optional) flip the scheduler on:
   ```
   dhis2.export.scheduler.enabled=true
   dhis2.export.scheduler.cron=0 0 2 1 * *   # 02:00 UTC, day 1 of each month
   dhis2.export.scheduler.zone=UTC
   ```

## Privacy / PHI contract

ADX export is **aggregate-only**. Hard rules:

- `AggregatedDataValue` carries exactly four fields: `orgUnitUid`,
  `dataElementUid`, `categoryOptionComboUid`, `value`. No patient UUID,
  no MRN, no name.
- A regression test (`DhisAdxXmlWriterTest#noPatientIdentifierLeaksThroughXmlWriter`)
  asserts the rendered XML contains no UUID-shaped substring.
- Sensitive `DataDomain`s (mental health, HIV status, substance use,
  genetics) are out of scope for v0. Re-identification risk in tiny
  facilities means k-anonymity thresholds need to be designed before
  any such bucket can be added.

## Idempotency

Every outbox row is keyed on
`(run_id, period_iso, org_unit_uid, dataelement_uid, COALESCE(category_option_combo_uid,'__DEFAULT_COC__'))`
via the `uq_dhis2_outbox_value` UNIQUE INDEX (V68). The COALESCE is
required because Postgres treats NULLs as distinct in plain UNIQUE
constraints, which would otherwise let two outbox rows for the same
"default-COC" data value coexist inside one run.
A retry for the same period+orgUnit+dataElement+COC will fail this
UNIQUE INDEX inside the same run, so replays are safe at the SQL
boundary. DHIS2 itself accepts repeated POSTs — the import summary
will show high `imported` counts on first call and high `ignored`
counts on subsequent calls (the orchestrator persists this on
`dhis2_export_run` as `PARTIAL`).

## Observability

- `dhis2_export_run.http_status` and `error_message` capture the last
  outcome.
- `dhis2_export_run.skipped_count` reflects HMS concept codes that
  had no mapping in the dataset; surface this in operator dashboards
  to drive mapping-table maintenance.
- All push attempts log at INFO with the hospital id, period, and run
  id; per-attempt outcomes log at INFO; non-retryable rejections log
  at WARN.

## Mapping seed example (Burkina Faso EPI)

```sql
INSERT INTO integration.dhis2_dataelement_mapping
  (id, hospital_id, hms_concept_system, hms_concept_code,
   dhis2_dataelement_uid, period_type, dataset_uid, is_active,
   created_at, updated_at)
VALUES
  (gen_random_uuid(), :hid, 'http://hl7.org/fhir/sid/cvx', '49',
   'BCG00000001', 'MONTHLY', 'EPI00DSDEF1', TRUE, NOW(), NOW()),
  (gen_random_uuid(), :hid, 'http://hl7.org/fhir/sid/cvx', '03',
   'OPV00000001', 'MONTHLY', 'EPI00DSDEF1', TRUE, NOW(), NOW());
```

(Use real DHIS2 UIDs from the destination instance — these are
illustrative.)
