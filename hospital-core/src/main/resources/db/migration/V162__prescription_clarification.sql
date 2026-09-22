-- V162: pharmacy flow audit gap G5 — the pharmacist's clarification request.
--
-- PENDING_CLARIFICATION has been a PrescriptionStatus since the pharmacy
-- module shipped and the prescriber's dashboard tile counts it, but nothing
-- could write it except a client-asserted PUT the pharmacist cannot call, and
-- nothing recorded WHAT needed clarifying. These columns carry the exchange:
-- the pharmacist's question (encrypted at rest like the prescription notes —
-- it is clinical narrative), who asked and when, and the prescriber's answer,
-- who answered and when. The two "by" columns hold user ids without a FK,
-- the shape V160 chose for chart_restricted_by_user_id: users are retired,
-- not hard-deleted, and the audit row is the durable record of the actor.
ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS clarification_reason TEXT,
    ADD COLUMN IF NOT EXISTS clarification_requested_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS clarification_requested_by_user_id UUID,
    ADD COLUMN IF NOT EXISTS clarification_response TEXT,
    ADD COLUMN IF NOT EXISTS clarification_resolved_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS clarification_resolved_by_user_id UUID,
    -- The status the order held when the question was asked, restored on
    -- resolve: a partially filled or back-ordered order keeps its progress.
    ADD COLUMN IF NOT EXISTS clarification_previous_status VARCHAR(40);
