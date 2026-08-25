-- V136: isolation precautions (Tier 2 item 32).
--
-- WHY: verified zero code. Every `Isolation` hit in the codebase was either
-- TenantIsolationMode (a multi-tenancy concept, nothing to do with infection
-- control) or PatientSocialHistory.social_isolation_risk (a social
-- determinant). Nothing anywhere records that a patient is on contact,
-- droplet or airborne precautions.
--
-- For a facility that sees TB, cholera, Lassa and measles that is not a
-- missing feature, it is a missing safety control. The people who most need
-- to know are the ones the chart never tells: the porter moving the bed, the
-- nurse taking the next observation round, the clerk assigning the next
-- admission to the free bed in the same bay.
--
-- ITS OWN TABLE, not Admission.metadata. The metadata jsonb column carries a
-- comment suggesting "isolation precautions" as an example use, and that is
-- the wrong home for this. A precaution has to be queryable (the bed board
-- filters on it), indexable (the board loads a whole ward at once),
-- constrained (the type is a closed clinical vocabulary, not free text) and
-- auditable (who ordered it, who stopped it, and when). A jsonb blob gives
-- none of those.
--
-- MULTIPLE CONCURRENT PRECAUTIONS ARE NORMAL, so this is a child table and
-- not an enum column on the admission. A viral haemorrhagic fever is contact
-- AND droplet; a neutropenic patient on protective isolation may also be on
-- contact precautions for a colonising organism. Collapsing that to one
-- value would force a clinician to pick which risk to under-communicate.
--
-- ACTIVE IS ended_at IS NULL. Precautions are discontinued, never deleted:
-- the fact that a patient WAS on airborne precautions last week is exactly
-- what a contact-tracing question needs, so the row survives.
--
-- Admission is NULLABLE on purpose. Precautions start in the emergency
-- department before anyone is admitted, which is precisely when they matter
-- most — the decision they drive is which bed the patient may be given.
-- patient_id is the required link; admission_id is set when there is one.
--
-- No DO block, so no splitStatements attribute.

CREATE TABLE IF NOT EXISTS clinical.isolation_precautions (
    id                      UUID         NOT NULL,
    hospital_id             UUID         NOT NULL,
    patient_id              UUID         NOT NULL,
    admission_id            UUID,

    -- Closed clinical vocabulary: CONTACT, DROPLET, AIRBORNE, PROTECTIVE.
    precaution_type         VARCHAR(20)  NOT NULL,

    -- Why the precaution exists. suspected_organism is separate from reason
    -- because the organism drives the ward-compatibility rule while the
    -- reason is what a clinician reads.
    reason                  VARCHAR(500) NOT NULL,
    suspected_organism      VARCHAR(120),

    started_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    ordered_by_staff_id     UUID,

    -- NULL = still in force. This is the column every read filters on.
    ended_at                TIMESTAMP WITHOUT TIME ZONE,
    discontinued_by_staff_id UUID,
    discontinuation_reason  VARCHAR(500),

    notes                   VARCHAR(1000),

    created_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL,

    CONSTRAINT pk_isolation_precautions PRIMARY KEY (id),
    CONSTRAINT fk_isolation_hospital  FOREIGN KEY (hospital_id)
        REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_isolation_patient   FOREIGN KEY (patient_id)
        REFERENCES clinical.patients (id),
    CONSTRAINT fk_isolation_admission FOREIGN KEY (admission_id)
        REFERENCES public.admissions (id),
    -- A precaution cannot end before it began.
    CONSTRAINT ck_isolation_period CHECK (ended_at IS NULL OR ended_at >= started_at)
);

-- The bed board loads every active precaution for a hospital in one go, and
-- the chart loads them per patient. Both filter on ended_at IS NULL, so the
-- indexes are partial - an ended precaution is history and is never in the
-- hot path.
CREATE INDEX IF NOT EXISTS idx_isolation_active_by_hospital
    ON clinical.isolation_precautions (hospital_id)
    WHERE ended_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_isolation_active_by_patient
    ON clinical.isolation_precautions (patient_id)
    WHERE ended_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_isolation_by_admission
    ON clinical.isolation_precautions (admission_id);

-- Stops the same precaution type being raised twice on one patient while the
-- first is still in force. Without it, two nurses acting on the same result
-- produce two CONTACT rows and the banner double-counts.
CREATE UNIQUE INDEX IF NOT EXISTS uk_isolation_active_type_per_patient
    ON clinical.isolation_precautions (patient_id, precaution_type)
    WHERE ended_at IS NULL;
