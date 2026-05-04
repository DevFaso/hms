-- V85: jsonb feature_keys on subscription_plans (MVP-c batch — MVP-6c
-- in docs/super-admin-gaps.md).
--
-- Adds platform.subscription_plans.feature_keys_jsonb JSONB and
-- backfills from the existing comma-separated `feature_keys` TEXT
-- column. The legacy column is preserved for one release for rollback
-- safety; flagged for drop in a follow-up cycle.
--
-- The backfill produces a JSON array of strings (lowercased, trimmed,
-- empty entries filtered out) so downstream code can do
-- `feature_keys_jsonb @> '"foo"'::jsonb` containment checks at SQL
-- level when needed.
--
-- Strictly additive. Rollback drops the new column; entity reads fall
-- back to the TEXT column.

ALTER TABLE platform.subscription_plans
    ADD COLUMN IF NOT EXISTS feature_keys_jsonb JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Backfill: split the TEXT column on commas, trim + lower-case each
-- token, strip empties, jsonb_agg into an array. COALESCE to '[]'
-- for any plan whose featureKeys was NULL or all-empty.
UPDATE platform.subscription_plans
   SET feature_keys_jsonb = COALESCE(
        (SELECT jsonb_agg(LOWER(TRIM(token)))
           FROM regexp_split_to_table(feature_keys, ',') AS token
          WHERE TRIM(token) <> ''),
        '[]'::jsonb)
 WHERE feature_keys_jsonb = '[]'::jsonb
   AND feature_keys IS NOT NULL
   AND feature_keys <> '';

CREATE INDEX IF NOT EXISTS idx_subscription_plan_feature_keys_jsonb
    ON platform.subscription_plans USING GIN (feature_keys_jsonb);
