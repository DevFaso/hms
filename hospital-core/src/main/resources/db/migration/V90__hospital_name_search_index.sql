-- V90: B-tree functional index on LOWER(hospital.hospitals.name) to
-- accelerate the super-admin hospital-scope typeahead introduced in
-- docs/super-admin-cross-tenant-design.md.
--
-- The repository query (HospitalRepository.searchHospitals) now does
--   LOWER(name) LIKE LOWER('memo%')   -- prefix match (F2 follow-up)
-- with a server-side LIMIT 20 for the typeahead UX. With 10k+ tenants
-- a sequential scan on every keystroke is unacceptable; this functional
-- B-tree index makes the prefix match a true index-range scan
-- (anchored prefix is B-tree-optimal for LIKE).
--
-- History: the original repo query used substring (LIKE '%memo%') and
-- this index was decorative — Postgres would fall back to a seq scan.
-- Design call #3 in docs/super-admin-cross-tenant-design.md called for
-- prefix match; F2 (2026-05-05) flipped the query so the index is now
-- load-bearing.
--
-- Strictly additive: CREATE INDEX IF NOT EXISTS, no schema change.
-- Rollback (manual, not Flyway): DROP INDEX IF EXISTS hospital.idx_hospitals_lower_name;

CREATE INDEX IF NOT EXISTS idx_hospitals_lower_name
    ON hospital.hospitals (LOWER(name));
