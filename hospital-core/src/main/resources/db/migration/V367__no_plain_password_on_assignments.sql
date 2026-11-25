-- V367: the legacy security.user_role_hospital_assignment.temp_plain_password column stays empty, whatever
-- writes to it: an instance still running the previous release during a rollout included. The application no
-- longer maps it. The function body is dollar-quoted, so the file runs as one statement (splitStatements="false").
CREATE OR REPLACE FUNCTION security.urha_keep_no_plain_password()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    NEW.temp_plain_password := NULL;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_urha_keep_no_plain_password ON security.user_role_hospital_assignment;

CREATE TRIGGER trg_urha_keep_no_plain_password
    BEFORE INSERT OR UPDATE OF temp_plain_password ON security.user_role_hospital_assignment
    FOR EACH ROW
    EXECUTE FUNCTION security.urha_keep_no_plain_password();
