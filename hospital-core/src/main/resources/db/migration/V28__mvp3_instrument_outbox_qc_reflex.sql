-- =============================================================================
-- V28: MVP3 - Instrument Integration, QC Events & Reflex Rules
-- =============================================================================

-- ── Instrument Outbox (outbound HL7v2 orders queue) ──────────────────────────
CREATE TABLE IF NOT EXISTS lab.instrument_outbox (
    id              UUID          NOT NULL DEFAULT gen_random_uuid(),
    lab_order_id    UUID          NOT NULL,
    message_type    VARCHAR(20)   NOT NULL,   -- OML^O21, ORU^R01, OML_CANCEL
    payload         TEXT          NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',  -- PENDING, SENT, ACK, ERROR
    created_at      TIMESTAMP     NOT NULL DEFAULT now(),
    sent_at         TIMESTAMP,

    CONSTRAINT pk_instrument_outbox         PRIMARY KEY (id),
    CONSTRAINT fk_instrument_outbox_order   FOREIGN KEY (lab_order_id)
                                            REFERENCES lab.lab_orders(id)
);

CREATE INDEX IF NOT EXISTS idx_instrument_outbox_order  ON lab.instrument_outbox(lab_order_id);
CREATE INDEX IF NOT EXISTS idx_instrument_outbox_status ON lab.instrument_outbox(status);

-- ── QC Events ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS lab.qc_events (
    id                  UUID          NOT NULL DEFAULT gen_random_uuid(),
    hospital_id         UUID          NOT NULL,
    analyzer_id         VARCHAR(100),
    test_definition_id  UUID,
    qc_level            VARCHAR(30)   NOT NULL,   -- LOW_CONTROL, HIGH_CONTROL
    measured_value      NUMERIC(18,6) NOT NULL,
    expected_value      NUMERIC(18,6) NOT NULL,
    passed              BOOLEAN       NOT NULL DEFAULT FALSE,
    recorded_at         TIMESTAMP     NOT NULL DEFAULT now(),
    recorded_by_id      UUID,
    notes               VARCHAR(2048),
    created_at          TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_qc_events                 PRIMARY KEY (id),
    CONSTRAINT fk_qc_events_testdef         FOREIGN KEY (test_definition_id)
                                            REFERENCES lab.lab_test_definitions(id)
);

CREATE INDEX IF NOT EXISTS idx_qc_events_hospital    ON lab.qc_events(hospital_id);
CREATE INDEX IF NOT EXISTS idx_qc_events_testdef     ON lab.qc_events(test_definition_id);
CREATE INDEX IF NOT EXISTS idx_qc_events_recorded_at ON lab.qc_events(recorded_at);

-- ── Reflex / Add-On Test Rules ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS lab.reflex_rules (
    id               UUID          NOT NULL DEFAULT gen_random_uuid(),
    trigger_test_id  UUID          NOT NULL,
    condition        TEXT          NOT NULL,   -- JSON: { "severityFlag": "ABNORMAL" } or { "thresholdOperator": "GT", "thresholdValue": 11.0 }
    reflex_test_id   UUID          NOT NULL,
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    description      VARCHAR(512),
    created_at       TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_reflex_rules              PRIMARY KEY (id),
    CONSTRAINT fk_reflex_rules_trigger      FOREIGN KEY (trigger_test_id)
                                            REFERENCES lab.lab_test_definitions(id),
    CONSTRAINT fk_reflex_rules_reflex       FOREIGN KEY (reflex_test_id)
                                            REFERENCES lab.lab_test_definitions(id)
);

CREATE INDEX IF NOT EXISTS idx_reflex_rules_trigger ON lab.reflex_rules(trigger_test_id);
CREATE INDEX IF NOT EXISTS idx_reflex_rules_active  ON lab.reflex_rules(active);
