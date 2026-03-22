-- ============================================================
-- V35: Super Admin MVP3 — System Alerts table
-- ============================================================

CREATE TABLE IF NOT EXISTS platform.platform_system_alerts (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_type       VARCHAR(60)  NOT NULL,
    severity         VARCHAR(30)  NOT NULL,
    title            VARCHAR(500) NOT NULL,
    description      VARCHAR(2000),
    source           VARCHAR(120),
    acknowledged     BOOLEAN      DEFAULT false,
    acknowledged_by  VARCHAR(120),
    acknowledged_at  TIMESTAMP,
    resolved         BOOLEAN      DEFAULT false,
    resolved_at      TIMESTAMP,
    created_at       TIMESTAMP    DEFAULT now(),
    updated_at       TIMESTAMP    DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_system_alerts_severity
    ON platform.platform_system_alerts(severity);

CREATE INDEX IF NOT EXISTS idx_system_alerts_acknowledged
    ON platform.platform_system_alerts(acknowledged);

CREATE INDEX IF NOT EXISTS idx_system_alerts_created_at
    ON platform.platform_system_alerts(created_at DESC);
