-- =============================================================================
-- V25: Ward & Bed Management
-- Adds ward and bed tables to hospital schema for bed/ward inventory tracking.
-- Also adds optional bed_id FK to admissions table.
-- =============================================================================

CREATE TABLE IF NOT EXISTS hospital.wards (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    hospital_id     UUID            NOT NULL,
    department_id   UUID,
    name            VARCHAR(100)    NOT NULL,
    code            VARCHAR(20)     NOT NULL,
    ward_type       VARCHAR(30)     NOT NULL,
    floor           INTEGER,
    description     VARCHAR(500),
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT now(),

    CONSTRAINT pk_ward              PRIMARY KEY (id),
    CONSTRAINT fk_ward_hospital     FOREIGN KEY (hospital_id)   REFERENCES hospital.hospitals(id),
    CONSTRAINT fk_ward_department   FOREIGN KEY (department_id) REFERENCES hospital.departments(id),
    CONSTRAINT uk_ward_hospital_code UNIQUE (hospital_id, code)
);

CREATE INDEX IF NOT EXISTS idx_ward_hospital   ON hospital.wards(hospital_id);
CREATE INDEX IF NOT EXISTS idx_ward_department ON hospital.wards(department_id);

CREATE TABLE IF NOT EXISTS hospital.beds (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    ward_id         UUID            NOT NULL,
    bed_number      VARCHAR(20)     NOT NULL,
    bed_status      VARCHAR(30)     NOT NULL DEFAULT 'AVAILABLE',
    bed_type        VARCHAR(50),
    floor           INTEGER,
    room_number     VARCHAR(20),
    notes           VARCHAR(500),
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT now(),

    CONSTRAINT pk_bed               PRIMARY KEY (id),
    CONSTRAINT fk_bed_ward          FOREIGN KEY (ward_id) REFERENCES hospital.wards(id),
    CONSTRAINT uk_bed_ward_number   UNIQUE (ward_id, bed_number)
);

CREATE INDEX IF NOT EXISTS idx_bed_ward   ON hospital.beds(ward_id);
CREATE INDEX IF NOT EXISTS idx_bed_status ON hospital.beds(bed_status);

-- Add optional bed reference to admissions (preserving existing room_bed text field)
ALTER TABLE admissions ADD COLUMN IF NOT EXISTS bed_id UUID;
ALTER TABLE admissions ADD CONSTRAINT fk_admission_bed FOREIGN KEY (bed_id) REFERENCES hospital.beds(id);
CREATE INDEX IF NOT EXISTS idx_admission_bed ON admissions(bed_id);
