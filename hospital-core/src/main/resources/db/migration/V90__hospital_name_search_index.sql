-- V90: B-tree functional index on LOWER(hospital.hospitals.name) to
-- accelerate the super-admin hospital-scope typeahead introduced in
-- docs/super-admin-cross-tenant-design.md.
--
-- The repository query (HospitalRepository.searchHospitals) does
--   LOWER(name) LIKE LOWER('%q%')
-- with a server-side LIMIT 20 for the typeahead UX. With 10k+ tenants
-- a sequential scan on every keystroke is unacceptable; this index
-- gives B-tree-friendly support for prefix matches (LIKE 'memo%') and
-- bounds the cost of substring matches enough that LIMIT 20 cuts off
-- after a small number of matched rows.
--
-- Strictly additive: CREATE INDEX IF NOT EXISTS, no schema change.
-- Rollback (manual, not Flyway): DROP INDEX IF EXISTS hospital.idx_hospitals_lower_name;

CREATE INDEX IF NOT EXISTS idx_hospitals_lower_name
    ON hospital.hospitals (LOWER(name));
