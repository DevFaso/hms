-- V249 (round 18, front desk and identity).
--
-- QA D1061: a child or another dependant is registered under the guardian's
-- phone number, as real registration desks do. users.phone_number was
-- NOT NULL and UNIQUE, so the child's account had to take the number too,
-- found it already held by the parent, and the registration answered 409
-- "already registered" (the desk invented a number to get past it).
--
-- A patient account whose phone belongs to ANOTHER person (another name or
-- date of birth) is now created with no login phone; the number stays the
-- contact on the child's patient record (clinical.patients.phone_number_primary,
-- which has no unique constraint). The column keeps its UNIQUE constraint:
-- PostgreSQL ignores NULLs in a unique index, so any number of dependants may
-- have none, and one number still names at most one login.
--
-- Schema only, no row read or written, no PHI. Plain statement, no DO block.
-- Idempotent: dropping a NOT NULL that is already dropped is a no-op.

ALTER TABLE security.users ALTER COLUMN phone_number DROP NOT NULL;
