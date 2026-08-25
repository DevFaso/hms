-- V137: in-app transfer orders (Tier 2 item 30).
--
-- WHY: verified zero code. TransferOrder, transferPatient and
-- InternalTransfer all returned nothing. Moving a patient between beds or
-- wards existed ONLY as inbound HL7 ADT^A02 — and that path updates
-- Admission.department and nothing else, so it never touches Admission.bed
-- or Bed.status at all. In-app there was no way to move a patient except by
-- reassigning the bed directly, which leaves no record of who ordered the
-- move, why, or where the patient came from.
--
-- AN ORCHESTRATION AND AUDIT LAYER, NOT NEW INVARIANT RISK.
-- BedAssignmentService already owns Admission.bed <-> Bed.status and has
-- since P0 #4. This table records the DECISION to move; the execution still
-- goes through that service, so there is exactly one writer of the invariant.
--
-- TWO STEPS, because a transfer is ordered by one person and carried out by
-- another, minutes or hours later:
--
--   REQUESTED  - the destination bed is held as RESERVED so the ward clerk
--                cannot allocate it to somebody else in the meantime. This
--                is what makes the order worth having rather than a log
--                written after the fact.
--   COMPLETED  - BedAssignmentService performs the move.
--   CANCELLED  - the reservation is released.
--
-- FROM IS A SNAPSHOT, NOT A JOIN. from_bed_id and from_ward_id record where
-- the patient actually was when the order was raised. Reading it back off
-- the admission later would show where they are NOW, which for a completed
-- transfer is the destination — the question "where did they come from"
-- would then answer itself incorrectly.
--
-- ISOLATION OVERRIDE IS RECORDED, NOT PREVENTED. An airborne case must not
-- routinely move to a ward that cannot contain it (V136), but a clinician
-- may have a reason - no isolation bed exists and the patient needs theatre.
-- Refusing outright would push that decision outside the system where
-- nothing records it. So the override is allowed, requires a reason, and is
-- kept.
--
-- No DO block, so no splitStatements attribute.

CREATE TABLE IF NOT EXISTS clinical.transfer_orders (
    id                        UUID         NOT NULL,
    hospital_id               UUID         NOT NULL,
    admission_id              UUID         NOT NULL,
    patient_id                UUID         NOT NULL,

    -- Where the patient was when the order was raised. Snapshot, deliberately.
    from_bed_id               UUID,
    from_ward_id              UUID,

    -- Where they are going. The bed is required: a transfer with no
    -- destination is a request, not an order.
    to_bed_id                 UUID         NOT NULL,
    to_ward_id                UUID         NOT NULL,

    -- BED_TO_BED or WARD_TO_WARD, derived from the wards at order time and
    -- stored so a later ward rename cannot rewrite history.
    transfer_type             VARCHAR(20)  NOT NULL,

    -- REQUESTED, COMPLETED, CANCELLED.
    status                    VARCHAR(20)  NOT NULL,

    reason                    VARCHAR(500) NOT NULL,
    notes                     VARCHAR(1000),

    requested_by_staff_id     UUID,
    requested_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL,

    completed_by_staff_id     UUID,
    completed_at              TIMESTAMP WITHOUT TIME ZONE,

    cancelled_by_staff_id     UUID,
    cancelled_at              TIMESTAMP WITHOUT TIME ZONE,
    cancellation_reason       VARCHAR(500),

    -- Set when the destination cannot contain the patient's active airborne
    -- precaution and a clinician moved them anyway.
    isolation_override        BOOLEAN      NOT NULL DEFAULT FALSE,
    isolation_override_reason VARCHAR(500),

    created_at                TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at                TIMESTAMP WITHOUT TIME ZONE NOT NULL,

    CONSTRAINT pk_transfer_orders PRIMARY KEY (id),
    CONSTRAINT fk_transfer_hospital  FOREIGN KEY (hospital_id)
        REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_transfer_admission FOREIGN KEY (admission_id)
        REFERENCES public.admissions (id),
    CONSTRAINT fk_transfer_patient   FOREIGN KEY (patient_id)
        REFERENCES clinical.patients (id),
    CONSTRAINT fk_transfer_from_bed  FOREIGN KEY (from_bed_id)
        REFERENCES hospital.beds (id),
    CONSTRAINT fk_transfer_to_bed    FOREIGN KEY (to_bed_id)
        REFERENCES hospital.beds (id),
    CONSTRAINT fk_transfer_from_ward FOREIGN KEY (from_ward_id)
        REFERENCES hospital.wards (id),
    CONSTRAINT fk_transfer_to_ward   FOREIGN KEY (to_ward_id)
        REFERENCES hospital.wards (id),

    -- An override without a reason is an override nobody can review.
    CONSTRAINT ck_transfer_override_reason
        CHECK (isolation_override = FALSE OR isolation_override_reason IS NOT NULL)
);

-- The worklist: every transfer still waiting to be carried out, per hospital.
CREATE INDEX IF NOT EXISTS idx_transfer_pending_by_hospital
    ON clinical.transfer_orders (hospital_id, requested_at)
    WHERE status = 'REQUESTED';

-- "Where has this patient been moved" — the admission's transfer history.
CREATE INDEX IF NOT EXISTS idx_transfer_by_admission
    ON clinical.transfer_orders (admission_id, requested_at DESC);

-- Stops two open orders targeting the same destination bed. The bed is held
-- RESERVED between request and completion, but the reservation alone cannot
-- say WHICH order holds it, and two orders racing for one bed means the
-- second patient arrives to find it occupied.
CREATE UNIQUE INDEX IF NOT EXISTS uk_transfer_pending_destination
    ON clinical.transfer_orders (to_bed_id)
    WHERE status = 'REQUESTED';

-- One open transfer per admission: a patient cannot be simultaneously
-- on their way to two places.
CREATE UNIQUE INDEX IF NOT EXISTS uk_transfer_pending_admission
    ON clinical.transfer_orders (admission_id)
    WHERE status = 'REQUESTED';
