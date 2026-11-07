-- V266 (user-supplied guidance 2026-11-06): one row per bag released in an emergency: its type, the
-- reason, who decided or authorised, who issued, when. Beside the crossmatch and not on it: a
-- serologically incompatible release leaves the laboratory's incompatible result as it was.
-- reason is encrypted by the entity's converter, hence TEXT. Releases before this table have
-- their audit row only: there is no reason or decider to backfill.
-- Plain statements, no DO block. Idempotent.

CREATE TABLE IF NOT EXISTS clinical.emergency_blood_releases (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    request_id          UUID         NOT NULL,
    blood_unit_id       UUID         NOT NULL,
    hospital_id         UUID         NOT NULL,
    release_type        VARCHAR(40)  NOT NULL,
    reason              TEXT,
    authoriser_user_id  UUID,
    issued_by_user_id   UUID,
    issued_at           TIMESTAMP    NOT NULL,
    antibody_warning    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_emergency_blood_releases      PRIMARY KEY (id),
    CONSTRAINT fk_emergency_release_request     FOREIGN KEY (request_id)         REFERENCES clinical.transfusion_requests(id),
    CONSTRAINT fk_emergency_release_unit        FOREIGN KEY (blood_unit_id)      REFERENCES clinical.blood_units(id),
    CONSTRAINT fk_emergency_release_hospital    FOREIGN KEY (hospital_id)        REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_emergency_release_authoriser  FOREIGN KEY (authoriser_user_id) REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT fk_emergency_release_issuer      FOREIGN KEY (issued_by_user_id)  REFERENCES security.users(id) ON DELETE NO ACTION,
    CONSTRAINT ck_emergency_release_type        CHECK (release_type IN ('UNCROSSMATCHED', 'O_POSITIVE_DECISION', 'SEROLOGICALLY_INCOMPATIBLE')),
    CONSTRAINT ck_emergency_release_documented  CHECK (release_type = 'UNCROSSMATCHED'
        OR (reason IS NOT NULL AND authoriser_user_id IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_emergency_release_request
    ON clinical.emergency_blood_releases (request_id, blood_unit_id, issued_at);
