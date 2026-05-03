-- V81: Subscription plans + organization subscriptions (MVP-6 — gap #5
-- in docs/super-admin-gaps.md).
--
-- Establishes the schema super admins need to model paid tiers, seat
-- counts, and per-org assignments. The MVP ships read-only feature-key
-- listing on a plan; enforcement of plan-tier feature gating against
-- FeatureFlagOverride is deferred to MVP-6b once a paid tenant is real.
--
-- Strictly additive — no DROP, two new tables in the platform schema.
-- Both tables key off `id UUID` and copy the timestamp pattern used by
-- BaseEntity (created_at / updated_at NOT NULL).
--
-- Rollback plan: dropping both tables is safe; no FK references them
-- elsewhere, and the SubscriptionPlan / OrganizationSubscription beans
-- only load when the tables exist (Hibernate will surface a clear
-- "relation does not exist" if rollback skipped a step).

CREATE TABLE IF NOT EXISTS platform.subscription_plans (
    id                    UUID         PRIMARY KEY,
    name                  VARCHAR(120) NOT NULL,
    tier_code             VARCHAR(60)  NOT NULL UNIQUE,
    description           VARCHAR(1000),
    monthly_price_cents   BIGINT       NOT NULL DEFAULT 0,
    currency              VARCHAR(10)  NOT NULL DEFAULT 'USD',
    included_seats        INTEGER      NOT NULL DEFAULT 0,
    feature_keys          TEXT         NOT NULL DEFAULT '',  -- comma-separated; jsonb when MVP-6b lands
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_subscription_plans_active_tier
    ON platform.subscription_plans (active, tier_code);

CREATE TABLE IF NOT EXISTS platform.organization_subscriptions (
    id                UUID         PRIMARY KEY,
    organization_id   UUID         NOT NULL,
    plan_id           UUID         NOT NULL,
    seat_limit        INTEGER      NOT NULL DEFAULT 0,
    billing_period    VARCHAR(20)  NOT NULL DEFAULT 'MONTHLY',
    status            VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    started_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ends_at           TIMESTAMP    NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_orgsub_organization
        FOREIGN KEY (organization_id) REFERENCES hospital.organizations(id),
    CONSTRAINT fk_orgsub_plan
        FOREIGN KEY (plan_id) REFERENCES platform.subscription_plans(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_orgsub_active_per_org
    ON platform.organization_subscriptions (organization_id)
 WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_orgsub_plan
    ON platform.organization_subscriptions (plan_id);
