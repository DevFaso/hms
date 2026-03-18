-- =============================================================
-- V26: Seed ROLE_LAB_TECHNICIAN and ROLE_LAB_MANAGER
--
-- Lab technicians perform bench work (specimen receipt, result entry).
-- Lab managers oversee technicians and can release/verify results.
--
-- Idempotent: ON CONFLICT (code) DO NOTHING
-- =============================================================

INSERT INTO "security".roles (id, code, name, description, created_at, updated_at) VALUES
    (gen_random_uuid(), 'ROLE_LAB_TECHNICIAN', 'ROLE_LAB_TECHNICIAN',
     'Lab technician responsible for specimen collection, receipt, bench processing, and preliminary result entry',
     NOW(), NOW()),
    (gen_random_uuid(), 'ROLE_LAB_MANAGER', 'ROLE_LAB_MANAGER',
     'Lab manager overseeing technician workflows, result verification, quality control, and lab operations',
     NOW(), NOW())
ON CONFLICT (code) DO NOTHING;
