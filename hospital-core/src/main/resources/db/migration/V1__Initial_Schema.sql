-- ============================================================
-- V1__Initial_Schema.sql
-- Complete PostgreSQL schema for HMS (Hospital Management System)
-- Auto-generated from Hibernate entity model (110 entities, 153 tables)
-- All tables use IF NOT EXISTS for idempotency
-- ============================================================

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

CREATE TABLE IF NOT EXISTS billing.billing_invoices (
    amount_paid NUMERIC NOT NULL,
    due_date DATE NOT NULL,
    invoice_date DATE NOT NULL,
    total_amount NUMERIC NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_by UUID,
    encounter_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    updated_by UUID,
    invoice_number VARCHAR(50) NOT NULL,
    notes VARCHAR(2048),
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS billing.invoice_items (
    quantity INTEGER NOT NULL,
    total_price NUMERIC NOT NULL,
    unit_price NUMERIC NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    assignment_id UUID NOT NULL,
    billing_invoice_id UUID NOT NULL,
    id UUID NOT NULL,
    related_service_id UUID,
    item_description VARCHAR(255) NOT NULL,
    item_category VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.advance_directives (
    effective_date DATE,
    expiration_date DATE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    last_reviewed_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    source_system VARCHAR(100),
    description VARCHAR(1024),
    document_location VARCHAR(255),
    physician_name VARCHAR(255),
    witness_name VARCHAR(255),
    directive_type VARCHAR(60) NOT NULL,
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.appointments (
    appointment_date DATE NOT NULL,
    end_time TIME NOT NULL,
    start_time TIME NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    assignment_id UUID NOT NULL,
    created_by UUID,
    department_id UUID NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    staff_id UUID NOT NULL,
    notes VARCHAR(2048),
    reason VARCHAR(2048),
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.birth_plan_comfort_items (
    item_order INTEGER NOT NULL,
    birth_plan_id UUID NOT NULL,
    item VARCHAR(255),
    PRIMARY KEY (birth_plan_id, item_order)
);

CREATE TABLE IF NOT EXISTS clinical.birth_plan_medicated_options (
    option_order INTEGER NOT NULL,
    birth_plan_id UUID NOT NULL,
    option VARCHAR(255),
    PRIMARY KEY (birth_plan_id, option_order)
);

CREATE TABLE IF NOT EXISTS clinical.birth_plan_support_persons (
    person_order INTEGER NOT NULL,
    birth_plan_id UUID NOT NULL,
    person_name VARCHAR(255),
    PRIMARY KEY (birth_plan_id, person_order)
);

CREATE TABLE IF NOT EXISTS clinical.birth_plan_unmedicated_techniques (
    technique_order INTEGER NOT NULL,
    birth_plan_id UUID NOT NULL,
    technique VARCHAR(255),
    PRIMARY KEY (birth_plan_id, technique_order)
);

CREATE TABLE IF NOT EXISTS clinical.birth_plans (
    cord_clamping_duration INTEGER,
    delayed_cord_clamping BOOLEAN,
    discussed_with_provider BOOLEAN,
    expected_due_date DATE NOT NULL,
    flexibility_acknowledgment BOOLEAN NOT NULL,
    movement_during_labor BOOLEAN,
    provider_review_required BOOLEAN NOT NULL,
    provider_reviewed BOOLEAN NOT NULL,
    skin_to_skin_contact BOOLEAN,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    provider_signature_date TIMESTAMP WITHOUT TIME ZONE,
    review_timestamp TIMESTAMP WITHOUT TIME ZONE,
    reviewed_by_provider_id BIGINT,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    eye_ointment VARCHAR(20),
    hepatitis_b_vaccine VARCHAR(20),
    vitamin_k_shot VARCHAR(20),
    backup_delivery_method VARCHAR(50),
    feeding_method VARCHAR(50),
    fetal_monitoring_style VARCHAR(50),
    first_bath_timing VARCHAR(50),
    preferred_delivery_method VARCHAR(50),
    preferred_pain_approach VARCHAR(50),
    lighting_preference VARCHAR(100),
    who_cuts_cord VARCHAR(100),
    additional_wishes TEXT,
    delivery_method_notes TEXT,
    environment_notes TEXT,
    healthcare_provider VARCHAR(255),
    medical_conditions TEXT,
    music_preference VARCHAR(255),
    pain_management_notes TEXT,
    patient_name VARCHAR(255) NOT NULL,
    place_of_birth VARCHAR(255),
    postpartum_notes TEXT,
    provider_comments TEXT,
    provider_signature VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.consultations (
    follow_up_required BOOLEAN,
    is_curbside BOOLEAN,
    acknowledged_at TIMESTAMP WITHOUT TIME ZONE,
    cancelled_at TIMESTAMP WITHOUT TIME ZONE,
    completed_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    requested_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    scheduled_at TIMESTAMP WITHOUT TIME ZONE,
    sla_due_by TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    consultant_id UUID,
    encounter_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    requesting_provider_id UUID NOT NULL,
    specialty_requested VARCHAR(100) NOT NULL,
    cancellation_reason TEXT,
    clinical_question TEXT,
    consultant_note TEXT,
    current_medications TEXT,
    follow_up_instructions TEXT,
    reason_for_consult TEXT NOT NULL,
    recommendations TEXT,
    relevant_history TEXT,
    consultation_type VARCHAR(60) NOT NULL,
    status VARCHAR(60) NOT NULL,
    urgency VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.digital_signature_audit_log (
    log_order INTEGER NOT NULL,
    performed_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    performed_by_user_id UUID NOT NULL,
    signature_id UUID NOT NULL,
    ip_address VARCHAR(45),
    action VARCHAR(50) NOT NULL,
    device_info VARCHAR(500),
    details VARCHAR(1000),
    performed_by_display VARCHAR(255),
    PRIMARY KEY (log_order, signature_id)
);

CREATE TABLE IF NOT EXISTS clinical.digital_signatures (
    verification_count INTEGER NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITHOUT TIME ZONE,
    last_verified_at TIMESTAMP WITHOUT TIME ZONE,
    revoked_at TIMESTAMP WITHOUT TIME ZONE,
    signature_date_time TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version BIGINT,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    report_id UUID NOT NULL,
    revoked_by_user_id UUID,
    signed_by_staff_id UUID NOT NULL,
    ip_address VARCHAR(45),
    signature_hash VARCHAR(64) NOT NULL,
    device_info VARCHAR(500),
    revocation_reason VARCHAR(1000),
    signature_notes VARCHAR(2000),
    signature_value VARCHAR(2000) NOT NULL,
    metadata TEXT,
    revoked_by_display VARCHAR(255),
    report_type VARCHAR(60) NOT NULL,
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.discharge_approvals (
    approved_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    requested_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version BIGINT,
    doctor_assignment_id UUID,
    doctor_staff_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    nurse_assignment_id UUID NOT NULL,
    nurse_staff_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    registration_id UUID NOT NULL,
    doctor_note VARCHAR(4000),
    nurse_summary VARCHAR(4000),
    rejection_reason VARCHAR(4000),
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.discharge_equipment_supplies (
    equipment_order INTEGER NOT NULL,
    discharge_summary_id UUID NOT NULL,
    equipment_and_supplies VARCHAR(255),
    PRIMARY KEY (discharge_summary_id, equipment_order)
);

CREATE TABLE IF NOT EXISTS clinical.discharge_follow_up_appointments (
    appointment_date DATE,
    appointment_order INTEGER NOT NULL,
    is_confirmed BOOLEAN,
    appointment_time TIMESTAMP WITHOUT TIME ZONE,
    appointment_id UUID,
    discharge_summary_id UUID NOT NULL,
    phone_number VARCHAR(50),
    appointment_type VARCHAR(100) NOT NULL,
    confirmation_number VARCHAR(100),
    specialty VARCHAR(100),
    location VARCHAR(500),
    purpose VARCHAR(1000),
    special_instructions VARCHAR(1000),
    provider_name VARCHAR(255),
    PRIMARY KEY (appointment_order, discharge_summary_id)
);

CREATE TABLE IF NOT EXISTS clinical.discharge_medication_reconciliations (
    continue_at_discharge BOOLEAN,
    given_during_hospitalization BOOLEAN,
    was_on_admission BOOLEAN,
    discharge_summary_id UUID NOT NULL,
    prescription_id UUID,
    route VARCHAR(50),
    medication_code VARCHAR(64),
    dosage VARCHAR(100),
    frequency VARCHAR(100),
    patient_instructions VARCHAR(1000),
    prescriber_notes VARCHAR(1000),
    reason_for_change VARCHAR(1000),
    medication_name VARCHAR(255) NOT NULL,
    reconciliation_action VARCHAR(60) NOT NULL
);

CREATE TABLE IF NOT EXISTS clinical.discharge_pending_test_results (
    expected_result_date DATE,
    is_critical BOOLEAN,
    order_date DATE,
    patient_notified_of_pending BOOLEAN,
    discharge_summary_id UUID NOT NULL,
    imaging_order_id UUID,
    lab_order_id UUID,
    test_type VARCHAR(50) NOT NULL,
    test_code VARCHAR(64),
    notification_instructions VARCHAR(1000),
    follow_up_provider VARCHAR(255),
    ordering_provider VARCHAR(255),
    test_name VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS clinical.discharge_summaries (
    discharge_date DATE NOT NULL,
    is_finalized BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    discharge_time TIMESTAMP WITHOUT TIME ZONE,
    finalized_at TIMESTAMP WITHOUT TIME ZONE,
    provider_signature_date_time TIMESTAMP WITHOUT TIME ZONE,
    signature_date_time TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version BIGINT,
    approval_record_id UUID,
    assignment_id UUID NOT NULL,
    discharging_provider_id UUID NOT NULL,
    encounter_id UUID NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    provider_signature VARCHAR(500),
    additional_notes VARCHAR(2000),
    discharge_condition VARCHAR(2000),
    patient_education_provided VARCHAR(2000),
    warning_signs VARCHAR(2000),
    activity_restrictions VARCHAR(3000),
    diet_instructions VARCHAR(3000),
    follow_up_instructions VARCHAR(3000),
    wound_care_instructions VARCHAR(3000),
    discharge_diagnosis VARCHAR(5000) NOT NULL,
    hospital_course VARCHAR(5000),
    patient_or_caregiver_signature VARCHAR(255),
    disposition VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.drug_interactions (
    is_active BOOLEAN,
    monitoring_interval_hours INTEGER,
    requires_avoidance BOOLEAN,
    requires_dose_adjustment BOOLEAN,
    requires_monitoring BOOLEAN,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    drug1_code VARCHAR(100) NOT NULL,
    drug2_code VARCHAR(100) NOT NULL,
    evidence_level VARCHAR(100),
    source_database VARCHAR(100),
    clinical_effects VARCHAR(500),
    mechanism VARCHAR(500),
    monitoring_parameters VARCHAR(500),
    literature_references VARCHAR(1000),
    notes VARCHAR(1000),
    description TEXT,
    recommendation TEXT,
    drug1_name VARCHAR(255) NOT NULL,
    drug2_name VARCHAR(255) NOT NULL,
    external_reference_id VARCHAR(255),
    severity VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.education_resource_content_translations (
    resource_id UUID NOT NULL,
    language_code VARCHAR(255) NOT NULL,
    translated_content TEXT,
    PRIMARY KEY (language_code, resource_id)
);

CREATE TABLE IF NOT EXISTS clinical.education_resource_cultural_notes (
    resource_id UUID NOT NULL,
    note VARCHAR(1000)
);

CREATE TABLE IF NOT EXISTS clinical.education_resource_related (
    related_resource_id UUID,
    resource_id UUID NOT NULL
);

CREATE TABLE IF NOT EXISTS clinical.education_resource_tags (
    resource_id UUID NOT NULL,
    tag VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS clinical.education_resource_translations (
    resource_id UUID NOT NULL,
    translated_title VARCHAR(500),
    language_code VARCHAR(255) NOT NULL,
    PRIMARY KEY (language_code, resource_id)
);

CREATE TABLE IF NOT EXISTS clinical.education_resources (
    average_rating DOUBLE PRECISION NOT NULL,
    estimated_duration INTEGER,
    is_active BOOLEAN NOT NULL,
    is_culturally_sensitive BOOLEAN NOT NULL,
    is_evidence_based BOOLEAN NOT NULL,
    is_high_risk_relevant BOOLEAN NOT NULL,
    is_warning_sign_content BOOLEAN NOT NULL,
    completion_count BIGINT NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    published_at TIMESTAMP WITHOUT TIME ZONE,
    rating_count BIGINT NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    view_count BIGINT NOT NULL,
    primary_language VARCHAR(10) NOT NULL,
    hospital_id UUID,
    id UUID NOT NULL,
    organization_id UUID,
    created_by VARCHAR(100) NOT NULL,
    last_modified_by VARCHAR(100),
    thumbnail_url VARCHAR(500),
    title VARCHAR(500) NOT NULL,
    video_url VARCHAR(500),
    content_url VARCHAR(1000),
    evidence_source VARCHAR(1000),
    description VARCHAR(2000),
    text_content TEXT,
    category VARCHAR(60) NOT NULL,
    resource_type VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.encounter_history (
    changed_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    encounter_date TIMESTAMP WITHOUT TIME ZONE,
    encounter_id UUID NOT NULL,
    id UUID NOT NULL,
    change_type VARCHAR(30),
    changed_by VARCHAR(100),
    notes VARCHAR(2048),
    extra_fields TEXT,
    previous_values TEXT,
    encounter_type VARCHAR(60),
    status VARCHAR(60),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.encounter_note_addenda (
    attest_accuracy BOOLEAN NOT NULL,
    attest_no_abbreviations BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    documented_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    event_occurred_at TIMESTAMP WITHOUT TIME ZONE,
    signed_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    author_staff_id UUID,
    author_user_id UUID NOT NULL,
    id UUID NOT NULL,
    note_id UUID NOT NULL,
    author_credentials VARCHAR(200),
    author_name VARCHAR(200),
    content TEXT NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.encounter_note_history (
    changed_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    encounter_id UUID NOT NULL,
    id UUID NOT NULL,
    note_id UUID,
    change_type VARCHAR(30),
    changed_by VARCHAR(120),
    content_snapshot TEXT,
    metadata_snapshot TEXT,
    template VARCHAR(60),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.encounter_note_links (
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    linked_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    artifact_id UUID NOT NULL,
    id UUID NOT NULL,
    note_id UUID NOT NULL,
    artifact_code VARCHAR(120),
    artifact_status VARCHAR(120),
    artifact_display VARCHAR(255),
    artifact_type VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.encounter_notes (
    attest_accuracy BOOLEAN NOT NULL,
    attest_no_abbreviations BOOLEAN NOT NULL,
    attest_spell_check BOOLEAN NOT NULL,
    late_entry BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    documented_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    event_occurred_at TIMESTAMP WITHOUT TIME ZONE,
    signed_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    author_staff_id UUID,
    author_user_id UUID NOT NULL,
    encounter_id UUID NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    author_credentials VARCHAR(200),
    author_name VARCHAR(200),
    signed_by_credentials VARCHAR(200),
    signed_by_name VARCHAR(200),
    chief_complaint TEXT,
    data_assessment TEXT,
    data_evaluation TEXT,
    data_implementation TEXT,
    data_objective TEXT,
    data_plan TEXT,
    data_subjective TEXT,
    diagnostic_results TEXT,
    history_present_illness TEXT,
    patient_instructions TEXT,
    physical_exam TEXT,
    review_of_systems TEXT,
    summary TEXT,
    template VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.encounter_treatments (
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    performed_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    encounter_id UUID NOT NULL,
    id UUID NOT NULL,
    staff_id UUID,
    treatment_id UUID NOT NULL,
    outcome VARCHAR(500),
    notes VARCHAR(1000),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.encounters (
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    encounter_date TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version BIGINT,
    appointment_id UUID,
    assignment_id UUID NOT NULL,
    department_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    staff_id UUID NOT NULL,
    code VARCHAR(50) NOT NULL,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    notes VARCHAR(2048),
    extra_fields TEXT,
    encounter_type VARCHAR(60) NOT NULL,
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.high_risk_plan_bp_logs (
    diastolic INTEGER NOT NULL,
    heart_rate INTEGER,
    list_order INTEGER NOT NULL,
    reading_date DATE NOT NULL,
    systolic INTEGER NOT NULL,
    log_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    notes VARCHAR(500),
    PRIMARY KEY (list_order, plan_id)
);

CREATE TABLE IF NOT EXISTS clinical.high_risk_plan_care_team_members (
    list_order INTEGER NOT NULL,
    plan_id UUID NOT NULL,
    contact VARCHAR(120),
    member_role VARCHAR(120),
    member_name VARCHAR(150),
    coverage_notes VARCHAR(300),
    PRIMARY KEY (list_order, plan_id)
);

CREATE TABLE IF NOT EXISTS clinical.high_risk_plan_care_team_notes (
    list_order INTEGER NOT NULL,
    logged_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    note_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    author VARCHAR(120),
    follow_up VARCHAR(500),
    summary VARCHAR(500),
    PRIMARY KEY (list_order, plan_id)
);

CREATE TABLE IF NOT EXISTS clinical.high_risk_plan_education_topics (
    list_order INTEGER NOT NULL,
    plan_id UUID NOT NULL,
    topic VARCHAR(150),
    guidance VARCHAR(500),
    materials VARCHAR(500),
    PRIMARY KEY (list_order, plan_id)
);

CREATE TABLE IF NOT EXISTS clinical.high_risk_plan_emergency_symptoms (
    list_order INTEGER NOT NULL,
    plan_id UUID NOT NULL,
    symptom VARCHAR(240) NOT NULL,
    PRIMARY KEY (list_order, plan_id)
);

CREATE TABLE IF NOT EXISTS clinical.high_risk_plan_lifestyle_factors (
    list_order INTEGER NOT NULL,
    plan_id UUID NOT NULL,
    factor VARCHAR(150) NOT NULL,
    PRIMARY KEY (list_order, plan_id)
);

CREATE TABLE IF NOT EXISTS clinical.high_risk_plan_medication_logs (
    list_order INTEGER NOT NULL,
    taken BOOLEAN NOT NULL,
    taken_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    med_log_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    dosage VARCHAR(60),
    medication_name VARCHAR(120) NOT NULL,
    notes VARCHAR(500),
    PRIMARY KEY (list_order, plan_id)
);

CREATE TABLE IF NOT EXISTS clinical.high_risk_plan_milestones (
    completed BOOLEAN NOT NULL,
    completed_at DATE,
    list_order INTEGER NOT NULL,
    target_date DATE,
    milestone_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    assigned_to VARCHAR(120),
    location VARCHAR(240),
    follow_up_actions VARCHAR(500),
    summary VARCHAR(500),
    milestone_type VARCHAR(60) NOT NULL,
    PRIMARY KEY (list_order, plan_id)
);

CREATE TABLE IF NOT EXISTS clinical.high_risk_plan_preexisting_conditions (
    list_order INTEGER NOT NULL,
    plan_id UUID NOT NULL,
    condition VARCHAR(150) NOT NULL,
    PRIMARY KEY (list_order, plan_id)
);

CREATE TABLE IF NOT EXISTS clinical.high_risk_plan_pregnancy_conditions (
    list_order INTEGER NOT NULL,
    plan_id UUID NOT NULL,
    condition VARCHAR(150) NOT NULL,
    PRIMARY KEY (list_order, plan_id)
);

CREATE TABLE IF NOT EXISTS clinical.high_risk_plan_preventive_guidance (
    list_order INTEGER NOT NULL,
    plan_id UUID NOT NULL,
    guidance VARCHAR(240) NOT NULL,
    PRIMARY KEY (list_order, plan_id)
);

CREATE TABLE IF NOT EXISTS clinical.high_risk_plan_support_resources (
    list_order INTEGER NOT NULL,
    plan_id UUID NOT NULL,
    resource_type VARCHAR(120),
    resource_name VARCHAR(150),
    contact_details VARCHAR(240),
    resource_url VARCHAR(240),
    resource_notes VARCHAR(300),
    PRIMARY KEY (list_order, plan_id)
);

CREATE TABLE IF NOT EXISTS clinical.high_risk_pregnancy_plans (
    active BOOLEAN NOT NULL,
    last_specialist_review DATE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    risk_level VARCHAR(60),
    visit_cadence VARCHAR(150),
    coordination_notes VARCHAR(500),
    delivery_recommendations TEXT,
    escalation_plan TEXT,
    home_monitoring_instructions TEXT,
    medication_plan TEXT,
    overall_notes TEXT,
    risk_notes TEXT,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.imaging_orders (
    attestation_confirmed BOOLEAN,
    contrast_required BOOLEAN,
    duplicate_of_recent_order BOOLEAN,
    has_contrast_allergy BOOLEAN,
    has_implanted_device BOOLEAN,
    needs_interpreter BOOLEAN,
    portable_study BOOLEAN,
    requires_authorization BOOLEAN,
    requires_follow_up_call BOOLEAN,
    requires_npo BOOLEAN,
    requires_pregnancy_test BOOLEAN,
    scheduled_date DATE,
    sedation_required BOOLEAN,
    cancelled_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    ordered_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    provider_signed_at TIMESTAMP WITHOUT TIME ZONE,
    status_updated_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    cancelled_by_user_id UUID,
    duplicate_reference_order_id UUID,
    encounter_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    ordering_provider_user_id UUID,
    patient_id UUID NOT NULL,
    status_updated_by UUID,
    ordering_provider_npi VARCHAR(40),
    scheduled_time VARCHAR(50),
    authorization_number VARCHAR(120),
    contrast_type VARCHAR(120),
    sedation_type VARCHAR(120),
    body_region VARCHAR(150),
    cancelled_by_name VARCHAR(150),
    study_type VARCHAR(150) NOT NULL,
    ordering_provider_name VARCHAR(200),
    cancellation_reason VARCHAR(500),
    contrast_allergy_details VARCHAR(500),
    implanted_device_details VARCHAR(500),
    sedation_notes VARCHAR(500),
    additional_protocols VARCHAR(1000),
    provider_signature_statement VARCHAR(1000),
    special_instructions VARCHAR(1000),
    workflow_notes VARCHAR(1000),
    clinical_question VARCHAR(2000),
    appointment_location VARCHAR(255),
    laterality VARCHAR(60),
    modality VARCHAR(60) NOT NULL,
    priority VARCHAR(60) NOT NULL,
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.imaging_report_attachments (
    position INTEGER NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    size_bytes BIGINT,
    report_id UUID NOT NULL,
    dicom_object_uid VARCHAR(64),
    category VARCHAR(80),
    content_type VARCHAR(120),
    storage_bucket VARCHAR(120),
    viewer_url VARCHAR(500),
    file_name VARCHAR(255),
    label VARCHAR(255),
    storage_key VARCHAR(255) NOT NULL,
    thumbnail_key VARCHAR(255),
    PRIMARY KEY (position, report_id)
);

CREATE TABLE IF NOT EXISTS clinical.imaging_report_measurements (
    is_abnormal BOOLEAN,
    numeric_value NUMERIC,
    reference_max NUMERIC,
    reference_min NUMERIC,
    sequence_number INTEGER,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    report_id UUID NOT NULL,
    unit VARCHAR(50),
    plane VARCHAR(80),
    modifier VARCHAR(120),
    region VARCHAR(120),
    label VARCHAR(200),
    text_value VARCHAR(500),
    notes VARCHAR(1000),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.imaging_report_status_history (
    changed_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    changed_by UUID,
    id UUID NOT NULL,
    imaging_order_id UUID NOT NULL,
    imaging_report_id UUID NOT NULL,
    client_source VARCHAR(120),
    changed_by_name VARCHAR(200),
    status_reason VARCHAR(500),
    notes VARCHAR(1000),
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.imaging_reports (
    attachments_count INTEGER NOT NULL,
    contrast_administered BOOLEAN,
    is_latest_version BOOLEAN NOT NULL,
    is_locked_for_editing BOOLEAN,
    measurements_count INTEGER NOT NULL,
    radiation_dose_mgy NUMERIC,
    report_version INTEGER NOT NULL,
    completed_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    critical_result_acknowledged_at TIMESTAMP WITHOUT TIME ZONE,
    critical_result_flagged_at TIMESTAMP WITHOUT TIME ZONE,
    interpreted_at TIMESTAMP WITHOUT TIME ZONE,
    last_status_synced_at TIMESTAMP WITHOUT TIME ZONE,
    patient_notified_at TIMESTAMP WITHOUT TIME ZONE,
    performed_at TIMESTAMP WITHOUT TIME ZONE,
    signed_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_by UUID,
    critical_result_ack_by_staff_id UUID,
    department_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    imaging_order_id UUID NOT NULL,
    interpreting_provider_id UUID,
    organization_id UUID,
    performed_by_staff_id UUID,
    signed_by_staff_id UUID,
    updated_by UUID,
    series_instance_uid VARCHAR(64),
    study_instance_uid VARCHAR(64),
    accession_number VARCHAR(80),
    report_number VARCHAR(80),
    external_report_id VARCHAR(120),
    body_region VARCHAR(150),
    external_system_name VARCHAR(150),
    pacs_viewer_url VARCHAR(500),
    contrast_details VARCHAR(1000),
    comparison_studies TEXT,
    findings TEXT,
    impression TEXT,
    lock_reason VARCHAR(255),
    recommendations TEXT,
    report_title VARCHAR(255),
    technique TEXT,
    modality VARCHAR(60),
    report_status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.maternal_history (
    abortions INTEGER,
    adequate_housing BOOLEAN,
    anxiety_present BOOLEAN,
    autoimmune_disorder BOOLEAN,
    caffeine_intake_daily INTEGER,
    calculated_risk_score INTEGER,
    cardiac_disease BOOLEAN,
    cigarettes_per_day INTEGER,
    covid19_vaccination BOOLEAN,
    data_complete BOOLEAN,
    depression_screening_score INTEGER,
    diabetes BOOLEAN,
    domestic_violence_concerns BOOLEAN,
    domestic_violence_screening BOOLEAN,
    eclampsia_history BOOLEAN,
    estimated_due_date DATE,
    estimated_due_date_by_ultrasound DATE,
    family_diabetes BOOLEAN,
    family_genetic_disorders BOOLEAN,
    family_hypertension BOOLEAN,
    family_pregnancy_complications BOOLEAN,
    family_twin_history BOOLEAN,
    fetal_anomaly_history BOOLEAN,
    financial_concerns BOOLEAN,
    flu_vaccination_current_season BOOLEAN,
    flu_vaccination_date DATE,
    folic_acid_supplementation BOOLEAN,
    food_security BOOLEAN,
    gestational_diabetes_history BOOLEAN,
    gravida INTEGER,
    hellp_syndrome_history BOOLEAN,
    hepatitis_b_vaccination BOOLEAN,
    hypertension BOOLEAN,
    last_menstrual_period DATE,
    latex_allergy BOOLEAN,
    living_children INTEGER,
    menstrual_cycle_length_days INTEGER,
    mental_health_screening_completed BOOLEAN,
    occupational_hazards BOOLEAN,
    para INTEGER,
    placenta_previa_history BOOLEAN,
    placental_abruption_history BOOLEAN,
    postpartum_hemorrhage_history BOOLEAN,
    preeclampsia_history BOOLEAN,
    prenatal_vitamins_start_date DATE,
    prenatal_vitamins_started BOOLEAN,
    preterm_births INTEGER,
    preterm_labor_history BOOLEAN,
    previous_abdominal_surgery BOOLEAN,
    previous_cesarean_sections INTEGER,
    previous_uterine_surgery BOOLEAN,
    recreational_drug_use BOOLEAN,
    renal_disease BOOLEAN,
    requires_specialist_referral BOOLEAN,
    reviewed_by_provider BOOLEAN,
    smoking_cessation_date DATE,
    tdap_vaccination BOOLEAN,
    tdap_vaccination_date DATE,
    term_births INTEGER,
    thyroid_disorder BOOLEAN,
    ultrasound_confirmation_date DATE,
    version_number INTEGER NOT NULL,
    zika_risk_exposure BOOLEAN,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    recorded_date TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    review_timestamp TIMESTAMP WITHOUT TIME ZONE,
    reviewed_by_staff_id BIGINT,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    recorded_by_staff_id UUID,
    alcohol_use VARCHAR(50),
    exercise_frequency VARCHAR(50),
    menstrual_cycle_regularity VARCHAR(50),
    risk_category VARCHAR(50),
    smoking_status VARCHAR(50),
    diet_type VARCHAR(100),
    alcohol_use_details VARCHAR(500),
    exercise_details VARCHAR(500),
    update_reason VARCHAR(500),
    additional_notes TEXT,
    allergies TEXT,
    chronic_conditions TEXT,
    clinical_notes TEXT,
    complications_details TEXT,
    contraception_method_prior VARCHAR(255),
    current_medications TEXT,
    diet_description TEXT,
    domestic_violence_details TEXT,
    drug_allergies TEXT,
    environmental_exposures TEXT,
    family_history_details TEXT,
    family_medical_history TEXT,
    identified_risk_factors TEXT,
    immunization_notes TEXT,
    mental_health_conditions TEXT,
    occupational_hazards_details TEXT,
    pet_exposure VARCHAR(255),
    previous_pregnancy_complications TEXT,
    previous_pregnancy_outcomes TEXT,
    psychosocial_notes TEXT,
    rubella_immunity VARCHAR(255),
    specialist_referral_reason TEXT,
    substance_use TEXT,
    substance_use_details TEXT,
    support_system TEXT,
    surgical_history TEXT,
    travel_history TEXT,
    varicella_immunity VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.newborn_assessment_alerts (
    alert_order INTEGER NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    assessment_id UUID NOT NULL,
    alert_code VARCHAR(64),
    triggered_by VARCHAR(120),
    alert_message TEXT NOT NULL,
    alert_severity VARCHAR(60) NOT NULL,
    alert_type VARCHAR(60) NOT NULL,
    PRIMARY KEY (alert_order, assessment_id)
);

CREATE TABLE IF NOT EXISTS clinical.newborn_assessment_education_topics (
    assessment_id UUID NOT NULL,
    topic VARCHAR(60) NOT NULL,
    PRIMARY KEY (assessment_id, topic)
);

CREATE TABLE IF NOT EXISTS clinical.newborn_assessment_follow_up_actions (
    assessment_id UUID NOT NULL,
    action VARCHAR(60) NOT NULL,
    PRIMARY KEY (action, assessment_id)
);

CREATE TABLE IF NOT EXISTS clinical.newborn_assessments (
    apgar_five_minute INTEGER,
    apgar_one_minute INTEGER,
    apgar_ten_minute INTEGER,
    diastolic_bp_mm_hg INTEGER,
    escalation_recommended BOOLEAN NOT NULL,
    glucose_mg_dl INTEGER,
    glucose_protocol_initiated BOOLEAN NOT NULL,
    heart_rate_bpm INTEGER,
    late_entry BOOLEAN NOT NULL,
    oxygen_saturation_percent INTEGER,
    parent_education_completed BOOLEAN NOT NULL,
    respirations_per_min INTEGER,
    respiratory_support_initiated BOOLEAN NOT NULL,
    systolic_bp_mm_hg INTEGER,
    temperature_celsius DOUBLE PRECISION,
    thermoregulation_support_initiated BOOLEAN NOT NULL,
    assessment_time TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    documented_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    original_entry_time TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    documented_by_user_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    recorded_by_staff_id UUID,
    registration_id UUID,
    apgar_notes TEXT,
    exam_abdomen TEXT,
    exam_cardiac TEXT,
    exam_chest_lungs TEXT,
    exam_general_appearance TEXT,
    exam_genitourinary TEXT,
    exam_head_neck TEXT,
    exam_musculoskeletal TEXT,
    exam_neurological TEXT,
    exam_notes TEXT,
    exam_skin TEXT,
    follow_up_notes TEXT,
    parent_education_notes TEXT,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.nursing_note_addenda (
    attest_accuracy BOOLEAN NOT NULL,
    attest_no_abbreviations BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    documented_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    event_occurred_at TIMESTAMP WITHOUT TIME ZONE,
    signed_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    author_staff_id UUID,
    author_user_id UUID NOT NULL,
    id UUID NOT NULL,
    note_id UUID NOT NULL,
    author_credentials VARCHAR(200),
    author_name VARCHAR(200),
    content TEXT NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.nursing_note_education_entries (
    entry_order INTEGER NOT NULL,
    note_id UUID NOT NULL,
    teaching_method VARCHAR(120),
    topic VARCHAR(150),
    patient_understanding VARCHAR(400),
    reinforcement_actions VARCHAR(400),
    education_summary TEXT,
    PRIMARY KEY (entry_order, note_id)
);

CREATE TABLE IF NOT EXISTS clinical.nursing_note_interventions (
    entry_order INTEGER NOT NULL,
    linked_medication_task_id UUID,
    linked_order_id UUID,
    note_id UUID NOT NULL,
    description VARCHAR(400),
    follow_up_actions VARCHAR(400),
    PRIMARY KEY (entry_order, note_id)
);

CREATE TABLE IF NOT EXISTS clinical.nursing_notes (
    attest_accuracy BOOLEAN NOT NULL,
    attest_no_abbreviations BOOLEAN NOT NULL,
    attest_spell_check BOOLEAN NOT NULL,
    late_entry BOOLEAN NOT NULL,
    readability_score DOUBLE PRECISION,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    documented_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    event_occurred_at TIMESTAMP WITHOUT TIME ZONE,
    signed_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    author_staff_id UUID,
    author_user_id UUID NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    author_credentials VARCHAR(200),
    author_name VARCHAR(200),
    signed_by_credentials VARCHAR(200),
    signed_by_name VARCHAR(200),
    action_summary TEXT,
    data_assessment TEXT,
    data_evaluation TEXT,
    data_implementation TEXT,
    data_objective TEXT,
    data_plan TEXT,
    data_subjective TEXT,
    education_summary TEXT,
    narrative TEXT,
    response_summary TEXT,
    template VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.obgyn_referral_attachments (
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    size_bytes BIGINT,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    uploaded_at TIMESTAMP WITHOUT TIME ZONE,
    id UUID NOT NULL,
    referral_id UUID NOT NULL,
    uploaded_by UUID NOT NULL,
    content_type VARCHAR(128),
    storage_key VARCHAR(512) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    category VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.obgyn_referral_messages (
    read BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    referral_id UUID NOT NULL,
    sender_user_id UUID NOT NULL,
    attachments TEXT,
    body TEXT NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.obgyn_referrals (
    attachments_present BOOLEAN NOT NULL,
    gestational_age_weeks INTEGER,
    ongoing_midwifery_care BOOLEAN NOT NULL,
    acknowledgement_timestamp TIMESTAMP WITHOUT TIME ZONE,
    cancelled_timestamp TIMESTAMP WITHOUT TIME ZONE,
    care_team_updated_at TIMESTAMP WITHOUT TIME ZONE,
    completion_timestamp TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    letter_generated_at TIMESTAMP WITHOUT TIME ZONE,
    sla_due_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    encounter_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    midwife_user_id UUID NOT NULL,
    obgyn_user_id UUID,
    patient_id UUID NOT NULL,
    created_by VARCHAR(120),
    updated_by VARCHAR(120),
    letter_storage_path VARCHAR(512),
    cancellation_reason TEXT,
    care_context VARCHAR(60) NOT NULL,
    clinical_indication TEXT,
    history_summary TEXT,
    midwife_contact_snapshot TEXT,
    patient_contact_snapshot TEXT,
    plan_summary TEXT,
    referral_reason TEXT NOT NULL,
    status VARCHAR(60) NOT NULL,
    transfer_type VARCHAR(60) NOT NULL,
    urgency VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.patient_allergies (
    active BOOLEAN NOT NULL,
    last_occurrence_date DATE,
    onset_date DATE,
    recorded_date DATE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    recorded_by_staff_id UUID,
    allergen_code VARCHAR(64),
    category VARCHAR(100),
    source_system VARCHAR(100),
    reaction_notes VARCHAR(1024),
    allergen_display VARCHAR(255) NOT NULL,
    reaction VARCHAR(255),
    severity VARCHAR(60),
    verification_status VARCHAR(60),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.patient_chart_attachments (
    position INTEGER NOT NULL,
    size_bytes BIGINT,
    chart_update_id UUID NOT NULL,
    category VARCHAR(60),
    sha256 VARCHAR(64),
    content_type VARCHAR(120),
    file_name VARCHAR(255),
    label VARCHAR(255),
    storage_key VARCHAR(255) NOT NULL,
    PRIMARY KEY (chart_update_id, position)
);

CREATE TABLE IF NOT EXISTS clinical.patient_chart_section_entries (
    occurred_on DATE,
    position INTEGER NOT NULL,
    sensitive_flag BOOLEAN,
    chart_update_id UUID NOT NULL,
    linked_resource_id UUID,
    severity VARCHAR(50),
    status VARCHAR(50),
    code VARCHAR(64),
    source_system VARCHAR(120),
    author_notes VARCHAR(2048),
    narrative VARCHAR(2048),
    details_json TEXT,
    display VARCHAR(255),
    section_type VARCHAR(60) NOT NULL,
    PRIMARY KEY (chart_update_id, position)
);

CREATE TABLE IF NOT EXISTS clinical.patient_chart_updates (
    attachment_count INTEGER,
    include_sensitive BOOLEAN NOT NULL,
    notify_care_team BOOLEAN NOT NULL,
    section_count INTEGER,
    version_number INTEGER NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    notification_sent_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    assignment_id UUID NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    recorded_by_staff_id UUID NOT NULL,
    update_reason VARCHAR(512) NOT NULL,
    summary VARCHAR(1024),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.patient_consents (
    consent_given BOOLEAN NOT NULL,
    consent_expiration TIMESTAMP WITHOUT TIME ZONE,
    consent_timestamp TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    from_hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    to_hospital_id UUID NOT NULL,
    purpose VARCHAR(1024),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.patient_education_progress (
    access_count INTEGER NOT NULL,
    confirmed_understanding BOOLEAN NOT NULL,
    needs_clarification BOOLEAN NOT NULL,
    progress_percentage INTEGER NOT NULL,
    rating INTEGER,
    completed_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    discussed_with_provider_at TIMESTAMP WITHOUT TIME ZONE,
    last_accessed_at TIMESTAMP WITHOUT TIME ZONE,
    started_at TIMESTAMP WITHOUT TIME ZONE,
    time_spent_seconds BIGINT NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    provider_id UUID,
    resource_id UUID NOT NULL,
    clarification_request VARCHAR(1000),
    provider_notes VARCHAR(1000),
    feedback VARCHAR(2000),
    comprehension_status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.patient_education_questions (
    appointment_scheduled BOOLEAN NOT NULL,
    is_answered BOOLEAN NOT NULL,
    is_urgent BOOLEAN NOT NULL,
    requires_in_person_discussion BOOLEAN NOT NULL,
    answered_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    answered_by_staff_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    related_appointment_id UUID,
    resource_id UUID,
    category VARCHAR(100),
    provider_notes VARCHAR(1000),
    question VARCHAR(1000) NOT NULL,
    answer TEXT,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.patient_family_history (
    active BOOLEAN,
    age_at_onset INTEGER,
    clinically_significant BOOLEAN,
    generation INTEGER,
    genetic_condition BOOLEAN,
    genetic_testing_done BOOLEAN,
    is_autoimmune BOOLEAN,
    is_cancer BOOLEAN,
    is_cardiovascular BOOLEAN,
    is_diabetes BOOLEAN,
    is_mental_health BOOLEAN,
    is_neurological BOOLEAN,
    recommended_age_for_screening INTEGER,
    recorded_date DATE NOT NULL,
    relative_age INTEGER,
    relative_age_at_death INTEGER,
    relative_living BOOLEAN,
    risk_factor_for_patient BOOLEAN,
    screening_recommended BOOLEAN,
    verification_date DATE,
    verified BOOLEAN,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    recorded_by_staff_id UUID,
    relative_gender VARCHAR(20),
    condition_code VARCHAR(50),
    relationship_side VARCHAR(50),
    severity VARCHAR(50),
    condition_category VARCHAR(100),
    inheritance_pattern VARCHAR(100),
    outcome VARCHAR(100),
    pedigree_id VARCHAR(100),
    relationship VARCHAR(100) NOT NULL,
    relative_name VARCHAR(200),
    cause_of_death VARCHAR(500),
    notes VARCHAR(2048),
    condition_display VARCHAR(255) NOT NULL,
    genetic_marker VARCHAR(255),
    screening_type VARCHAR(255),
    source_of_information VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.patient_hospital_registrations (
    is_active BOOLEAN NOT NULL,
    registration_date DATE NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    stay_status_updated_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    ready_by_staff_id UUID,
    current_bed VARCHAR(30),
    current_room VARCHAR(30),
    mrn VARCHAR(50) NOT NULL,
    attending_physician_name VARCHAR(150),
    ready_for_discharge_note VARCHAR(1000),
    patient_name VARCHAR(255),
    stay_status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.patient_immunization (
    active BOOLEAN,
    administration_date DATE NOT NULL,
    adverse_reaction BOOLEAN,
    consent_date DATE,
    consent_obtained BOOLEAN,
    contraindication BOOLEAN,
    dose_number INTEGER,
    dose_quantity DOUBLE PRECISION,
    expiration_date DATE,
    insurance_reported BOOLEAN,
    next_dose_due_date DATE,
    occupational_requirement BOOLEAN,
    overdue BOOLEAN,
    pregnancy_related BOOLEAN,
    registry_reported BOOLEAN,
    registry_reported_date DATE,
    reminder_sent BOOLEAN,
    reminder_sent_date DATE,
    required_for_school BOOLEAN,
    required_for_travel BOOLEAN,
    total_doses_in_series INTEGER,
    verified BOOLEAN,
    vis_date DATE,
    vis_given BOOLEAN,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    administered_by_staff_id UUID,
    encounter_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    ndc_code VARCHAR(20),
    dose_unit VARCHAR(50),
    reaction_severity VARCHAR(50),
    route VARCHAR(50),
    status VARCHAR(50) NOT NULL,
    vaccine_code VARCHAR(50),
    external_reference_id VARCHAR(100),
    lot_number VARCHAR(100),
    site VARCHAR(100),
    source_of_record VARCHAR(100),
    vaccine_type VARCHAR(100),
    manufacturer VARCHAR(200),
    contraindication_reason VARCHAR(500),
    status_reason VARCHAR(500),
    reaction_description VARCHAR(1000),
    notes VARCHAR(2048),
    target_disease VARCHAR(255),
    vaccine_display VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.patient_insurances (
    effective_date DATE,
    expiration_date DATE,
    is_primary BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    linked_as VARCHAR(10),
    assignment_id UUID,
    id UUID NOT NULL,
    linked_by_user_id UUID,
    patient_id UUID,
    payer_code VARCHAR(50),
    subscriber_relationship VARCHAR(50),
    group_number VARCHAR(100),
    policy_number VARCHAR(100) NOT NULL,
    provider_name VARCHAR(150) NOT NULL,
    subscriber_name VARCHAR(150),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.patient_primary_care (
    end_date DATE,
    is_current BOOLEAN NOT NULL,
    start_date DATE NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    assignment_id UUID NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    notes VARCHAR(512),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.patient_problem_history (
    changed_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    changed_by_user_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    problem_id UUID NOT NULL,
    changed_by_name VARCHAR(200),
    reason VARCHAR(2000),
    snapshot_after TEXT,
    snapshot_before TEXT,
    change_type VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.patient_problems (
    is_chronic BOOLEAN NOT NULL,
    onset_date DATE,
    resolved_date DATE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    last_reviewed_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    recorded_by_staff_id UUID,
    icd_version VARCHAR(20),
    problem_code VARCHAR(50),
    source_system VARCHAR(100),
    status_change_reason VARCHAR(500),
    notes VARCHAR(2048),
    supporting_evidence VARCHAR(4096),
    diagnosis_codes TEXT,
    problem_display VARCHAR(255) NOT NULL,
    severity VARCHAR(60),
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.patient_social_history (
    abuse_history BOOLEAN,
    active BOOLEAN,
    alcohol_binge_drinking BOOLEAN,
    alcohol_drinks_per_week INTEGER,
    alcohol_use BOOLEAN,
    domestic_violence_screening BOOLEAN,
    exercise_minutes_per_week INTEGER,
    feels_safe_at_home BOOLEAN,
    financial_barriers BOOLEAN,
    has_primary_caregiver BOOLEAN,
    health_literacy_concerns BOOLEAN,
    household_members INTEGER,
    housing_stability BOOLEAN,
    interpreter_needed BOOLEAN,
    intravenous_drug_use BOOLEAN,
    mental_health_support BOOLEAN,
    number_of_partners INTEGER,
    recorded_date DATE NOT NULL,
    recreational_drug_use BOOLEAN,
    sexually_active BOOLEAN,
    social_isolation_risk BOOLEAN,
    sti_history BOOLEAN,
    substance_abuse_treatment BOOLEAN,
    tobacco_packs_per_day DOUBLE PRECISION,
    tobacco_quit_date DATE,
    tobacco_use BOOLEAN,
    tobacco_years_used INTEGER,
    transportation_access BOOLEAN,
    version_number INTEGER,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    recorded_by_staff_id UUID,
    employment_status VARCHAR(50),
    marital_status VARCHAR(50),
    preferred_language VARCHAR(50),
    stress_level VARCHAR(50),
    alcohol_frequency VARCHAR(100),
    diet_type VARCHAR(100),
    education_level VARCHAR(100),
    exercise_frequency VARCHAR(100),
    insurance_status VARCHAR(100),
    living_arrangement VARCHAR(100),
    tobacco_type VARCHAR(100),
    occupation VARCHAR(200),
    diet_restrictions VARCHAR(500),
    drug_types_used VARCHAR(500),
    social_support_network VARCHAR(500),
    alcohol_notes VARCHAR(1000),
    coping_mechanisms VARCHAR(1000),
    nutritional_concerns VARCHAR(1000),
    occupational_hazards VARCHAR(1000),
    safety_concerns VARCHAR(1000),
    sexual_health_notes VARCHAR(1000),
    stress_sources VARCHAR(1000),
    substance_notes VARCHAR(1000),
    tobacco_notes VARCHAR(1000),
    additional_notes VARCHAR(2048),
    contraception_use VARCHAR(255),
    exercise_type VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.patient_surgical_history (
    procedure_date DATE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    last_updated_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    performed_by_staff_id UUID,
    procedure_code VARCHAR(50),
    source_system VARCHAR(100),
    location VARCHAR(150),
    notes VARCHAR(2048),
    procedure_display VARCHAR(255) NOT NULL,
    outcome VARCHAR(60),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.patient_vital_signs (
    blood_glucose_mg_dl INTEGER,
    clinically_significant BOOLEAN NOT NULL,
    diastolic_bp_mm_hg INTEGER,
    heart_rate_bpm INTEGER,
    respiratory_rate_bpm INTEGER,
    spo2_percent INTEGER,
    systolic_bp_mm_hg INTEGER,
    temperature_celsius DOUBLE PRECISION,
    weight_kg DOUBLE PRECISION,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    recorded_by_assignment_id UUID,
    recorded_by_staff_id UUID,
    registration_id UUID,
    body_position VARCHAR(40),
    source VARCHAR(40),
    notes VARCHAR(1000),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.patients (
    date_of_birth DATE NOT NULL,
    is_active BOOLEAN NOT NULL,
    blood_type VARCHAR(5),
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    gender VARCHAR(10),
    department_id UUID,
    hospital_id UUID,
    id UUID NOT NULL,
    organization_id UUID,
    user_id UUID NOT NULL,
    emergency_contact_phone VARCHAR(20),
    emergency_contact_relationship VARCHAR(50),
    city VARCHAR(100),
    country VARCHAR(100),
    emergency_contact_name VARCHAR(100),
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    middle_name VARCHAR(100),
    phone_number_primary VARCHAR(100) NOT NULL,
    phone_number_secondary VARCHAR(100),
    state VARCHAR(100),
    zip_code VARCHAR(100),
    email VARCHAR(150) NOT NULL,
    address VARCHAR(1024),
    care_team_notes VARCHAR(2000),
    allergies VARCHAR(2048),
    chronic_conditions VARCHAR(2048),
    medical_history_summary VARCHAR(2048),
    address_line1 VARCHAR(255),
    address_line2 VARCHAR(255),
    preferred_pharmacy VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.pharmacy_fills (
    days_supply INTEGER,
    fill_date DATE NOT NULL,
    is_controlled_substance BOOLEAN,
    is_generic_substitution BOOLEAN,
    quantity_dispensed NUMERIC,
    refill_number INTEGER,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    prescription_id UUID,
    ndc_code VARCHAR(20),
    pharmacy_ncpdp VARCHAR(20),
    rxnorm_code VARCHAR(20),
    pharmacy_npi VARCHAR(50),
    prescriber_dea VARCHAR(50),
    prescriber_npi VARCHAR(50),
    quantity_unit VARCHAR(60),
    dosage_form VARCHAR(80),
    source_system VARCHAR(100),
    strength VARCHAR(100),
    pharmacy_phone VARCHAR(120),
    pharmacy_address VARCHAR(500),
    directions VARCHAR(1000),
    notes VARCHAR(1000),
    external_reference_id VARCHAR(255),
    medication_name VARCHAR(255) NOT NULL,
    pharmacy_name VARCHAR(255),
    prescriber_name VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.postpartum_care_plans (
    active BOOLEAN NOT NULL,
    contact_info_verified BOOLEAN NOT NULL,
    discharge_checklist_complete BOOLEAN NOT NULL,
    hemorrhage_protocol_confirmed BOOLEAN NOT NULL,
    immediate_observation_target INTEGER NOT NULL,
    immediate_observations_completed INTEGER NOT NULL,
    immediate_window_completed BOOLEAN NOT NULL,
    immunizations_updated BOOLEAN NOT NULL,
    mental_health_referral_outstanding BOOLEAN NOT NULL,
    pain_followup_outstanding BOOLEAN NOT NULL,
    postpartum_visit_date DATE,
    rh_immunoglobulin_completed BOOLEAN NOT NULL,
    shift_frequency_minutes INTEGER NOT NULL,
    social_support_referral_outstanding BOOLEAN NOT NULL,
    uterotonic_availability_confirmed BOOLEAN NOT NULL,
    closed_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    delivery_occurred_at TIMESTAMP WITHOUT TIME ZONE,
    last_observation_at TIMESTAMP WITHOUT TIME ZONE,
    next_due_at TIMESTAMP WITHOUT TIME ZONE,
    overdue_since TIMESTAMP WITHOUT TIME ZONE,
    stabilization_achieved_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    registration_id UUID,
    follow_up_contact_method VARCHAR(120),
    escalation_reason VARCHAR(500),
    discharge_safety_notes TEXT,
    active_phase VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.postpartum_observation_alerts (
    alert_order INTEGER NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    observation_id UUID NOT NULL,
    alert_code VARCHAR(64),
    triggered_by VARCHAR(120),
    alert_message TEXT NOT NULL,
    alert_severity VARCHAR(60) NOT NULL,
    alert_type VARCHAR(60) NOT NULL,
    PRIMARY KEY (alert_order, observation_id)
);

CREATE TABLE IF NOT EXISTS clinical.postpartum_observation_education_topics (
    observation_id UUID NOT NULL,
    topic VARCHAR(60) NOT NULL,
    PRIMARY KEY (observation_id, topic)
);

CREATE TABLE IF NOT EXISTS clinical.postpartum_observations (
    chills_or_rigors BOOLEAN NOT NULL,
    contact_info_verified BOOLEAN NOT NULL,
    diastolic_bp_mm_hg INTEGER,
    discharge_checklist_complete BOOLEAN NOT NULL,
    education_completed BOOLEAN NOT NULL,
    estimated_blood_loss_ml INTEGER,
    excessive_bleeding BOOLEAN NOT NULL,
    foul_lochia_odor BOOLEAN NOT NULL,
    fundus_height_cm INTEGER,
    hemorrhage_protocol_activated BOOLEAN NOT NULL,
    hemorrhage_protocol_confirmed BOOLEAN NOT NULL,
    immunizations_updated BOOLEAN NOT NULL,
    late_entry BOOLEAN NOT NULL,
    mental_health_referral_suggested BOOLEAN NOT NULL,
    pain_management_referral_suggested BOOLEAN NOT NULL,
    pain_score INTEGER,
    postpartum_visit_date DATE,
    pulse_bpm INTEGER,
    respirations_per_min INTEGER,
    rh_immunoglobulin_completed BOOLEAN NOT NULL,
    social_support_referral_suggested BOOLEAN NOT NULL,
    systolic_bp_mm_hg INTEGER,
    temperature_celsius DOUBLE PRECISION,
    uterine_atony_suspected BOOLEAN NOT NULL,
    uterine_tenderness BOOLEAN NOT NULL,
    uterotonic_availability_confirmed BOOLEAN NOT NULL,
    uterotonic_given BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    documented_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    next_due_at_snapshot TIMESTAMP WITHOUT TIME ZONE,
    observation_time TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    original_entry_time TIMESTAMP WITHOUT TIME ZONE,
    overdue_since_snapshot TIMESTAMP WITHOUT TIME ZONE,
    signed_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    care_plan_id UUID NOT NULL,
    documented_by_user_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    recorded_by_staff_id UUID,
    registration_id UUID,
    supersedes_observation_id UUID,
    follow_up_contact_method VARCHAR(120),
    signoff_credentials VARCHAR(200),
    signoff_name VARCHAR(200),
    lochia_notes VARCHAR(250),
    perineum_findings VARCHAR(1000),
    discharge_safety_notes TEXT,
    education_notes TEXT,
    psychosocial_notes TEXT,
    bladder_status VARCHAR(60),
    fundus_tone VARCHAR(60),
    lochia_amount VARCHAR(60),
    lochia_character VARCHAR(60),
    mood_status VARCHAR(60),
    schedule_phase_at_entry VARCHAR(60),
    sleep_status VARCHAR(60),
    support_status VARCHAR(60),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.prescription_alerts (
    acknowledged BOOLEAN NOT NULL,
    alert_order INTEGER NOT NULL,
    blocking BOOLEAN NOT NULL,
    acknowledged_at TIMESTAMP WITHOUT TIME ZONE,
    prescription_id UUID NOT NULL,
    severity VARCHAR(20),
    alert_type VARCHAR(40),
    reference_code VARCHAR(120),
    message TEXT,
    PRIMARY KEY (alert_order, prescription_id)
);

CREATE TABLE IF NOT EXISTS clinical.prescription_instructions (
    instruction_order INTEGER NOT NULL,
    patient_acknowledged BOOLEAN NOT NULL,
    acknowledged_at TIMESTAMP WITHOUT TIME ZONE,
    language_code VARCHAR(10),
    prescription_id UUID NOT NULL,
    label VARCHAR(120),
    education_url VARCHAR(512),
    instruction_text VARCHAR(1024),
    PRIMARY KEY (instruction_order, prescription_id)
);

CREATE TABLE IF NOT EXISTS clinical.prescription_transmissions (
    attempt_count INTEGER,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    last_attempted_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    prescription_id UUID NOT NULL,
    channel VARCHAR(40),
    status VARCHAR(40),
    destination_reference VARCHAR(120),
    destination VARCHAR(255),
    payload JSONB,
    status_reason TEXT,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.prescriptions (
    allergies_reviewed BOOLEAN NOT NULL,
    contraindications_reviewed BOOLEAN NOT NULL,
    controlled_substance BOOLEAN NOT NULL,
    inpatient_order BOOLEAN NOT NULL,
    interactions_reviewed BOOLEAN NOT NULL,
    quantity NUMERIC,
    refills_allowed INTEGER,
    refills_remaining INTEGER,
    requires_cosign BOOLEAN NOT NULL,
    acknowledged_at TIMESTAMP WITHOUT TIME ZONE,
    cosigned_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    dispatched_at TIMESTAMP WITHOUT TIME ZONE,
    two_factor_verified_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version BIGINT,
    assignment_id UUID NOT NULL,
    cosigned_by_staff_id UUID,
    encounter_id UUID NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    pharmacy_id UUID,
    staff_id UUID NOT NULL,
    dispatch_channel VARCHAR(40),
    dispatch_status VARCHAR(40),
    dose_unit VARCHAR(40),
    two_factor_method VARCHAR(40),
    pharmacy_npi VARCHAR(50),
    quantity_unit VARCHAR(60),
    medication_code VARCHAR(64),
    route VARCHAR(80),
    dosage VARCHAR(100),
    duration VARCHAR(100),
    frequency VARCHAR(100),
    dispatch_reference VARCHAR(120),
    pharmacy_contact VARCHAR(120),
    two_factor_reference VARCHAR(120),
    notes VARCHAR(1024),
    override_reason VARCHAR(1024),
    instructions VARCHAR(2048),
    education_material_json JSONB,
    medication_display_name VARCHAR(255),
    medication_name VARCHAR(255) NOT NULL,
    patient_instruction_json JSONB,
    pharmacy_address VARCHAR(255),
    pharmacy_name VARCHAR(255),
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.procedure_orders (
    blood_products_required BOOLEAN,
    consent_obtained BOOLEAN,
    estimated_duration_minutes INTEGER,
    imaging_guidance_required BOOLEAN,
    requires_anesthesia BOOLEAN,
    requires_sedation BOOLEAN,
    site_marked BOOLEAN,
    cancelled_at TIMESTAMP WITHOUT TIME ZONE,
    completed_at TIMESTAMP WITHOUT TIME ZONE,
    consent_obtained_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    ordered_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    scheduled_datetime TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    encounter_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    ordering_provider_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    laterality VARCHAR(20),
    anesthesia_type VARCHAR(50),
    procedure_code VARCHAR(50),
    sedation_type VARCHAR(50),
    procedure_category VARCHAR(100),
    consent_form_location VARCHAR(500),
    cancellation_reason TEXT,
    clinical_notes TEXT,
    consent_obtained_by VARCHAR(255),
    indication TEXT NOT NULL,
    pre_procedure_instructions TEXT,
    procedure_name VARCHAR(255) NOT NULL,
    special_equipment_needed TEXT,
    status VARCHAR(60) NOT NULL,
    urgency VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.refill_requests (
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    prescription_id UUID NOT NULL,
    preferred_pharmacy VARCHAR(500),
    patient_notes VARCHAR(1000),
    provider_notes VARCHAR(1000),
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.treatment_plan_followups (
    due_on DATE,
    completed_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    assigned_staff_id UUID,
    id UUID NOT NULL,
    treatment_plan_id UUID NOT NULL,
    instructions TEXT,
    label VARCHAR(255),
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.treatment_plan_reviews (
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    reviewer_staff_id UUID NOT NULL,
    treatment_plan_id UUID NOT NULL,
    comment TEXT,
    action VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.treatment_plans (
    patient_visibility BOOLEAN NOT NULL,
    timeline_review_date DATE,
    timeline_start_date DATE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    patient_visibility_at TIMESTAMP WITHOUT TIME ZONE,
    sign_off_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version BIGINT,
    assignment_id UUID NOT NULL,
    author_staff_id UUID NOT NULL,
    encounter_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    sign_off_by UUID,
    supervising_staff_id UUID,
    follow_up_summary TEXT,
    lifestyle_plan_json JSONB,
    medication_plan_json JSONB,
    problem_statement TEXT NOT NULL,
    referral_plan_json JSONB,
    responsible_parties_json JSONB,
    therapeutic_goals_json JSONB,
    timeline_summary TEXT,
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.treatments (
    active BOOLEAN NOT NULL,
    duration_minutes INTEGER,
    price NUMERIC NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    assignment_id UUID NOT NULL,
    department_id UUID NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    description VARCHAR(1000),
    name VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.ultrasound_orders (
    gestational_age_at_order INTEGER,
    is_high_risk_pregnancy BOOLEAN,
    scan_count_for_pregnancy INTEGER,
    scheduled_date DATE,
    cancelled_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    ordered_date TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    priority VARCHAR(20),
    cancelled_by VARCHAR(200),
    ordered_by VARCHAR(200),
    appointment_location VARCHAR(500),
    cancellation_reason VARCHAR(500),
    high_risk_notes VARCHAR(1000),
    special_instructions VARCHAR(1000),
    clinical_indication VARCHAR(2000),
    scheduled_time VARCHAR(255),
    scan_type VARCHAR(60) NOT NULL,
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.ultrasound_reports (
    abdominal_circumference_mm DOUBLE PRECISION,
    amniotic_fluid_index DOUBLE PRECISION,
    anatomy_survey_complete BOOLEAN,
    anomalies_detected BOOLEAN,
    biparietal_diameter_mm DOUBLE PRECISION,
    cervical_length_mm DOUBLE PRECISION,
    crown_rump_length_mm DOUBLE PRECISION,
    due_date_confirmed BOOLEAN,
    estimated_due_date DATE,
    estimated_fetal_weight_grams INTEGER,
    femur_length_mm DOUBLE PRECISION,
    fetal_cardiac_activity BOOLEAN,
    fetal_heart_rate INTEGER,
    fetal_movement_observed BOOLEAN,
    fetal_tone_normal BOOLEAN,
    follow_up_required BOOLEAN,
    genetic_screening_recommended BOOLEAN,
    gestational_age_at_scan INTEGER,
    gestational_age_days INTEGER,
    head_circumference_mm DOUBLE PRECISION,
    nasal_bone_present BOOLEAN,
    next_ultrasound_recommended_weeks INTEGER,
    nuchal_translucency_mm DOUBLE PRECISION,
    number_of_fetuses INTEGER,
    patient_notified BOOLEAN,
    report_reviewed_by_provider BOOLEAN,
    scan_date DATE NOT NULL,
    specialist_referral_needed BOOLEAN,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    patient_notified_at TIMESTAMP WITHOUT TIME ZONE,
    report_finalized_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    ultrasound_order_id UUID NOT NULL,
    placental_grade VARCHAR(20),
    amniotic_fluid_level VARCHAR(50),
    fetal_position VARCHAR(100),
    placental_location VARCHAR(100),
    scan_performed_by_credentials VARCHAR(100),
    umbilical_artery_doppler VARCHAR(100),
    uterine_artery_doppler VARCHAR(100),
    genetic_screening_type VARCHAR(200),
    report_finalized_by VARCHAR(200),
    scan_performed_by VARCHAR(200),
    specialist_referral_type VARCHAR(200),
    provider_review_notes VARCHAR(1000),
    anatomy_findings VARCHAR(2000),
    anomaly_description VARCHAR(2000),
    follow_up_recommendations VARCHAR(2000),
    findings_summary VARCHAR(3000),
    interpretation VARCHAR(3000),
    finding_category VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.visit_education_documentation (
    birth_plan_discussed BOOLEAN NOT NULL,
    breastfeeding_discussed BOOLEAN NOT NULL,
    exercise_discussed BOOLEAN NOT NULL,
    mental_health_discussed BOOLEAN NOT NULL,
    nutrition_discussed BOOLEAN NOT NULL,
    patient_engaged BOOLEAN NOT NULL,
    patient_understood BOOLEAN NOT NULL,
    requires_follow_up BOOLEAN NOT NULL,
    warning_signs_discussed BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    follow_up_scheduled_for TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    encounter_id UUID NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    staff_id UUID NOT NULL,
    topic_discussed VARCHAR(500) NOT NULL,
    comprehension_notes VARCHAR(1000),
    follow_up_plan VARCHAR(1000),
    patient_concerns VARCHAR(1000),
    patient_questions VARCHAR(1000),
    discussion_notes TEXT,
    category VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS clinical.visit_education_resources_provided (
    documentation_id UUID NOT NULL,
    resource_id UUID
);

CREATE TABLE IF NOT EXISTS empi.identity_aliases (
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_by UUID,
    id UUID NOT NULL,
    master_identity_id UUID NOT NULL,
    updated_by UUID,
    source_system VARCHAR(100),
    alias_value VARCHAR(255) NOT NULL,
    alias_type VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS empi.master_identities (
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_by UUID,
    department_id UUID,
    hospital_id UUID,
    id UUID NOT NULL,
    organization_id UUID,
    patient_id UUID,
    updated_by UUID,
    empi_number VARCHAR(64) NOT NULL,
    mrn_snapshot VARCHAR(100),
    source_system VARCHAR(100),
    metadata TEXT,
    resolution_state VARCHAR(60),
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS empi.merge_events (
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    merged_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    department_id UUID,
    hospital_id UUID,
    id UUID NOT NULL,
    merged_by UUID,
    organization_id UUID,
    primary_identity_id UUID NOT NULL,
    secondary_identity_id UUID NOT NULL,
    resolution VARCHAR(50),
    undo_token VARCHAR(100),
    notes TEXT,
    merge_type VARCHAR(60),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS governance.security_policy_approvals (
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    required_by TIMESTAMP WITHOUT TIME ZONE,
    submitted_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    severity VARCHAR(40),
    baseline_version VARCHAR(80),
    change_type VARCHAR(80),
    requested_by VARCHAR(120),
    policy_name VARCHAR(160) NOT NULL,
    summary VARCHAR(1000),
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS governance.security_policy_baselines (
    policy_count INTEGER NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    enforcement_level VARCHAR(60) NOT NULL,
    baseline_version VARCHAR(80) NOT NULL,
    created_by VARCHAR(120),
    title VARCHAR(160) NOT NULL,
    summary VARCHAR(1000),
    control_objectives_json TEXT,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS governance.security_rule_sets (
    rule_count INTEGER NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    published_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    enforcement_scope VARCHAR(60) NOT NULL,
    code VARCHAR(100) NOT NULL,
    published_by VARCHAR(120),
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    metadata_json TEXT,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS hospital.department_translations (
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    language_code VARCHAR(10) NOT NULL,
    assignment_id UUID NOT NULL,
    department_id UUID NOT NULL,
    id UUID NOT NULL,
    language VARCHAR(100),
    description VARCHAR(2048),
    name VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS hospital.departments (
    bed_capacity INTEGER,
    is_active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    assignment_id UUID NOT NULL,
    head_of_department_staff_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    phone_number VARCHAR(20),
    code VARCHAR(32) NOT NULL,
    department_service_level VARCHAR(60),
    department_billing_system VARCHAR(120),
    department_data_steward VARCHAR(120),
    department_ehr_system VARCHAR(120),
    department_inventory_system VARCHAR(120),
    department_owner_team VARCHAR(120),
    description VARCHAR(2048),
    department_integration_notes VARCHAR(255),
    department_owner_contact_email VARCHAR(255),
    email VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS hospital.hospitals (
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    organization_id UUID,
    phone_number VARCHAR(30),
    po_box VARCHAR(50),
    zip_code VARCHAR(50),
    hospital_service_level VARCHAR(60),
    city VARCHAR(100),
    code VARCHAR(100) NOT NULL,
    country VARCHAR(100),
    license_number VARCHAR(100),
    province VARCHAR(100),
    region VARCHAR(100),
    sector VARCHAR(100),
    state VARCHAR(100),
    hospital_billing_system VARCHAR(120),
    hospital_data_steward VARCHAR(120),
    hospital_ehr_system VARCHAR(120),
    hospital_inventory_system VARCHAR(120),
    hospital_owner_team VARCHAR(120),
    address VARCHAR(2048),
    email VARCHAR(255),
    hospital_integration_notes VARCHAR(255),
    hospital_owner_contact_email VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    website VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS hospital.organizations (
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    primary_contact_phone VARCHAR(32),
    service_level VARCHAR(60),
    code VARCHAR(100) NOT NULL,
    billing_system VARCHAR(120),
    data_steward VARCHAR(120),
    default_timezone VARCHAR(120),
    ehr_system VARCHAR(120),
    inventory_system VARCHAR(120),
    owner_team VARCHAR(120),
    description VARCHAR(500),
    onboarding_notes VARCHAR(1000),
    integration_notes VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    owner_contact_email VARCHAR(255),
    primary_contact_email VARCHAR(255),
    type VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS hospital.staff (
    active BOOLEAN NOT NULL,
    end_date DATE,
    start_date DATE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    signature_captured_at TIMESTAMP WITHOUT TIME ZONE,
    signature_revoked_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    assignment_id UUID NOT NULL,
    department_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    npi VARCHAR(20),
    license_number VARCHAR(100),
    specialization VARCHAR(100),
    signature_certificate_id VARCHAR(120),
    name VARCHAR(500),
    signature_public_key TEXT,
    employment_type VARCHAR(60) NOT NULL,
    job_title VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS hospital.staff_availability (
    available_from TIME,
    available_to TIME,
    date DATE NOT NULL,
    day_off BOOLEAN NOT NULL,
    is_active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    staff_id UUID NOT NULL,
    note VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS hospital.staff_leave_requests (
    end_date DATE NOT NULL,
    end_time TIME,
    requires_coverage BOOLEAN NOT NULL,
    start_date DATE NOT NULL,
    start_time TIME,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    reviewed_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    department_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    requested_by_user_id UUID NOT NULL,
    reviewed_by_user_id UUID,
    staff_id UUID NOT NULL,
    manager_note VARCHAR(1000),
    reason VARCHAR(1000),
    leave_type VARCHAR(60) NOT NULL,
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS hospital.staff_shifts (
    end_time TIME NOT NULL,
    published BOOLEAN NOT NULL,
    shift_date DATE NOT NULL,
    start_time TIME NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    status_changed_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    department_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    last_modified_by_user_id UUID,
    scheduled_by_user_id UUID NOT NULL,
    staff_id UUID NOT NULL,
    cancellation_reason VARCHAR(1000),
    notes VARCHAR(1000),
    shift_type VARCHAR(60) NOT NULL,
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS lab.lab_orders (
    documentation_shared_with_lab BOOLEAN NOT NULL,
    standing_order BOOLEAN NOT NULL,
    standing_order_review_interval_days INTEGER,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    order_datetime TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    signed_at TIMESTAMP WITHOUT TIME ZONE,
    standing_order_expires_at TIMESTAMP WITHOUT TIME ZONE,
    standing_order_last_reviewed_at TIMESTAMP WITHOUT TIME ZONE,
    standing_order_review_due_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    assignment_id UUID NOT NULL,
    encounter_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    lab_test_definition_id UUID NOT NULL,
    ordering_staff_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    signed_by_user_id UUID,
    ordering_provider_npi VARCHAR(20),
    primary_diagnosis_code VARCHAR(20),
    order_channel_other VARCHAR(120),
    provider_signature_digest VARCHAR(512),
    clinical_indication VARCHAR(2048) NOT NULL,
    medical_necessity_note VARCHAR(2048),
    notes VARCHAR(2048),
    standing_order_review_notes VARCHAR(2048),
    additional_diagnosis_codes TEXT,
    documentation_reference VARCHAR(255),
    order_channel VARCHAR(60) NOT NULL,
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS lab.lab_results (
    acknowledged BOOLEAN NOT NULL,
    released BOOLEAN NOT NULL,
    acknowledged_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    released_at TIMESTAMP WITHOUT TIME ZONE,
    result_date TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    signed_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    acknowledged_by_user_id UUID,
    assignment_id UUID NOT NULL,
    id UUID NOT NULL,
    lab_order_id UUID NOT NULL,
    released_by_user_id UUID,
    signed_by_user_id UUID,
    result_unit VARCHAR(50),
    notes VARCHAR(2048),
    result_value VARCHAR(2048) NOT NULL,
    signature_notes VARCHAR(2048),
    signature_value VARCHAR(2048),
    acknowledged_by_display VARCHAR(255),
    released_by_display VARCHAR(255),
    signed_by_display VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS lab.lab_test_definitions (
    is_active BOOLEAN NOT NULL,
    turnaround_time_minutes INTEGER,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    assignment_id UUID,
    hospital_id UUID,
    id UUID NOT NULL,
    test_code VARCHAR(50) NOT NULL,
    unit VARCHAR(50),
    category VARCHAR(100),
    sample_type VARCHAR(100),
    preparation_instructions VARCHAR(1000),
    description VARCHAR(2048),
    name VARCHAR(255) NOT NULL,
    reference_ranges TEXT,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS platform.department_platform_service_links (
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    department_id UUID NOT NULL,
    id UUID NOT NULL,
    organization_service_id UUID NOT NULL,
    dept_link_service_level VARCHAR(60),
    credentials_reference VARCHAR(120),
    dept_link_data_steward VARCHAR(120),
    dept_link_owner_team VARCHAR(120),
    dept_link_owner_contact_email VARCHAR(255),
    override_endpoint VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS platform.hospital_platform_service_links (
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    organization_service_id UUID NOT NULL,
    link_service_level VARCHAR(60),
    credentials_reference VARCHAR(120),
    link_data_steward VARCHAR(120),
    link_owner_team VARCHAR(120),
    link_owner_contact_email VARCHAR(255),
    override_endpoint VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS platform.organization_platform_services (
    managed_by_platform BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    organization_id UUID NOT NULL,
    service_level VARCHAR(60),
    api_key_reference VARCHAR(120),
    provider VARCHAR(120),
    service_billing_system VARCHAR(120),
    service_data_steward VARCHAR(120),
    service_ehr_system VARCHAR(120),
    service_inventory_system VARCHAR(120),
    service_owner_team VARCHAR(120),
    base_url VARCHAR(255),
    documentation_url VARCHAR(255),
    service_integration_notes VARCHAR(255),
    service_owner_contact_email VARCHAR(255),
    service_type VARCHAR(60) NOT NULL,
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS platform.platform_feature_flag_overrides (
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    flag_key VARCHAR(120) NOT NULL,
    updated_by VARCHAR(120),
    description VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS platform.platform_release_windows (
    freeze_changes BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    ends_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    starts_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    environment VARCHAR(60) NOT NULL,
    owner_team VARCHAR(120),
    window_name VARCHAR(120) NOT NULL,
    description VARCHAR(240),
    notes VARCHAR(255),
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS admission_applied_order_sets (
    admission_id UUID NOT NULL,
    order_set_id UUID NOT NULL
);

CREATE TABLE IF NOT EXISTS admission_order_sets (
    active BOOLEAN NOT NULL,
    version INTEGER NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    deactivated_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_by_staff_id UUID,
    department_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    last_modified_by_staff_id UUID,
    name VARCHAR(200) NOT NULL,
    clinical_guidelines VARCHAR(500),
    deactivation_reason VARCHAR(500),
    description VARCHAR(1000),
    admission_type VARCHAR(60) NOT NULL,
    order_items JSONB NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS admissions (
    length_of_stay_days INTEGER,
    actual_discharge_date_time TIMESTAMP WITHOUT TIME ZONE,
    admission_date_time TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    expected_discharge_date_time TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    admitting_provider_id UUID NOT NULL,
    attending_physician_id UUID,
    department_id UUID,
    discharging_provider_id UUID,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    primary_diagnosis_code VARCHAR(20),
    room_bed VARCHAR(50),
    insurance_auth_number VARCHAR(100),
    admission_source VARCHAR(200),
    chief_complaint VARCHAR(500) NOT NULL,
    primary_diagnosis_description VARCHAR(500),
    admission_notes VARCHAR(2000),
    discharge_instructions VARCHAR(2000),
    discharge_summary VARCHAR(5000),
    acuity_level VARCHAR(60) NOT NULL,
    admission_type VARCHAR(60) NOT NULL,
    consulting_physicians JSONB,
    custom_orders JSONB,
    discharge_disposition VARCHAR(60),
    follow_up_appointments JSONB,
    metadata JSONB,
    secondary_diagnoses JSONB,
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS announcement (
    date TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    text VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS articles (
    published_at TIMESTAMP WITH TIME ZONE,
    id UUID NOT NULL,
    content VARCHAR(2000),
    author VARCHAR(255),
    image_url VARCHAR(255),
    title VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS education_resources (
    id BIGINT NOT NULL,
    category VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    url VARCHAR(255),
    content TEXT,
    type VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS general_referrals (
    priority_score INTEGER,
    acknowledged_at TIMESTAMP WITHOUT TIME ZONE,
    completed_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    scheduled_appointment_at TIMESTAMP WITHOUT TIME ZONE,
    sla_due_at TIMESTAMP WITHOUT TIME ZONE,
    submitted_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    hospital_id UUID NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    receiving_provider_id UUID,
    referring_provider_id UUID NOT NULL,
    target_department_id UUID,
    insurance_auth_number VARCHAR(100),
    anticipated_treatment VARCHAR(300),
    appointment_location VARCHAR(300),
    target_facility_name VARCHAR(300),
    cancellation_reason VARCHAR(500),
    clinical_question VARCHAR(500),
    referral_reason VARCHAR(500) NOT NULL,
    acknowledgement_notes VARCHAR(1000),
    clinical_indication VARCHAR(1000),
    follow_up_recommendations VARCHAR(1000),
    clinical_summary VARCHAR(2000),
    completion_summary VARCHAR(2000),
    current_medications JSONB,
    diagnoses JSONB,
    metadata JSONB,
    referral_type VARCHAR(60) NOT NULL,
    status VARCHAR(60) NOT NULL,
    target_specialty VARCHAR(60) NOT NULL,
    urgency VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS patient_insurances_v2 (
    coverage_end DATE,
    coverage_start DATE,
    primary_plan BOOLEAN NOT NULL,
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    group_number VARCHAR(120),
    member_id VARCHAR(120),
    policy_number VARCHAR(120),
    provider_name VARCHAR(180) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS patient_medical_histories_v2 (
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    allergies TEXT,
    conditions TEXT,
    medications TEXT,
    notes TEXT,
    surgeries TEXT,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS patients_v2 (
    date_of_birth DATE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    id UUID NOT NULL,
    gender VARCHAR(32) NOT NULL,
    phone VARCHAR(40),
    postal_code VARCHAR(40),
    mrn VARCHAR(64) NOT NULL,
    city VARCHAR(120),
    country VARCHAR(120),
    first_name VARCHAR(120) NOT NULL,
    last_name VARCHAR(120) NOT NULL,
    middle_name VARCHAR(120),
    state VARCHAR(120),
    address_line1 VARCHAR(180),
    address_line2 VARCHAR(180),
    email VARCHAR(180),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS permission_matrix_audit_events (
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    id UUID NOT NULL,
    snapshot_id UUID,
    description VARCHAR(512),
    initiated_by VARCHAR(255),
    matrix_json TEXT,
    metadata_json TEXT,
    action VARCHAR(60) NOT NULL,
    left_environment VARCHAR(60),
    right_environment VARCHAR(60),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS permission_matrix_snapshots (
    version_number INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    id UUID NOT NULL,
    source_snapshot_id UUID,
    notes VARCHAR(2000),
    created_by VARCHAR(255),
    label VARCHAR(255),
    environment VARCHAR(60) NOT NULL,
    matrix_json TEXT NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS referral_attachments (
    size_bytes BIGINT NOT NULL,
    uploaded_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    referral_id UUID NOT NULL,
    uploaded_by_staff_id UUID,
    category VARCHAR(50) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    display_name VARCHAR(300) NOT NULL,
    description VARCHAR(500),
    storage_key VARCHAR(500) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS testimonials (
    rating INTEGER,
    created_at TIMESTAMP WITH TIME ZONE,
    id UUID NOT NULL,
    text VARCHAR(1000),
    author VARCHAR(255) NOT NULL,
    avatar_url VARCHAR(255),
    role_label VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS reference.catalog_entries (
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    catalog_id UUID NOT NULL,
    id UUID NOT NULL,
    code VARCHAR(160) NOT NULL,
    description VARCHAR(255),
    label VARCHAR(255) NOT NULL,
    metadata VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS reference.catalogs (
    entry_count INTEGER NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    last_imported_at TIMESTAMP WITHOUT TIME ZONE,
    published_at TIMESTAMP WITHOUT TIME ZONE,
    scheduled_publish_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    code VARCHAR(120) NOT NULL,
    description VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    status VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS security.notifications (
    read BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    message VARCHAR(255) NOT NULL,
    recipient_username VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS security.organization_security_policies (
    active BOOLEAN NOT NULL,
    enforce_strict BOOLEAN NOT NULL,
    priority INTEGER NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    organization_id UUID NOT NULL,
    code VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    name VARCHAR(255) NOT NULL,
    policy_type VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS security.organization_security_rules (
    active BOOLEAN NOT NULL,
    priority INTEGER NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    security_policy_id UUID NOT NULL,
    code VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    rule_value VARCHAR(2000),
    name VARCHAR(255) NOT NULL,
    rule_type VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS security.password_reset_tokens (
    consumed_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    expiration TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    ip_address VARCHAR(45),
    token_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS security.permissions (
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    assignment_id UUID NOT NULL,
    id UUID NOT NULL,
    name VARCHAR(50) NOT NULL,
    code VARCHAR(80) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS security.role_permissions (
    permission_id UUID NOT NULL,
    role_id UUID NOT NULL,
    PRIMARY KEY (permission_id, role_id)
);

CREATE TABLE IF NOT EXISTS security.roles (
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS security.user_mfa_enrollments (
    enabled BOOLEAN NOT NULL,
    primary_factor BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    enrolled_at TIMESTAMP WITHOUT TIME ZONE,
    last_verified_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    channel VARCHAR(120),
    metadata_json VARCHAR(255),
    method VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS security.user_recovery_contacts (
    primary_contact BOOLEAN NOT NULL,
    verified BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    verified_at TIMESTAMP WITHOUT TIME ZONE,
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    contact_value VARCHAR(255) NOT NULL,
    notes VARCHAR(255),
    contact_type VARCHAR(60) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS security.user_role_hospital_assignment (
    is_active BOOLEAN NOT NULL,
    start_date DATE,
    assigned_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    confirmation_sent_at TIMESTAMP WITHOUT TIME ZONE,
    confirmation_verified_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    confirmation_code VARCHAR(16),
    hospital_id UUID,
    id UUID NOT NULL,
    registered_by_user_id UUID,
    role_id UUID NOT NULL,
    user_id UUID NOT NULL,
    assignment_code VARCHAR(50),
    description VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS security.user_roles (
    role_id UUID NOT NULL,
    user_id UUID NOT NULL,
    PRIMARY KEY (role_id, user_id)
);

CREATE TABLE IF NOT EXISTS security.users (
    force_password_change BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL,
    is_deleted BOOLEAN NOT NULL,
    activation_token_expires_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    last_login_at TIMESTAMP WITHOUT TIME ZONE,
    password_changed_at TIMESTAMP WITHOUT TIME ZONE,
    password_rotation_forced_at TIMESTAMP WITHOUT TIME ZONE,
    password_rotation_warning_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    phone_number VARCHAR(20) NOT NULL,
    username VARCHAR(50) NOT NULL,
    activation_token VARCHAR(100),
    email VARCHAR(100) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    profile_image_url VARCHAR(500),
    password_hash VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS support.audit_event_logs (
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    event_timestamp TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    assignment_id UUID,
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    ip_address VARCHAR(45),
    target_entity_type VARCHAR(50),
    target_resource_id VARCHAR(100),
    details VARCHAR(2048),
    event_description VARCHAR(2048) NOT NULL,
    hospital_name VARCHAR(255),
    resource_name VARCHAR(255),
    role_name VARCHAR(255),
    user_name VARCHAR(255),
    event_type VARCHAR(60) NOT NULL,
    status VARCHAR(60),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS support.chat_messages (
    is_read BOOLEAN NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    timestamp TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    assignment_id UUID NOT NULL,
    id UUID NOT NULL,
    recipient_id UUID NOT NULL,
    sender_id UUID NOT NULL,
    content VARCHAR(2048) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS support.frontend_audit_events (
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    occurred_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    id UUID NOT NULL,
    ip_address VARCHAR(45),
    event_type VARCHAR(120) NOT NULL,
    metadata VARCHAR(4000),
    actor VARCHAR(255),
    user_agent VARCHAR(255),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS support.service_translations (
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    language_code VARCHAR(10) NOT NULL,
    assignment_id UUID NOT NULL,
    id UUID NOT NULL,
    treatment_id UUID NOT NULL,
    description VARCHAR(1000),
    name VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
);

