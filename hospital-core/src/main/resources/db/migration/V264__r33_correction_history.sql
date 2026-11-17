-- V264 (QA round 33, CX-009): what a corrected culture report said before each
-- correction. The report keeps only its latest correction reason, and a removed
-- isolate is a deleted row, so once a correction took an organism off a validated
-- report nothing said it had ever been there. One row per correction, written
-- before the change, with the report's words at that moment. New table, real
-- foreign keys, same schema and same sensitivity as the report it describes.
-- Plain statements, no DO block. Idempotent.

CREATE TABLE IF NOT EXISTS lab.micro_culture_corrections (
    id                   UUID          NOT NULL DEFAULT gen_random_uuid(),
    culture_result_id    UUID          NOT NULL,
    corrected_at         TIMESTAMP     NOT NULL,
    corrected_by_user_id UUID,
    reason               VARCHAR(500)  NOT NULL,
    status_before        VARCHAR(20)   NOT NULL,
    growth_before        VARCHAR(20),
    gram_stain_before    VARCHAR(255),
    notes_before         VARCHAR(1000),
    isolates_before      TEXT,
    created_at           TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_micro_culture_corrections PRIMARY KEY (id),
    CONSTRAINT fk_micro_correction_culture  FOREIGN KEY (culture_result_id)    REFERENCES lab.micro_culture_results(id) ON DELETE CASCADE,
    CONSTRAINT fk_micro_correction_user     FOREIGN KEY (corrected_by_user_id) REFERENCES security.users(id) ON DELETE NO ACTION
);

CREATE INDEX IF NOT EXISTS idx_micro_correction_culture
    ON lab.micro_culture_corrections (culture_result_id, corrected_at);

-- QA CX-011: a repeat screen that disagrees with the standing group must give a
-- reason. The reason was checked and then dropped: nothing stored it, so the history
-- showed two groups and never why they differ.
ALTER TABLE clinical.patient_blood_groups
    ADD COLUMN IF NOT EXISTS correction_reason VARCHAR(500);
