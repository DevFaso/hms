CREATE DOMAIN IF NOT EXISTS JSONB AS JSON;
DROP ALIAS IF EXISTS UUID_GENERATE_V4;
CREATE ALIAS UUID_GENERATE_V4 FOR "java.util.UUID.randomUUID";
DROP ALIAS IF EXISTS GEN_RANDOM_UUID;
CREATE ALIAS GEN_RANDOM_UUID FOR "java.util.UUID.randomUUID";
CREATE SCHEMA IF NOT EXISTS billing;
CREATE SCHEMA IF NOT EXISTS clinical;
CREATE SCHEMA IF NOT EXISTS empi;
CREATE SCHEMA IF NOT EXISTS governance;
CREATE SCHEMA IF NOT EXISTS hospital;
CREATE SCHEMA IF NOT EXISTS lab;
CREATE SCHEMA IF NOT EXISTS platform;
CREATE SCHEMA IF NOT EXISTS reference;
CREATE SCHEMA IF NOT EXISTS security;
CREATE SCHEMA IF NOT EXISTS support;

-- Clinical prescriptions domain (ensures Hibernate FK creation succeeds during local runs)
CREATE TABLE IF NOT EXISTS clinical.prescriptions (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	patient_id UUID NOT NULL,
	hospital_id UUID NOT NULL,
	staff_id UUID NOT NULL,
	encounter_id UUID NOT NULL,
	assignment_id UUID NOT NULL,
	medication_name VARCHAR(255) NOT NULL,
	medication_code VARCHAR(64),
	medication_display_name VARCHAR(255),
	dosage VARCHAR(100),
	dose_unit VARCHAR(40),
	route VARCHAR(80),
	frequency VARCHAR(100),
	duration VARCHAR(100),
	quantity NUMERIC(12, 2),
	quantity_unit VARCHAR(60),
	refills_allowed INTEGER,
	refills_remaining INTEGER,
	instructions VARCHAR(2048),
	patient_instruction_json JSONB,
	education_material_json JSONB,
	pharmacy_id UUID,
	pharmacy_name VARCHAR(255),
	pharmacy_npi VARCHAR(50),
	pharmacy_contact VARCHAR(120),
	pharmacy_address VARCHAR(255),
	dispatch_channel VARCHAR(40),
	dispatch_status VARCHAR(40),
	dispatch_reference VARCHAR(120),
	dispatched_at TIMESTAMP WITHOUT TIME ZONE,
	acknowledged_at TIMESTAMP WITHOUT TIME ZONE,
	controlled_substance BOOLEAN NOT NULL DEFAULT FALSE,
	inpatient_order BOOLEAN NOT NULL DEFAULT FALSE,
	allergies_reviewed BOOLEAN NOT NULL DEFAULT FALSE,
	interactions_reviewed BOOLEAN NOT NULL DEFAULT FALSE,
	contraindications_reviewed BOOLEAN NOT NULL DEFAULT FALSE,
	override_reason VARCHAR(1024),
	two_factor_method VARCHAR(40),
	two_factor_reference VARCHAR(120),
	two_factor_verified_at TIMESTAMP WITHOUT TIME ZONE,
	requires_cosign BOOLEAN NOT NULL DEFAULT FALSE,
	cosigned_by_staff_id UUID,
	cosigned_at TIMESTAMP WITHOUT TIME ZONE,
	notes VARCHAR(1024),
	status VARCHAR(40) NOT NULL,
	version BIGINT,
	createdAt TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updatedAt TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS clinical.prescription_instructions (
	prescription_id UUID NOT NULL,
	instruction_order INTEGER NOT NULL,
	label VARCHAR(120),
	instruction_text VARCHAR(1024),
	education_url VARCHAR(512),
	language_code VARCHAR(10),
	patient_acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
	acknowledged_at TIMESTAMP WITHOUT TIME ZONE
);

CREATE TABLE IF NOT EXISTS clinical.prescription_alerts (
	prescription_id UUID NOT NULL,
	alert_order INTEGER NOT NULL,
	alert_type VARCHAR(40),
	severity VARCHAR(20),
	message TEXT,
	reference_code VARCHAR(120),
	blocking BOOLEAN NOT NULL DEFAULT FALSE,
	acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
	acknowledged_at TIMESTAMP WITHOUT TIME ZONE
);

CREATE TABLE IF NOT EXISTS clinical.prescription_transmissions (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	prescription_id UUID NOT NULL,
	channel VARCHAR(40),
	destination VARCHAR(255),
	destination_reference VARCHAR(120),
	status VARCHAR(40),
	status_reason TEXT,
	attempt_count INTEGER DEFAULT 0,
	last_attempted_at TIMESTAMP WITHOUT TIME ZONE,
	payload JSONB,
	createdAt TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updatedAt TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- Clinical treatment planning tables required for coverage profile
CREATE TABLE IF NOT EXISTS clinical.treatment_plans (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	patient_id UUID NOT NULL,
	hospital_id UUID NOT NULL,
	encounter_id UUID,
	assignment_id UUID NOT NULL,
	author_staff_id UUID NOT NULL,
	supervising_staff_id UUID,
	sign_off_by UUID,
	status VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
	problem_statement TEXT NOT NULL,
	therapeutic_goals_json JSONB,
	medication_plan_json JSONB,
	lifestyle_plan_json JSONB,
	timeline_summary TEXT,
	follow_up_summary TEXT,
	referral_plan_json JSONB,
	responsible_parties_json JSONB,
	timeline_start_date DATE,
	timeline_review_date DATE,
	patient_visibility BOOLEAN NOT NULL DEFAULT FALSE,
	patient_visibility_at TIMESTAMP WITHOUT TIME ZONE,
	sign_off_at TIMESTAMP WITHOUT TIME ZONE,
	version BIGINT NOT NULL DEFAULT 0,
	createdAt TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updatedAt TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS clinical.treatment_plan_followups (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	treatment_plan_id UUID NOT NULL,
	label VARCHAR(255),
	instructions TEXT,
	due_on DATE,
	assigned_staff_id UUID,
	status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
	completed_at TIMESTAMP WITHOUT TIME ZONE,
	createdAt TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updatedAt TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS clinical.treatment_plan_reviews (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	treatment_plan_id UUID NOT NULL,
	reviewer_staff_id UUID NOT NULL,
	action VARCHAR(40) NOT NULL,
	comment TEXT,
	createdAt TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updatedAt TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- EMPI identity management tables (replicated for embedded H2 initialization)
CREATE TABLE IF NOT EXISTS empi.master_identities (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	empi_number VARCHAR(64) NOT NULL UNIQUE,
	patient_id UUID,
	organization_id UUID,
	hospital_id UUID,
	department_id UUID,
	status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
	resolution_state VARCHAR(30),
	source_system VARCHAR(100),
	active BOOLEAN NOT NULL DEFAULT TRUE,
	mrn_snapshot VARCHAR(100),
	metadata TEXT,
	created_by UUID,
	updated_by UUID,
	createdAt TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updatedAt TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS empi.identity_aliases (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	master_identity_id UUID NOT NULL,
	alias_type VARCHAR(50) NOT NULL,
	alias_value VARCHAR(255) NOT NULL,
	source_system VARCHAR(100),
	active BOOLEAN NOT NULL DEFAULT TRUE,
	created_by UUID,
	updated_by UUID,
	createdAt TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updatedAt TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT fk_empi_alias_master FOREIGN KEY (master_identity_id) REFERENCES empi.master_identities (id) ON DELETE CASCADE,
	CONSTRAINT uq_empi_alias_value UNIQUE (alias_type, alias_value)
);

CREATE TABLE IF NOT EXISTS empi.merge_events (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	primary_identity_id UUID NOT NULL,
	secondary_identity_id UUID NOT NULL,
	organization_id UUID,
	hospital_id UUID,
	department_id UUID,
	merge_type VARCHAR(30),
	resolution VARCHAR(50),
	notes TEXT,
	undo_token VARCHAR(100),
	merged_by UUID,
	merged_at TIMESTAMP WITH TIME ZONE,
	createdAt TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updatedAt TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT fk_empi_merge_primary FOREIGN KEY (primary_identity_id) REFERENCES empi.master_identities (id) ON DELETE CASCADE,
	CONSTRAINT fk_empi_merge_secondary FOREIGN KEY (secondary_identity_id) REFERENCES empi.master_identities (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_empi_identity_patient ON empi.master_identities (patient_id);
CREATE INDEX IF NOT EXISTS idx_empi_identity_org ON empi.master_identities (organization_id);
CREATE INDEX IF NOT EXISTS idx_empi_identity_hospital ON empi.master_identities (hospital_id);
CREATE INDEX IF NOT EXISTS idx_empi_identity_status ON empi.master_identities (status);

CREATE INDEX IF NOT EXISTS idx_empi_alias_master ON empi.identity_aliases (master_identity_id);
CREATE INDEX IF NOT EXISTS idx_empi_alias_type ON empi.identity_aliases (alias_type);

CREATE INDEX IF NOT EXISTS idx_empi_merge_primary ON empi.merge_events (primary_identity_id);
CREATE INDEX IF NOT EXISTS idx_empi_merge_secondary ON empi.merge_events (secondary_identity_id);
CREATE INDEX IF NOT EXISTS idx_empi_merge_org ON empi.merge_events (organization_id);
CREATE INDEX IF NOT EXISTS idx_empi_merge_hospital ON empi.merge_events (hospital_id);
