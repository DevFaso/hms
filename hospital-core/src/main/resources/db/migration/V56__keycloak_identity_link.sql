-- V56: Link HMS users to Keycloak identities
--
-- keycloak_subject  : the Keycloak JWT 'sub' claim (KC internal UUID).
--                     Null for legacy/internal-auth users until they first OIDC-login.
-- keycloak_realm    : the KC realm name (e.g. 'hms', 'hms-dev'). Future-proofing
--                     for multi-realm setups.
-- auth_source       : 'internal' (legacy password) | 'keycloak' | 'saml'.
--                     Drives which auth path the backend trusts.
-- last_oidc_login_at: updated by /auth/session/bootstrap on each OIDC login.

ALTER TABLE security.users
    ADD COLUMN IF NOT EXISTS keycloak_subject  VARCHAR(128)  NULL,
    ADD COLUMN IF NOT EXISTS keycloak_realm    VARCHAR(100)  NULL,
    ADD COLUMN IF NOT EXISTS auth_source       VARCHAR(32)   NOT NULL DEFAULT 'internal',
    ADD COLUMN IF NOT EXISTS last_oidc_login_at TIMESTAMP WITH TIME ZONE NULL;

-- Partial unique index: enforces one DB user per KC subject, but only once linked
-- (NULL subjects are excluded so unlinked legacy users don't conflict).
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_keycloak_subject
    ON security.users (keycloak_subject)
    WHERE keycloak_subject IS NOT NULL;

-- Non-unique covering index for the bootstrap lookup path:
-- SELECT * FROM security.users WHERE keycloak_subject = $1
-- (index already covers the query; kept explicit for query-plan clarity)
CREATE INDEX IF NOT EXISTS idx_users_auth_source
    ON security.users (auth_source);
