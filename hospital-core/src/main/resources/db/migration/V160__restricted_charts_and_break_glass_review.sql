-- V160: E8 #54 — restricted charts and break-the-glass review.
--
-- (a) A chart-restriction flag on the patient row. VIPs, staff members, a
--     clinician's own record or a family member's: the hospital administrator
--     marks the chart restricted, and from then on every read at the hospital
--     — including by staff who hold a registration-based relationship —
--     requires a live break-the-glass session (stated reason, time-boxed,
--     audited). The flag is intra-organisational: it is not the sensitive
--     category tag (V158), which withholds rows cross-hospital.
ALTER TABLE clinical.patients
    ADD COLUMN IF NOT EXISTS chart_restricted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS chart_restriction_reason VARCHAR(512),
    ADD COLUMN IF NOT EXISTS chart_restricted_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS chart_restricted_by_user_id UUID;

-- (b) A reviewed / sign-off state on every session, so the compliance screen
--     can work a queue rather than re-read the whole register: who reviewed
--     it, when, with what outcome and note. NULL reviewed_at = not yet reviewed.
ALTER TABLE clinical.break_glass_sessions
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS reviewed_by_user_id UUID,
    ADD COLUMN IF NOT EXISTS review_outcome VARCHAR(32),
    ADD COLUMN IF NOT EXISTS review_note VARCHAR(1024);

-- The review queue: unreviewed sessions of one hospital, most recent first.
CREATE INDEX IF NOT EXISTS idx_bg_sessions_hospital_unreviewed
    ON clinical.break_glass_sessions (hospital_id, started_at DESC)
    WHERE reviewed_at IS NULL;
