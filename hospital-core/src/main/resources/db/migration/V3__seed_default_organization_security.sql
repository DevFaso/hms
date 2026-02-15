-- =============================================================
-- V5: Seed DEFAULT_ORG organization and its security policies
-- Replaces Java-based OrganizationSecuritySeeder with a
-- Liquibase-managed migration for dev, uat, and prod.
--
-- Creates:
--   1. DEFAULT_ORG organization (HEALTHCARE_NETWORK)
--   2. 6 security policies (access control, data protection,
--      audit, password, session, role management)
--   3. 4 security rules (patient data access, admin endpoint,
--      audit operations, password min-length, session timeout)
--
-- Idempotent: uses DO $$ blocks with existence checks.
-- =============================================================

DO $$
DECLARE
    v_org_id UUID;
    v_policy_id UUID;
BEGIN
    -- =========================================================
    -- 1. Default Organization
    -- =========================================================
    SELECT id INTO v_org_id
    FROM hospital.organizations
    WHERE code = 'DEFAULT_ORG';

    IF v_org_id IS NULL THEN
        v_org_id := gen_random_uuid();
        INSERT INTO hospital.organizations (id, name, code, description, type, active, created_at, updated_at)
        VALUES (
            v_org_id,
            'Default Healthcare Organization',
            'DEFAULT_ORG',
            'Default organization for hospitals without specific organization assignment',
            'HEALTHCARE_NETWORK',
            TRUE,
            NOW(), NOW()
        );
    END IF;

    -- =========================================================
    -- 2a. Access Control Policy + rules
    -- =========================================================
    IF NOT EXISTS (
        SELECT 1 FROM security.organization_security_policies
        WHERE organization_id = v_org_id AND code = 'DEFAULT_ACCESS_CONTROL'
    ) THEN
        v_policy_id := gen_random_uuid();
        INSERT INTO security.organization_security_policies
            (id, name, code, description, policy_type, organization_id, priority, active, enforce_strict, created_at, updated_at)
        VALUES (
            v_policy_id,
            'Default Access Control Policy',
            'DEFAULT_ACCESS_CONTROL',
            'Default access control rules for healthcare organizations',
            'ACCESS_CONTROL',
            v_org_id, 100, TRUE, FALSE,
            NOW(), NOW()
        );

        -- Patient Data Access Rule
        INSERT INTO security.organization_security_rules
            (id, name, code, description, rule_type, rule_value, security_policy_id, priority, active, created_at, updated_at)
        VALUES (
            gen_random_uuid(),
            'Patient Data Access Rule',
            'PATIENT_DATA_ACCESS_RULE',
            'Controls access to patient data based on role',
            'ROLE_PERMISSION',
            'ROLE_DOCTOR:READ_WRITE,ROLE_NURSE:READ_WRITE,ROLE_RECEPTIONIST:READ,ROLE_LAB_SCIENTIST:READ',
            v_policy_id, 10, TRUE,
            NOW(), NOW()
        );

        -- Admin Endpoint Access Rule
        INSERT INTO security.organization_security_rules
            (id, name, code, description, rule_type, rule_value, security_policy_id, priority, active, created_at, updated_at)
        VALUES (
            gen_random_uuid(),
            'Admin Endpoint Access Rule',
            'ADMIN_ENDPOINT_ACCESS_RULE',
            'Controls access to administrative endpoints',
            'ENDPOINT_ACCESS',
            '/api/admin/**:ROLE_SUPER_ADMIN,ROLE_HOSPITAL_ADMIN',
            v_policy_id, 20, TRUE,
            NOW(), NOW()
        );
    END IF;

    -- =========================================================
    -- 2b. Data Protection Policy (no rules)
    -- =========================================================
    IF NOT EXISTS (
        SELECT 1 FROM security.organization_security_policies
        WHERE organization_id = v_org_id AND code = 'DEFAULT_DATA_PROTECTION'
    ) THEN
        INSERT INTO security.organization_security_policies
            (id, name, code, description, policy_type, organization_id, priority, active, enforce_strict, created_at, updated_at)
        VALUES (
            gen_random_uuid(),
            'Default Data Protection Policy',
            'DEFAULT_DATA_PROTECTION',
            'Default data protection and privacy rules',
            'DATA_PROTECTION',
            v_org_id, 90, TRUE, TRUE,
            NOW(), NOW()
        );
    END IF;

    -- =========================================================
    -- 2c. Audit Logging Policy + rule
    -- =========================================================
    IF NOT EXISTS (
        SELECT 1 FROM security.organization_security_policies
        WHERE organization_id = v_org_id AND code = 'DEFAULT_AUDIT_LOGGING'
    ) THEN
        v_policy_id := gen_random_uuid();
        INSERT INTO security.organization_security_policies
            (id, name, code, description, policy_type, organization_id, priority, active, enforce_strict, created_at, updated_at)
        VALUES (
            v_policy_id,
            'Default Audit Logging Policy',
            'DEFAULT_AUDIT_LOGGING',
            'Default audit logging requirements',
            'AUDIT_LOGGING',
            v_org_id, 80, TRUE, FALSE,
            NOW(), NOW()
        );

        INSERT INTO security.organization_security_rules
            (id, name, code, description, rule_type, rule_value, security_policy_id, priority, active, created_at, updated_at)
        VALUES (
            gen_random_uuid(),
            'Audit Sensitive Operations',
            'AUDIT_SENSITIVE_OPERATIONS_RULE',
            'Audit all sensitive medical operations',
            'AUDIT_REQUIREMENT',
            'PATIENT_CREATE,PATIENT_UPDATE,PRESCRIPTION_CREATE,LAB_RESULT_UPDATE',
            v_policy_id, 10, TRUE,
            NOW(), NOW()
        );
    END IF;

    -- =========================================================
    -- 2d. Password Policy + rule
    -- =========================================================
    IF NOT EXISTS (
        SELECT 1 FROM security.organization_security_policies
        WHERE organization_id = v_org_id AND code = 'DEFAULT_PASSWORD_POLICY'
    ) THEN
        v_policy_id := gen_random_uuid();
        INSERT INTO security.organization_security_policies
            (id, name, code, description, policy_type, organization_id, priority, active, enforce_strict, created_at, updated_at)
        VALUES (
            v_policy_id,
            'Default Password Policy',
            'DEFAULT_PASSWORD_POLICY',
            'Default password strength requirements',
            'PASSWORD_POLICY',
            v_org_id, 70, TRUE, TRUE,
            NOW(), NOW()
        );

        INSERT INTO security.organization_security_rules
            (id, name, code, description, rule_type, rule_value, security_policy_id, priority, active, created_at, updated_at)
        VALUES (
            gen_random_uuid(),
            'Password Minimum Length',
            'PASSWORD_MIN_LENGTH_RULE',
            'Minimum password length requirement',
            'PASSWORD_STRENGTH',
            '8',
            v_policy_id, 10, TRUE,
            NOW(), NOW()
        );
    END IF;

    -- =========================================================
    -- 2e. Session Management Policy + rule
    -- =========================================================
    IF NOT EXISTS (
        SELECT 1 FROM security.organization_security_policies
        WHERE organization_id = v_org_id AND code = 'DEFAULT_SESSION_MANAGEMENT'
    ) THEN
        v_policy_id := gen_random_uuid();
        INSERT INTO security.organization_security_policies
            (id, name, code, description, policy_type, organization_id, priority, active, enforce_strict, created_at, updated_at)
        VALUES (
            v_policy_id,
            'Default Session Management Policy',
            'DEFAULT_SESSION_MANAGEMENT',
            'Default session management and timeout rules',
            'SESSION_MANAGEMENT',
            v_org_id, 60, TRUE, FALSE,
            NOW(), NOW()
        );

        INSERT INTO security.organization_security_rules
            (id, name, code, description, rule_type, rule_value, security_policy_id, priority, active, created_at, updated_at)
        VALUES (
            gen_random_uuid(),
            'Session Timeout',
            'SESSION_TIMEOUT_RULE',
            'Session timeout in minutes',
            'SESSION_TIMEOUT',
            '480',
            v_policy_id, 10, TRUE,
            NOW(), NOW()
        );
    END IF;

    -- =========================================================
    -- 2f. Role Management Policy (no rules)
    -- =========================================================
    IF NOT EXISTS (
        SELECT 1 FROM security.organization_security_policies
        WHERE organization_id = v_org_id AND code = 'DEFAULT_ROLE_MANAGEMENT'
    ) THEN
        INSERT INTO security.organization_security_policies
            (id, name, code, description, policy_type, organization_id, priority, active, enforce_strict, created_at, updated_at)
        VALUES (
            gen_random_uuid(),
            'Default Role Management Policy',
            'DEFAULT_ROLE_MANAGEMENT',
            'Default role assignment and management rules',
            'ROLE_MANAGEMENT',
            v_org_id, 50, TRUE, FALSE,
            NOW(), NOW()
        );
    END IF;

END $$;
