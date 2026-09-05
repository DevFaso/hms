-- V153: standardized patient-reported outcomes -- EPDS first (Tier 2 item 47).
--
-- WHY: the only depression-screening field in the product is
-- maternal_history.depression_screening_score -- a bare integer with no
-- instrument, no range and no interpretation. The check-in questionnaires
-- (V39) cannot carry a scored instrument either: a response is bound to an
-- appointment (NOT NULL + UNIQUE per appointment), so a screen repeated
-- across the postpartum cadence has nowhere to go, and nothing scores it.
--
-- The instrument is DATA, not code. Every response option carries its own
-- score, so the scoring direction of an item is never hard-coded -- it comes
-- from the validated source along with the text. critical_item_no marks the
-- one item whose non-zero score escalates REGARDLESS of the total (EPDS item
-- 10, self-harm). The scoring engine only ever sums option scores, compares
-- the total to positive_threshold, and looks at the critical item.
--
-- Item and option TEXT is deliberately NOT written by whoever wrote this
-- migration (the V120 rule: clinical content belongs to its author, never to
-- model memory). Texts are loaded from the validated source through the
-- SUPER_ADMIN import endpoint (PUT /pro-instruments/{code}) -- the durable
-- half of this change -- and, once supplied, seeded here with attribution.
--
-- Responses are STORED, unlike NEWS2 (computed on read): an administered
-- instrument's answers ARE the record, and the trend across the cadence is
-- the clinical point. answers and notes are TEXT because the app encrypts
-- them at rest (EncryptedStringConverter); the score columns stay plain so
-- the trend can be read without decrypting anything.
--
-- The escalation columns mirror lab_results' critical-value chain (P0 #5):
-- a self-harm-positive response is notified on write and re-escalated by a
-- sweep until somebody acknowledges it. Going silent is the failure mode.
--
-- Rollback:
--   DROP TABLE clinical.pro_responses;
--   DROP TABLE clinical.pro_instrument_texts;
--   DROP TABLE clinical.pro_instrument_options;
--   DROP TABLE clinical.pro_instrument_items;
--   DROP TABLE clinical.pro_instruments;
-- =============================================================================

CREATE TABLE IF NOT EXISTS clinical.pro_instruments (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    code                VARCHAR(40)  NOT NULL,
    name                VARCHAR(160) NOT NULL,
    version             VARCHAR(40),
    source_citation     VARCHAR(500) NOT NULL,
    licence_note        VARCHAR(500),
    max_score           INT          NOT NULL,
    positive_threshold  INT          NOT NULL,
    critical_item_no    INT,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_pro_instruments PRIMARY KEY (id),
    CONSTRAINT uq_pro_instrument_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS clinical.pro_instrument_items (
    id             UUID NOT NULL DEFAULT gen_random_uuid(),
    instrument_id  UUID NOT NULL,
    item_no        INT  NOT NULL,

    CONSTRAINT pk_pro_instrument_items PRIMARY KEY (id),
    CONSTRAINT fk_pro_item_instrument FOREIGN KEY (instrument_id)
        REFERENCES clinical.pro_instruments(id) ON DELETE CASCADE,
    CONSTRAINT uq_pro_item_no UNIQUE (instrument_id, item_no)
);

-- The score lives on the OPTION. An item scored 0-1-2-3 and one scored
-- 3-2-1-0 look identical to the engine; the difference is data.
CREATE TABLE IF NOT EXISTS clinical.pro_instrument_options (
    id         UUID NOT NULL DEFAULT gen_random_uuid(),
    item_id    UUID NOT NULL,
    option_no  INT  NOT NULL,
    score      INT  NOT NULL,

    CONSTRAINT pk_pro_instrument_options PRIMARY KEY (id),
    CONSTRAINT fk_pro_option_item FOREIGN KEY (item_id)
        REFERENCES clinical.pro_instrument_items(id) ON DELETE CASCADE,
    CONSTRAINT uq_pro_option_no UNIQUE (item_id, option_no)
);

-- One row per (language, item, option). item_no 0 / option_no 0 is the
-- instrument's own instruction text; option_no 0 on an item is its prompt.
-- Zero rather than NULL so the UNIQUE constraint actually holds.
CREATE TABLE IF NOT EXISTS clinical.pro_instrument_texts (
    id             UUID       NOT NULL DEFAULT gen_random_uuid(),
    instrument_id  UUID       NOT NULL,
    language       VARCHAR(8) NOT NULL,
    item_no        INT        NOT NULL DEFAULT 0,
    option_no      INT        NOT NULL DEFAULT 0,
    text           TEXT       NOT NULL,

    CONSTRAINT pk_pro_instrument_texts PRIMARY KEY (id),
    CONSTRAINT fk_pro_text_instrument FOREIGN KEY (instrument_id)
        REFERENCES clinical.pro_instruments(id) ON DELETE CASCADE,
    CONSTRAINT uq_pro_text UNIQUE (instrument_id, language, item_no, option_no)
);

CREATE TABLE IF NOT EXISTS clinical.pro_responses (
    id                        UUID         NOT NULL DEFAULT gen_random_uuid(),
    instrument_id             UUID         NOT NULL,
    patient_id                UUID         NOT NULL,
    hospital_id               UUID         NOT NULL,
    postpartum_care_plan_id   UUID,
    source                    VARCHAR(30)  NOT NULL,
    language                  VARCHAR(8),
    administered_at           TIMESTAMP    NOT NULL,
    recorded_by_user_id       UUID,
    answers                   TEXT         NOT NULL,
    notes                     TEXT,
    total_score               INT          NOT NULL,
    -- Snapshot of the definition the answers were scored against. A later
    -- import may replace the instrument's items and option scores; the
    -- denominator this response was read on must not move with it.
    max_score                 INT          NOT NULL,
    instrument_version        VARCHAR(40),
    answered_items            INT          NOT NULL,
    total_items               INT          NOT NULL,
    complete                  BOOLEAN      NOT NULL,
    screen_positive           BOOLEAN      NOT NULL,
    critical_item_score       INT,
    critical_item_positive    BOOLEAN      NOT NULL DEFAULT FALSE,
    notified_at               TIMESTAMP,
    escalation_level          SMALLINT     NOT NULL DEFAULT 0,
    last_escalation_at        TIMESTAMP,
    acknowledged_at           TIMESTAMP,
    acknowledged_by_user_id   UUID,
    acknowledged_by_display   VARCHAR(200),
    -- What was done about the disclosure. Encrypted at rest (narrative).
    acknowledgement_note      TEXT,
    version                   BIGINT       NOT NULL DEFAULT 0,
    created_at                TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at                TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_pro_responses PRIMARY KEY (id),
    CONSTRAINT fk_pro_response_instrument FOREIGN KEY (instrument_id)
        REFERENCES clinical.pro_instruments(id),
    -- A screening response is part of the clinical record and outlives
    -- nothing: like every other chart table (labor episodes, delivery
    -- records, transfusions), it blocks a hard delete of its patient
    -- rather than vanishing with it or dangling without it.
    CONSTRAINT fk_pro_response_patient FOREIGN KEY (patient_id)
        REFERENCES clinical.patients(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pro_response_hospital FOREIGN KEY (hospital_id)
        REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_pro_response_care_plan FOREIGN KEY (postpartum_care_plan_id)
        REFERENCES clinical.postpartum_care_plans(id)
);

-- Trend per patient, newest first.
CREATE INDEX IF NOT EXISTS idx_pro_responses_patient
    ON clinical.pro_responses (patient_id, administered_at DESC);
-- "Has this plan been screened yet" -- the cadence hook.
CREATE INDEX IF NOT EXISTS idx_pro_responses_care_plan
    ON clinical.pro_responses (postpartum_care_plan_id, administered_at DESC);
-- The escalation sweep: unacknowledged critical responses only.
CREATE INDEX IF NOT EXISTS idx_pro_responses_critical_open
    ON clinical.pro_responses (last_escalation_at)
    WHERE critical_item_positive AND acknowledged_at IS NULL;

COMMENT ON TABLE clinical.pro_instruments IS
    'Standardized PRO instruments (Tier 2 item 47). Content is data from a '
    'validated source: option scores and text are loaded, never coded.';
COMMENT ON TABLE clinical.pro_responses IS
    'Administered PRO responses. answers/notes encrypted at rest; a '
    'critical-item-positive response escalates until acknowledged.';
