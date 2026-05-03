-- V82: Data-residency / region tagging on Organization (MVP-9 — gap #9
-- in docs/super-admin-gaps.md).
--
-- Adds the `region` column that records the regulatory jurisdiction
-- whose data-protection rules apply to the tenant's data. The deployment
-- can host tenants from multiple regions in one schema; this label tells
-- the platform which compliance posture to assume for each tenant.
--
-- Strictly additive — single nullable column with a default of 'BF'
-- (Burkina Faso, the platform's bootstrap focus country). Existing rows
-- get the default backfilled in-line so super admins can re-tag them
-- through the new region editor without a follow-up migration.
--
-- Rollback plan: the column has no FK and no CHECK; dropping it is safe
-- and the application falls back to NULL handling without code change
-- (mapper / DTO are nullable-tolerant).
--
-- The column is indexed because the Control Tower will filter the
-- organization grid by region; even with low cardinality the index pays
-- off because the filter is paired with the existing `active` /
-- `lifecycle_state` predicates that are already indexed.

ALTER TABLE hospital.organizations
    ADD COLUMN IF NOT EXISTS region VARCHAR(32) NOT NULL DEFAULT 'BF';

CREATE INDEX IF NOT EXISTS idx_organization_region
    ON hospital.organizations (region);
