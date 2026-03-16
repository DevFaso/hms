-- ============================================================
-- V22: MVP 14 — Proxy/Family Access + Notification Preferences
-- ============================================================

-- 1. Patient Proxy table (clinical schema)
CREATE TABLE IF NOT EXISTS clinical.patient_proxies (
    id              UUID PRIMARY KEY,
    grantor_patient_id UUID NOT NULL
        REFERENCES clinical.patients(id),
    proxy_user_id   UUID NOT NULL
        REFERENCES security.users(id),
    relationship    VARCHAR(30)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    permissions     VARCHAR(500) NOT NULL DEFAULT 'ALL',
    expires_at      TIMESTAMP,
    revoked_at      TIMESTAMP,
    notes           VARCHAR(500),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_proxy_grantor ON clinical.patient_proxies(grantor_patient_id);
CREATE INDEX IF NOT EXISTS idx_proxy_grantee ON clinical.patient_proxies(proxy_user_id);
CREATE INDEX IF NOT EXISTS idx_proxy_status  ON clinical.patient_proxies(status);

-- 2. Notification type column on existing notifications table
ALTER TABLE security.notifications
    ADD COLUMN IF NOT EXISTS type VARCHAR(40);

-- 3. Notification Preferences table (security schema)
CREATE TABLE IF NOT EXISTS security.notification_preferences (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL
        REFERENCES security.users(id),
    notification_type VARCHAR(30) NOT NULL,
    channel           VARCHAR(20) NOT NULL,
    enabled           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_notif_pref_user_type_channel
        UNIQUE (user_id, notification_type, channel)
);

CREATE INDEX IF NOT EXISTS idx_notif_pref_user ON security.notification_preferences(user_id);
