-- Migration V5: add one-time temp_plain_password column to user_role_hospital_assignment.
-- The column stores the auto-generated plaintext credential only until the user verifies
-- their assignment code.  It is cleared (set to NULL) immediately after verification so
-- no long-term plaintext password is retained in the database.
ALTER TABLE security.user_role_hospital_assignment
    ADD COLUMN IF NOT EXISTS temp_plain_password VARCHAR(255);
