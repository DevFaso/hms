-- V140: provider credentialing renewal (Tier 2 item 40).
--
-- WHY: the column was already there and meant nothing. hospital.staff has
-- carried license_number and license_expiry_date since V1, StaffRepository
-- has a query named "MVP 19: License expiry alerts" that finds staff whose
-- licence expires before a cutoff, and HospitalAdminDashboardServiceImpl
-- already grades each one EXPIRED / CRITICAL / WARNING. Nothing renews it,
-- nothing verifies it, and nothing tells anybody. The system knows a
-- clinician's licence expires next week and says so only to whoever happens
-- to open a dashboard page. That is not an alert; it is a fact sitting in a
-- table.
--
-- A HISTORY TABLE, AND YES, THAT IS THE OPPOSITE CALL FROM V139. One
-- migration ago the argument was three columns and no child table, because a
-- prescription has at most one live pharmacist verification and nothing
-- accumulates. Credentialing is the other shape and the difference is worth
-- stating so the two do not read as inconsistent: a licence is renewed again
-- and again over a career, and the question that matters after the fact is
-- not "is this clinician licensed now" but "was this clinician licensed on
-- the day they prescribed that". Only a history answers that. Overwriting
-- license_expiry_date in place destroys the evidence that somebody practised
-- past their expiry, which is precisely the thing this item exists to make
-- visible.
--
-- ALERT STAGE, SO THE ALERT STAYS AN ALERT. license_alert_stage records the
-- most severe threshold a staff member has already been notified about. The
-- sweep fires only when the stage ADVANCES (nothing -> WARNING -> CRITICAL
-- -> EXPIRED). A daily re-notification for the same licence would be worse
-- than silence: it trains an administrator to dismiss the whole category,
-- and then the one that mattered goes with it. Recording a renewal clears
-- the stage, so a licence that later drifts toward expiry alerts again from
-- scratch.
--
-- WHAT THIS DELIBERATELY DOES NOT DO: it does not block anything. An expired
-- licence does not stop a prescription, a sign-off, or a login. Whether it
-- should is a policy decision with real consequences in both directions —
-- an administrator who forgets to enter a renewal date could otherwise take
-- a working doctor offline mid-shift — and it is not a decision to encode
-- from the schema. Recorded on the roadmap for a decision; see item 40.
--
-- Nothing is backfilled. license_verified_at stays NULL on every existing
-- row because nobody has verified those licences: the number was typed into
-- a form and never checked against an issuing authority. Claiming otherwise
-- would be a false assurance, the same one V139 refused to manufacture.
--
-- No DO block, so no splitStatements attribute.

ALTER TABLE hospital.staff
    ADD COLUMN IF NOT EXISTS license_issuing_authority VARCHAR(200),
    ADD COLUMN IF NOT EXISTS license_verified_at       TIMESTAMP,
    ADD COLUMN IF NOT EXISTS license_verified_by       UUID,
    ADD COLUMN IF NOT EXISTS license_alert_stage       VARCHAR(16);

COMMENT ON COLUMN hospital.staff.license_issuing_authority IS
    'The body that issued the licence — ordre des medecins, ordre des '
    'pharmaciens, a nursing council. Free text: this deployment spans '
    'professions whose registries are not modelled here, and a lookup table '
    'nobody maintains is worse than a field somebody fills in.';

COMMENT ON COLUMN hospital.staff.license_verified_at IS
    'When somebody last checked this licence against its issuing authority. '
    'NULL means never — including on every pre-V140 row, which is the truth: '
    'the number was typed into a form and never verified.';

COMMENT ON COLUMN hospital.staff.license_verified_by IS
    'Who performed that check. Never the staff member themselves — '
    'self-verification is refused in the service, the same stance the '
    'co-sign and pharmacist-verification ceremonies take.';

COMMENT ON COLUMN hospital.staff.license_alert_stage IS
    'Most severe expiry threshold already notified: WARNING, CRITICAL or '
    'EXPIRED. The sweep notifies only when this advances, so an '
    'administrator is not sent the same warning every morning until they '
    'stop reading the category. Cleared when a renewal is recorded.';

ALTER TABLE hospital.staff
    DROP CONSTRAINT IF EXISTS fk_staff_license_verified_by;
ALTER TABLE hospital.staff
    ADD CONSTRAINT fk_staff_license_verified_by
        FOREIGN KEY (license_verified_by)
        REFERENCES security.users (id)
        ON DELETE SET NULL
        NOT VALID;

-- The sweep's only question is "whose licence is expiring or expired", so
-- the index covers exactly that and skips the rows with no date at all —
-- which is most of them, since the column has always been optional.
CREATE INDEX IF NOT EXISTS idx_staff_license_expiry_sweep
    ON hospital.staff (hospital_id, license_expiry_date)
    WHERE license_expiry_date IS NOT NULL;

-- ── Renewal history ──────────────────────────────────────────────────────
--
-- previous_* is stored alongside the new values rather than reconstructed by
-- walking the chain. Reconstruction breaks the moment a row is corrected or
-- a licence is recorded out of order, and the whole point of this table is
-- to be readable by somebody investigating an incident years later.

CREATE TABLE IF NOT EXISTS hospital.staff_credential_renewals (
    id                       UUID PRIMARY KEY,
    staff_id                 UUID         NOT NULL,
    hospital_id              UUID         NOT NULL,

    previous_license_number  VARCHAR(100),
    previous_expiry_date     DATE,

    license_number           VARCHAR(100),
    expiry_date              DATE         NOT NULL,
    issuing_authority        VARCHAR(200),
    note                     VARCHAR(1000),

    recorded_by              UUID         NOT NULL,
    recorded_at              TIMESTAMP    NOT NULL,

    created_at               TIMESTAMP    NOT NULL,
    updated_at               TIMESTAMP    NOT NULL
);

COMMENT ON TABLE hospital.staff_credential_renewals IS
    'Every credential renewal ever recorded, append-only in practice. Exists '
    'so "was this clinician licensed on the day they prescribed that" has an '
    'answer; overwriting hospital.staff in place would destroy it.';

COMMENT ON COLUMN hospital.staff_credential_renewals.expiry_date IS
    'The new expiry being recorded. NOT NULL: a renewal with no end date is '
    'not a renewal, it is a deletion of the expiry rule.';

COMMENT ON COLUMN hospital.staff_credential_renewals.previous_expiry_date IS
    'What the licence said immediately before this row was written. NULL '
    'when the staff member had no expiry on file — a first recording rather '
    'than a renewal.';

ALTER TABLE hospital.staff_credential_renewals
    DROP CONSTRAINT IF EXISTS fk_credential_renewal_staff;
ALTER TABLE hospital.staff_credential_renewals
    ADD CONSTRAINT fk_credential_renewal_staff
        FOREIGN KEY (staff_id)
        REFERENCES hospital.staff (id)
        ON DELETE CASCADE;

ALTER TABLE hospital.staff_credential_renewals
    DROP CONSTRAINT IF EXISTS fk_credential_renewal_hospital;
ALTER TABLE hospital.staff_credential_renewals
    ADD CONSTRAINT fk_credential_renewal_hospital
        FOREIGN KEY (hospital_id)
        REFERENCES hospital.hospitals (id)
        ON DELETE CASCADE;

ALTER TABLE hospital.staff_credential_renewals
    DROP CONSTRAINT IF EXISTS fk_credential_renewal_recorded_by;
ALTER TABLE hospital.staff_credential_renewals
    ADD CONSTRAINT fk_credential_renewal_recorded_by
        FOREIGN KEY (recorded_by)
        REFERENCES security.users (id)
        ON DELETE RESTRICT;

-- ON DELETE RESTRICT above, not SET NULL: an audit row whose author can be
-- erased is not an audit row. Users are soft-deleted here anyway
-- (users.is_deleted), so this refuses only a hard delete, which is what we
-- want it to refuse.

-- The history is always read for one staff member, newest first.
CREATE INDEX IF NOT EXISTS idx_credential_renewal_staff_recent
    ON hospital.staff_credential_renewals (staff_id, recorded_at DESC);

CREATE INDEX IF NOT EXISTS idx_credential_renewal_hospital
    ON hospital.staff_credential_renewals (hospital_id);
