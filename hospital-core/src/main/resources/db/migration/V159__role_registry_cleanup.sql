-- V159: E9 #68 — the role registry catches up with the guards.
--
-- ROLE_STAFF. Eight @PreAuthorize lists admit it, SecurityConfig's staff
-- scheduling and lab-order matchers name it, RoleExpansion hands it to every
-- SUPER_ADMIN, DashboardConfigService has a layout for it and the portal
-- references it sixteen times — but only the dev-profile RoleSeeder (local,
-- local-h2) ever created the row. On every environment that runs migrations
-- the role could not be assigned. Idempotent on both unique keys (code, name).
INSERT INTO "security".roles (id, code, name, description, created_at, updated_at) VALUES
    (gen_random_uuid(), 'ROLE_STAFF', 'ROLE_STAFF', 'General hospital support staff with schedule and appointment visibility', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- Seven roles V2 seeded that no guard, matcher or portal gate has ever
-- admitted: a user holding one can log in and reach nothing. Retired where
-- nobody holds them; a row still referenced by security.user_roles or
-- security.user_role_hospital_assignment is left in place (RoleRegistryTest
-- keeps the guards honest either way). role_permissions rows go first.
DELETE FROM "security".role_permissions rp
 USING "security".roles r
 WHERE rp.role_id = r.id
   AND r.code IN ('ROLE_USER', 'ROLE_MODERATOR', 'ROLE_TECHNICIAN', 'ROLE_CLEANER',
                  'ROLE_SECURITY', 'ROLE_SUPPORT', 'ROLE_MANAGER')
   AND NOT EXISTS (SELECT 1 FROM "security".user_roles ur WHERE ur.role_id = r.id)
   AND NOT EXISTS (SELECT 1 FROM "security".user_role_hospital_assignment a WHERE a.role_id = r.id);

DELETE FROM "security".roles r
 WHERE r.code IN ('ROLE_USER', 'ROLE_MODERATOR', 'ROLE_TECHNICIAN', 'ROLE_CLEANER',
                  'ROLE_SECURITY', 'ROLE_SUPPORT', 'ROLE_MANAGER')
   AND NOT EXISTS (SELECT 1 FROM "security".user_roles ur WHERE ur.role_id = r.id)
   AND NOT EXISTS (SELECT 1 FROM "security".user_role_hospital_assignment a WHERE a.role_id = r.id);
