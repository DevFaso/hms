-- V91: B-tree indexes on (created_at DESC) for the seven clinical parent
-- tables that the super-admin cross-tenant "recent activity" + list pages
-- query in created-time order.
--
-- Why now: closes F4 from docs/super-admin-cross-tenant-design.md. The
-- design's component inventory called for these indexes; they were
-- explicitly deferred when the cross-tenant slice landed because the
-- existing offset pagination at current scale is acceptable and
-- Postgres can use the default B-tree (ASC) for ORDER BY ... DESC at
-- low row counts. With these dedicated DESC indexes:
--
--   * cursor / keyset pagination (the design call #2 follow-up) becomes
--     a 1-line repo change instead of a "first add the index" round trip;
--   * the cross-tenant `recent-*` endpoints from
--     SuperAdminDashboardController (which all sort by created_at DESC
--     as a tiebreaker, with each entity's clinical-time field as the
--     primary sort) get a covering index on the secondary sort key.
--
-- Tables and schemas (verified against @Table annotations on the JPA
-- entities — see model/Consultation.java, Encounter.java,
-- treatment/TreatmentPlan.java, Prescription.java, Admission.java,
-- GeneralReferral.java, LabOrder.java):
--
--   clinical.consultations       (Consultation extends BaseEntity)
--   clinical.encounters          (Encounter   extends BaseEntity)
--   clinical.prescriptions       (Prescription extends BaseEntity)
--   clinical.treatment_plans     (TreatmentPlan extends BaseEntity)
--   lab.lab_orders               (LabOrder    extends BaseEntity)
--   admissions                   (Admission @CreationTimestamp, public schema)
--   general_referrals            (GeneralReferral @CreationTimestamp, public schema)
--
-- All seven have a `created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL`
-- column (verified against V1__Initial_Schema.sql). BaseEntity's
-- camelCase `createdAt` field is mapped to the snake_case `created_at`
-- column by Spring Boot's default SpringPhysicalNamingStrategy, which
-- this migration depends on.
--
-- Strictly additive: CREATE INDEX IF NOT EXISTS, no schema change.
-- Rollback (manual, not Flyway):
--   DROP INDEX IF EXISTS clinical.idx_consultations_created_at_desc;
--   DROP INDEX IF EXISTS clinical.idx_encounters_created_at_desc;
--   DROP INDEX IF EXISTS clinical.idx_prescriptions_created_at_desc;
--   DROP INDEX IF EXISTS clinical.idx_treatment_plans_created_at_desc;
--   DROP INDEX IF EXISTS lab.idx_lab_orders_created_at_desc;
--   DROP INDEX IF EXISTS public.idx_admissions_created_at_desc;
--   DROP INDEX IF EXISTS public.idx_general_referrals_created_at_desc;

CREATE INDEX IF NOT EXISTS idx_consultations_created_at_desc
    ON clinical.consultations (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_encounters_created_at_desc
    ON clinical.encounters (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_prescriptions_created_at_desc
    ON clinical.prescriptions (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_treatment_plans_created_at_desc
    ON clinical.treatment_plans (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_lab_orders_created_at_desc
    ON lab.lab_orders (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_admissions_created_at_desc
    ON admissions (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_general_referrals_created_at_desc
    ON general_referrals (created_at DESC);
