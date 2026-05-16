---
name: liquibase-migration
description: Use whenever you add a new SQL file under hospital-core/src/main/resources/db/migration/. This skill captures the MANDATORY changelog.xml registration step (which has been silently broken twice already and caught by Copilot review both times).
---

# Liquibase migration registration

HMS uses Liquibase with an **XML master changelog**, not Flyway-style
directory auto-discovery. A new `V##__name.sql` file does NOTHING until
it is registered in `changelog.xml`.

## The two-file rule

For every new migration you must touch **two files**:

1. The SQL file: `hospital-core/src/main/resources/db/migration/V##__<descriptive_name>.sql`
2. A `<changeSet>` entry at the end of:
   `hospital-core/src/main/resources/db/migration/changelog.xml`

Skipping step 2 is the single most common Liquibase mistake on this
codebase — it slipped through review on the V99 (ADT) and V100 (LOINC
binding) PRs and was caught by Copilot. If the new column is referenced
by a JPA entity in the same PR, the app **fails to start** with
"column does not exist" — that's the test gate that ultimately catches
it, but only if you actually run the integration tests.

## The changeSet template

```xml
<!-- =================================================================
     V## — <one-line headline>.
     (roadmap row N, vX.Y / Lane / "Item").
     <3-5 lines explaining what columns/indexes/constraints land and
     why; reference any IF NOT EXISTS guards; reserve number gaps if
     parallel feature branches are using adjacent slots.>
     ================================================================= -->
<changeSet id="V##-<kebab-case-slug>" author="hms-team" runOnChange="false">
    <sqlFile path="V##__<descriptive_name>.sql"
             relativeToChangelogFile="true"
             splitStatements="true"
             stripComments="true"/>
</changeSet>
```

Always:

- `runOnChange="false"` — migrations are immutable once shipped.
- `relativeToChangelogFile="true"` — keeps the path local to
  `db/migration/`.
- `splitStatements="true"` — Liquibase splits on `;`. If your file has
  PL/pgSQL DO blocks, see the V93 / V77 pattern (DO `$$ ... $$;` works
  inside `splitStatements="true"` because the body isn't split).

## SQL file conventions

The file header should mirror the changeSet comment, expanded:

- Roadmap row + lane + item it satisfies
- What it adds (columns / indexes / constraints)
- Why (deployment context, threat model, prior incident)
- Idempotency: every statement should be **strictly additive** and
  guarded with `ADD COLUMN IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`,
  or the DO `$$ + pg_constraint` pattern for CHECK constraints.
- Rollback: explicit note on whether automated rollback is declared
  (typically not for forward-only schema changes).

See V98 / V99 / V100 for current-style headers.

## Numbering

- The next-free slot is whatever is highest on `develop` plus one.
- **Parallel feature branches** can produce gaps (V97 was reserved for
  schema-per-tenant while V98 shipped on the ORU branch). When you see
  a skipped number, do NOT renumber — leave the gap and add a comment
  in your changeSet noting which branch took the prior slot.
- Once a migration is merged to `develop`, **never renumber it**
  (Liquibase tracks applied changesets by id; renumbering = re-run on
  every prod that already applied it).

## Schema location

PostgreSQL schemas in use:

- `public` — default; `admissions`, `appointments`, etc.
- `clinical` — `encounters`, `prescriptions`, `lab_*`, `patient_problems`, `medication_*`, `discharge_summaries`
- `lab` — `lab_orders`, `lab_results`, `lab_specimens`
- `audit` — `audit_event_log`
- `security` — `users`, `user_role_hospital_assignment`, `roles`
- `empi` — `empi_master_identities`, `empi_identity_aliases`, `empi_merge_events`
- `billing` — invoices, payments
- `tenant` — multi-tenancy bookkeeping

Always qualify the table with its schema in DDL
(`ALTER TABLE clinical.patient_problems ADD COLUMN ...`).

## Partial unique indexes (HL7 idempotency pattern)

Composite partial unique indexes are the idiomatic dedup mechanism on
HL7 ingest paths:

```sql
CREATE UNIQUE INDEX IF NOT EXISTS uk_<table>_<purpose>
    ON <schema>.<table> (<col1>, <col2>, <col3>)
 WHERE <col_that_means_optional_dedup> IS NOT NULL;
```

The partial `WHERE` clause excludes legacy/manually-created rows whose
dedup columns are NULL — they stay outside the constraint. See
`uk_lab_result_source_message` (V98), `uk_admission_external_visit` /
`uk_encounter_external_visit` (V99).

**Exclude whitespace-only values, not just empty strings.** A
predicate like `WHERE mrn <> ''` still includes `'   '` (three
spaces). Use `WHERE btrim(mrn) <> ''` so the index truly excludes
"blank" values and legacy whitespace-only rows don't trip uniqueness
collisions during migration. V101 shipped with `mrn <> ''` and was
Copilot-flagged (PR #343); the corrective form is:

```sql
WHERE is_active = true
  AND mrn IS NOT NULL
  AND btrim(mrn) <> '';
```

The same shape applies to any other text column where "blank" means
"not assigned yet" rather than "intentionally empty".

## Migration validation gates

Before opening a PR with a new migration:

1. Run `./gradlew :hospital-core:test` — full suite. Integration tests
   start the app + apply migrations against H2; missing changelog
   registration fails here loudly.
2. Verify `git diff origin/develop -- '*db/migration/*'` shows BOTH
   the SQL file AND the changelog.xml entry.
3. Verify the JPA entity columns referenced are present in the SQL.

## Reference files

- `hospital-core/src/main/resources/db/migration/changelog.xml` — the master
- `hospital-core/src/main/resources/db/migration/V98__lab_result_source_message_control_id.sql` — clean reference
- `hospital-core/src/main/resources/db/migration/V99__adt_external_visit_reconciliation.sql`
- `hospital-core/src/main/resources/db/migration/V100__patient_problem_loinc_binding.sql`
- `hospital-core/src/main/resources/db/migration/V101__fhir_write_patient_idempotency.sql` — partial unique idx for FHIR conditional-create (row 20)
- `hospital-core/src/main/resources/db/migration/V93__cds_rxnorm_bindings.sql` — DO-block pattern for CHECK constraint
