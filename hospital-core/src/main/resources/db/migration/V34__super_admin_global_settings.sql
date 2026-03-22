-- ============================================================
-- V34: Super Admin MVP2 — Global Settings table
-- ============================================================

CREATE TABLE IF NOT EXISTS platform.platform_global_settings (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    setting_key      VARCHAR(120) NOT NULL,
    setting_value    VARCHAR(2000),
    category         VARCHAR(60),
    description      VARCHAR(255),
    updated_by       VARCHAR(120),
    created_at       TIMESTAMP    DEFAULT now(),
    updated_at       TIMESTAMP    DEFAULT now(),
    CONSTRAINT uq_global_setting_key UNIQUE (setting_key)
);

CREATE INDEX IF NOT EXISTS idx_global_settings_category
    ON platform.platform_global_settings(category);
