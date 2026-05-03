-- V86: Per-region policy (MVP-c batch — MVP-9c in docs/super-admin-gaps.md).
--
-- Adds platform.region_policy keyed on the OrganizationRegion enum
-- code. Each row carries optional overrides:
--   * retention_days       — null falls back to the global policy
--   * default_export_format — null falls back to the global default
--                             ("STANDARD"); GDPR-tagged regions seed
--                             with "GDPR_PORTABILITY"
--   * target_deployment_url — when non-null, new tenants in this
--                             region are provisioned on the named
--                             deployment via TenantProvisioningClient
--
-- A row is seeded for every current OrganizationRegion code with NULL
-- overrides so the lookup is always present (avoids null-vs-missing
-- ambiguity in the resolver). The EU row is seeded with
-- 'GDPR_PORTABILITY' to capture the most common compliance case.
--
-- Strictly additive. Rollback drops the table — the resolver
-- tolerates a missing table by treating every region as "no override".

CREATE TABLE IF NOT EXISTS platform.region_policy (
    region                  VARCHAR(32) PRIMARY KEY,
    retention_days          INTEGER     NULL,
    default_export_format   VARCHAR(32) NULL,
    target_deployment_url   VARCHAR(255) NULL,
    updated_at              TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by              VARCHAR(255) NOT NULL DEFAULT 'system'
);

INSERT INTO platform.region_policy (region, retention_days, default_export_format, target_deployment_url, updated_by)
VALUES
    ('BF',     NULL, NULL,                NULL, 'system'),
    ('CI',     NULL, NULL,                NULL, 'system'),
    ('SN',     NULL, NULL,                NULL, 'system'),
    ('GA',     NULL, NULL,                NULL, 'system'),
    ('CM',     NULL, NULL,                NULL, 'system'),
    ('BJ',     NULL, NULL,                NULL, 'system'),
    ('TG',     NULL, NULL,                NULL, 'system'),
    ('ML',     NULL, NULL,                NULL, 'system'),
    ('NE',     NULL, NULL,                NULL, 'system'),
    ('ML_OAPI', NULL, NULL,               NULL, 'system'),
    ('EU',     NULL, 'GDPR_PORTABILITY',  NULL, 'system'),
    ('US',     NULL, NULL,                NULL, 'system'),
    ('OTHER',  NULL, NULL,                NULL, 'system')
ON CONFLICT (region) DO NOTHING;
