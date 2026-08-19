-- V83: Per-tenant feature-flag overrides (MVP-7b — gap #7 follow-up
-- in docs/super-admin-gaps.md).
--
-- Adds an optional `organization_id` column to
-- platform.platform_feature_flag_overrides so a single flag key can
-- carry both a global override (organization_id NULL) and any number
-- of per-tenant overrides (one per organization). The application
-- layer resolves the effective value per-request as
-- (default → global override → tenant override) so a tenant override
-- wins for that tenant only.
--
-- Strictly additive — column is nullable, no existing row touched.
-- The pre-existing uq_feature_flag_override_key UNIQUE constraint
-- (single-column, on flag_key alone) is dropped and replaced with a
-- composite that allows the same flag_key to coexist across
-- organizations + the global row. Postgres treats two NULL values
-- in a uniqueness comparison as distinct, which is exactly what we
-- want here (one global per key, plus one per non-null org).
--
-- Rollback: drop the column + composite constraint; re-add the
-- original single-column UNIQUE on flag_key. The application's
-- per-tenant resolver tolerates the column being absent (treats
-- every row as global) so partial rollback is safe at runtime.

ALTER TABLE platform.platform_feature_flag_overrides
    ADD COLUMN IF NOT EXISTS organization_id UUID NULL;

ALTER TABLE platform.platform_feature_flag_overrides
    DROP CONSTRAINT IF EXISTS uq_feature_flag_override_key;

ALTER TABLE platform.platform_feature_flag_overrides
    ADD CONSTRAINT uq_feature_flag_override_key_per_org
    UNIQUE (flag_key, organization_id);

CREATE INDEX IF NOT EXISTS idx_feature_flag_override_org
    ON platform.platform_feature_flag_overrides (organization_id);
