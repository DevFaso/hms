-- =============================================================
-- V4: Seed reference roles into security.roles
-- Replaces Java-based RoleSeeder with a Liquibase-managed
-- migration so role data exists via the database migration
-- pipeline in dev, uat, and prod environments.
--
-- Idempotent: ON CONFLICT (code) DO NOTHING
-- =============================================================

INSERT INTO "security".roles (id, code, name, description, created_at, updated_at) VALUES
    (gen_random_uuid(), 'ROLE_SUPER_ADMIN',        'ROLE_SUPER_ADMIN',        'System-wide super administrator with full access to all organizations, hospitals, and system settings',             NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_HOSPITAL_ADMIN',     'ROLE_HOSPITAL_ADMIN',     'Hospital administrator responsible for managing staff, departments, billing, and hospital-level configuration',      NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_DOCTOR',             'ROLE_DOCTOR',             'Licensed physician authorized to diagnose, prescribe, order tests, and manage patient treatment plans',              NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_NURSE',              'ROLE_NURSE',              'Registered nurse providing patient care, administering medications, and documenting vital signs',                    NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_PHYSICIAN',          'ROLE_PHYSICIAN',          'General physician with clinical privileges for patient consultation and treatment',                                  NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_MIDWIFE',            'ROLE_MIDWIFE',            'Certified midwife specializing in maternal care, labor support, and newborn assessment',                              NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_ANESTHESIOLOGIST',   'ROLE_ANESTHESIOLOGIST',   'Anesthesia specialist managing pre-operative assessment, sedation, and pain management',                              NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_LAB_SCIENTIST',      'ROLE_LAB_SCIENTIST',      'Laboratory scientist responsible for processing specimens, running tests, and publishing results',                   NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_PHARMACIST',         'ROLE_PHARMACIST',         'Licensed pharmacist handling medication dispensing, drug interaction checks, and inventory management',               NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_RECEPTIONIST',       'ROLE_RECEPTIONIST',       'Front-desk receptionist managing patient check-in, appointments, and visitor coordination',                           NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_ADMIN',              'ROLE_ADMIN',              'General administrative user with elevated operational privileges',                                                    NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_USER',               'ROLE_USER',               'Standard system user with basic access permissions',                                                                  NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_MODERATOR',          'ROLE_MODERATOR',          'Content and activity moderator with oversight and review capabilities',                                                NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_PATIENT',            'ROLE_PATIENT',            'Registered patient with self-service access to medical records, appointments, and prescriptions',                     NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_ACCOUNTANT',         'ROLE_ACCOUNTANT',         'Financial accountant managing ledger entries, expense tracking, and financial reconciliation',                         NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_TECHNICIAN',         'ROLE_TECHNICIAN',         'Medical or IT technician responsible for equipment maintenance and technical support',                                NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_RADIOLOGIST',        'ROLE_RADIOLOGIST',        'Imaging specialist interpreting X-rays, CT scans, MRIs, and generating radiology reports',                            NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_SURGEON',            'ROLE_SURGEON',            'Surgical specialist performing operations, managing surgical plans, and post-operative care',                         NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_BILLING_SPECIALIST', 'ROLE_BILLING_SPECIALIST', 'Billing specialist handling invoicing, insurance claims, payment processing, and financial reports',                  NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_PHYSIOTHERAPIST',    'ROLE_PHYSIOTHERAPIST',    'Physical therapist designing rehabilitation programs, therapy sessions, and mobility assessments',                    NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_CLEANER',            'ROLE_CLEANER',            'Facility maintenance staff responsible for sanitation and cleanliness of hospital premises',                           NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_SECURITY',           'ROLE_SECURITY',           'Security personnel managing access control, surveillance, and facility safety protocols',                              NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_SUPPORT',            'ROLE_SUPPORT',            'Technical or customer support staff assisting users with system issues and inquiries',                                 NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_MANAGER',            'ROLE_MANAGER',            'Department or operational manager overseeing staff coordination and resource planning',                                NOW(), NOW())
ON CONFLICT (code) DO NOTHING;
