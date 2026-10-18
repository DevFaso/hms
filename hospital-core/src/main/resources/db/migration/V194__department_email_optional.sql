-- V194: a department's e-mail is optional (QA D93).
--
-- The portal form treats it as optional, and the request DTO only validates
-- its format, but hospital.departments.email was NOT NULL, so creating a
-- department without one failed with an untranslated 400. Dropping the
-- constraint matches the entity (@Column(nullable = true)).
-- Idempotent: DROP NOT NULL on a nullable column is a no-op.

ALTER TABLE hospital.departments ALTER COLUMN email DROP NOT NULL;
