---
description: "Generate a safe, additive Flyway database migration for the HMS project. Use for: database migration, schema change, add column, add table, alter table, new index, DB change."
name: "HMS Migration"
argument-hint: "Describe the schema change (e.g. 'Add allergy_type and severity columns to patient_allergies table')"
agent: "agent"
tools: [read, search, edit]
---
Generate a safe Flyway database migration for the Hospital Management System.

**Schema Change:** $input

## Rules

- **Additive only** — no `DROP TABLE`, `DROP COLUMN`, or `TRUNCATE` without explicit approval.
- **Naming convention** — file must follow: `V{next_version}__{snake_case_description}.sql`
  - Check `hospital-core/src/main/resources/db/migration/` for the current highest version number.
- **Idempotent where possible** — use `IF NOT EXISTS` for `CREATE TABLE` / `ADD COLUMN`.
- **No data loss** — new `NOT NULL` columns must have a `DEFAULT` or be populated in the migration.
- **Include a rollback comment** — document how to revert at the top of the file.

## Steps

1. List existing migration files to find the next version number.
2. Identify the exact SQL needed for the change.
3. Create the migration file at `hospital-core/src/main/resources/db/migration/`.
4. Update the corresponding JPA `@Entity` class to match the new schema.
5. If new columns are added, update:
   - The mapper `toDto()` / `toEntity()` methods
   - The relevant DTOs
   - Frontend TypeScript interfaces

## Migration File Template

```sql
-- Rollback: <how to undo this migration>
-- Description: <what this migration does>

<SQL statements here, one per line, each terminated with ;>
```

## High-Risk Flags

If this migration involves any of the following, flag it explicitly for peer review before proceeding:
- Patient PHI fields
- Auth / user / role tables
- Billing / payment tables
- Deleting or renaming existing columns
- Large table alterations (check row count implications)
