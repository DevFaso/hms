-- =============================================================
-- V121: slot inventory — foundation pass (P2 #11).
--
-- WHY
--   Scheduling today books an Appointment against an arbitrary
--   (staff, date, start_time, end_time). Nothing describes what a
--   clinic session IS, so nothing can answer "when is Dr Kabore
--   next free for a 30-minute follow-up?" without the caller
--   already knowing the answer. That single missing question is
--   what blocks patient self-scheduling, waitlist auto-offer
--   (P3 #22) and utilisation reporting alike — they are three
--   readings of one model.
--
--   Three tables, in dependency order:
--
--   visit_types       — what an appointment IS, and how long it
--                       takes. Duration belongs to the visit type,
--                       not to whoever types the end time.
--   session_templates — a RECURRING clinic session: this clinician,
--                       this department, this weekday, these hours,
--                       sliced this finely, between these dates.
--   appointment_slots — the concrete, bookable units generated from
--                       a template. Materialised rather than
--                       computed on read, because a slot has state
--                       (held, booked, blocked) that a derived view
--                       cannot carry.
--
-- WHY MATERIALISE
--   A computed-on-the-fly view of "template minus existing
--   appointments" cannot express a slot held for 10 minutes while a
--   patient finishes paying, or one blocked for a meeting, and it
--   makes double-booking a race rather than a constraint. The
--   UNIQUE index on (staff_id, start_at) below is what actually
--   prevents two patients being given the same moment — enforcement
--   in the database, not in whichever service remembered to check.
--
-- REAL FOREIGN KEYS
--   These are new tables with no legacy rows, so unlike everything
--   V1 generated they can carry the constraints their mappings
--   claim. ON DELETE CASCADE from template to slot: deleting a
--   session means its unbooked slots go with it. The appointment
--   link is ON DELETE SET NULL — cancelling an appointment must
--   free the slot, not destroy it.
-- =============================================================

CREATE TABLE IF NOT EXISTS clinical.visit_types (
    id                  UUID PRIMARY KEY,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id         UUID NOT NULL,
    department_id       UUID,
    code                VARCHAR(40) NOT NULL,
    name                VARCHAR(150) NOT NULL,
    description         VARCHAR(500),
    duration_minutes    INTEGER NOT NULL,
    patient_bookable    BOOLEAN NOT NULL DEFAULT FALSE,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_visit_type_hospital FOREIGN KEY (hospital_id)
        REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_visit_type_department FOREIGN KEY (department_id)
        REFERENCES hospital.departments (id),
    CONSTRAINT uq_visit_type_code_hospital UNIQUE (hospital_id, code),
    CONSTRAINT ck_visit_type_duration CHECK (duration_minutes > 0)
);

CREATE INDEX IF NOT EXISTS idx_visit_type_hospital ON clinical.visit_types (hospital_id, active);

CREATE TABLE IF NOT EXISTS clinical.session_templates (
    id                  UUID PRIMARY KEY,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id         UUID NOT NULL,
    department_id       UUID NOT NULL,
    staff_id            UUID NOT NULL,
    visit_type_id       UUID,
    -- ISO-8601: 1 = Monday .. 7 = Sunday, matching java.time.DayOfWeek.
    day_of_week         SMALLINT NOT NULL,
    start_time          TIME NOT NULL,
    end_time            TIME NOT NULL,
    slot_minutes        INTEGER NOT NULL,
    capacity_per_slot   INTEGER NOT NULL DEFAULT 1,
    effective_from      DATE NOT NULL,
    effective_to        DATE,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    notes               VARCHAR(500),
    CONSTRAINT fk_session_template_hospital FOREIGN KEY (hospital_id)
        REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_session_template_department FOREIGN KEY (department_id)
        REFERENCES hospital.departments (id),
    CONSTRAINT fk_session_template_staff FOREIGN KEY (staff_id)
        REFERENCES hospital.staff (id),
    CONSTRAINT fk_session_template_visit_type FOREIGN KEY (visit_type_id)
        REFERENCES clinical.visit_types (id),
    CONSTRAINT ck_session_template_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_session_template_window CHECK (end_time > start_time),
    CONSTRAINT ck_session_template_slot CHECK (slot_minutes > 0),
    CONSTRAINT ck_session_template_capacity CHECK (capacity_per_slot > 0)
);

CREATE INDEX IF NOT EXISTS idx_session_template_lookup
    ON clinical.session_templates (hospital_id, active, day_of_week);

CREATE TABLE IF NOT EXISTS clinical.appointment_slots (
    id                  UUID PRIMARY KEY,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    session_template_id UUID,
    hospital_id         UUID NOT NULL,
    department_id       UUID NOT NULL,
    staff_id            UUID NOT NULL,
    visit_type_id       UUID,
    slot_date           DATE NOT NULL,
    start_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    end_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    appointment_id      UUID,
    held_until          TIMESTAMP WITHOUT TIME ZONE,
    held_by_user_id     UUID,
    blocked_reason      VARCHAR(255),
    CONSTRAINT fk_slot_template FOREIGN KEY (session_template_id)
        REFERENCES clinical.session_templates (id) ON DELETE CASCADE,
    CONSTRAINT fk_slot_hospital FOREIGN KEY (hospital_id)
        REFERENCES hospital.hospitals (id),
    CONSTRAINT fk_slot_department FOREIGN KEY (department_id)
        REFERENCES hospital.departments (id),
    CONSTRAINT fk_slot_staff FOREIGN KEY (staff_id)
        REFERENCES hospital.staff (id),
    CONSTRAINT fk_slot_visit_type FOREIGN KEY (visit_type_id)
        REFERENCES clinical.visit_types (id),
    CONSTRAINT fk_slot_appointment FOREIGN KEY (appointment_id)
        REFERENCES clinical.appointments (id) ON DELETE SET NULL,
    CONSTRAINT ck_slot_window CHECK (end_at > start_at)
);

-- THE constraint that makes this model worth building: one clinician
-- cannot be in two places at one moment, enforced by the database
-- rather than by whichever service remembered to check.
CREATE UNIQUE INDEX IF NOT EXISTS uq_slot_staff_start
    ON clinical.appointment_slots (staff_id, start_at);

-- The open-slot search: "who is free, in this department, over this
-- window". Partial index because a search for availability is only
-- ever interested in OPEN rows, and booked history dominates volume
-- within weeks of go-live.
CREATE INDEX IF NOT EXISTS idx_slot_search_open
    ON clinical.appointment_slots (hospital_id, department_id, slot_date, start_at)
    WHERE status = 'OPEN';

CREATE INDEX IF NOT EXISTS idx_slot_appointment
    ON clinical.appointment_slots (appointment_id);

-- Reclaiming expired holds sweeps on this.
CREATE INDEX IF NOT EXISTS idx_slot_held_until
    ON clinical.appointment_slots (held_until)
    WHERE status = 'HELD';
