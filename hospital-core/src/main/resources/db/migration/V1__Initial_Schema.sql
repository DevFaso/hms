CREATE SCHEMA IF NOT EXISTS "security";

CREATE TABLE Hospital (
    hospital_id VARCHAR2(255) PRIMARY KEY,
    name VARCHAR2(255) NOT NULL,
    address TEXT,
    city VARCHAR2(255),
    state VARCHAR2(255),
    zip_code VARCHAR2(255),
    country VARCHAR2(255),
    phone_number VARCHAR2(255),
    email VARCHAR2(255),
    website VARCHAR2(255),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

    CREATE TABLE "security"."users" (
        id VARCHAR2(255) PRIMARY KEY,
        username VARCHAR2(255) UNIQUE NOT NULL,
        password_hash VARCHAR2(255) NOT NULL,
        email VARCHAR2(255) UNIQUE NOT NULL,
        first_name VARCHAR2(255),
        last_name VARCHAR2(255),
        phone_number VARCHAR2(255),
        is_active BOOLEAN DEFAULT TRUE,
        activation_token VARCHAR2(255),
        activation_token_expires_at TIMESTAMP WITHOUT TIME ZONE,
        created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
    );

    CREATE TABLE "security"."roles" (
        id VARCHAR2(255) PRIMARY KEY,
        code VARCHAR2(255) UNIQUE NOT NULL,
        name VARCHAR2(255),
        created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
    );

    CREATE TABLE "security"."user_role_hospital_assignment" (
        id VARCHAR2(255) PRIMARY KEY,
        user_id VARCHAR2(255) REFERENCES "security"."users"(id),
        role_id VARCHAR2(255) REFERENCES "security"."roles"(id),
        hospital_id VARCHAR2(255) REFERENCES Hospital(hospital_id),
        is_active BOOLEAN DEFAULT TRUE,
        created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
    );

    CREATE TABLE "security"."permissions" (
        id VARCHAR2(255) PRIMARY KEY,
        code VARCHAR2(255) UNIQUE NOT NULL,
        name VARCHAR2(255),
        created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
    );

    CREATE TABLE "security"."role_permissions" (
        role_id VARCHAR2(255) REFERENCES "security"."roles"(id),
        permission_id VARCHAR2(255) REFERENCES "security"."permissions"(id),
        created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
    );
CREATE TABLE Patient (
    patient_id VARCHAR2(255) PRIMARY KEY,
    mrn VARCHAR2(255),
    first_name VARCHAR2(255),
    last_name VARCHAR2(255),
    middle_name VARCHAR2(255),
    date_of_birth DATE,
    gender VARCHAR2(50),
    ssn VARCHAR2(255),
    address TEXT,
    city VARCHAR2(255),
    state VARCHAR2(255),
    zip_code VARCHAR2(255),
    country VARCHAR2(255),
    phone_number_primary VARCHAR2(255),
    phone_number_secondary VARCHAR2(255),
    emergency_contact_name VARCHAR2(255),
    emergency_contact_phone VARCHAR2(255),
    emergency_contact_relationship VARCHAR2(255),
    blood_type VARCHAR2(10),
    allergies TEXT,
    medical_history_summary TEXT,
    registration_hospital_id VARCHAR2(255) REFERENCES Hospital(hospital_id),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);


CREATE TABLE Role (
    role_id VARCHAR2(255) PRIMARY KEY,
    role_name VARCHAR2(255) UNIQUE NOT NULL,
    description TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE UserRoleHospitalAssignment (
    user_id VARCHAR2(255) REFERENCES "security"."users"(id),
    role_id VARCHAR2(255) REFERENCES Role(role_id),
    hospital_id VARCHAR2(255) REFERENCES Hospital(hospital_id),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE Permission (
    permission_id VARCHAR2(255) PRIMARY KEY,
    permission_name VARCHAR2(255) UNIQUE NOT NULL,
    description TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE RolePermission (
    role_permission_id VARCHAR2(255) PRIMARY KEY,
    role_id VARCHAR2(255) REFERENCES Role(role_id),
    permission_id VARCHAR2(255) REFERENCES Permission(permission_id),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);


CREATE TABLE Department (
    department_id VARCHAR2(255) PRIMARY KEY,
    hospital_id VARCHAR2(255) REFERENCES Hospital(hospital_id),
    name VARCHAR2(255) NOT NULL,
    description TEXT,
    head_of_department_staff_id VARCHAR2(255) REFERENCES Staff(staff_id),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE Staff (
    staff_id VARCHAR2(255) PRIMARY KEY,
    user_id VARCHAR2(255) REFERENCES "security"."users"(id),
    employee_id_number VARCHAR2(255),
    specialty VARCHAR2(255),
    department_id VARCHAR2(255) REFERENCES Department(department_id),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);



CREATE TABLE Service (
    service_id VARCHAR2(255) PRIMARY KEY,
    user_id VARCHAR2(255) REFERENCES "security"."users"(id),
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    base_price DECIMAL(10, 2),
    category VARCHAR2(255),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE HospitalService (
    hospital_service_id VARCHAR2(255) PRIMARY KEY,
    hospital_id VARCHAR2(255) REFERENCES Hospital(hospital_id),
    service_id VARCHAR2(255) REFERENCES Service(service_id),
    department_id VARCHAR2(255) REFERENCES Department(department_id),
            activation_token_expires_at TIMESTAMP,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
-- removed stray line: created_by_user_id should be inside a table definition
CREATE TABLE Appointment (
    appointment_id VARCHAR2(255) PRIMARY KEY,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    staff_id_primary_provider VARCHAR2(255) REFERENCES Staff(staff_id),
    department_id VARCHAR2(255) REFERENCES Department(department_id),
    hospital_service_id VARCHAR2(255) REFERENCES HospitalService(hospital_service_id),
    appointment_datetime TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    duration_minutes INTEGER,
    reason_for_visit TEXT,
    status VARCHAR2(50),
    processed_by_user_id VARCHAR2(255) REFERENCES "security"."users"(id),
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
-- removed stray line: user_id should be inside a table definition
CREATE TABLE Encounter (
    encounter_id VARCHAR2(255) PRIMARY KEY,
    patient_id VARCHAR2(255) REFERENCES Patient(patient_id),
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    encounter_datetime_start TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    encounter_datetime_end TIMESTAMP WITHOUT TIME ZONE,
    encounter_type VARCHAR2(255),
    chief_complaint TEXT,
    diagnosis_codes TEXT, 
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE LabTestDefinition (
    lab_test_definition_id VARCHAR2(255) PRIMARY KEY,
    test_name VARCHAR2(255) NOT NULL,
    description TEXT,
    sample_type_required VARCHAR2(255),
    reference_range TEXT,
    units VARCHAR2(50),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE LabOrder (
    lab_order_id VARCHAR2(255) PRIMARY KEY,
    encounter_id VARCHAR2(255) REFERENCES Encounter(encounter_id),
    patient_id VARCHAR2(255) REFERENCES Patient(patient_id),
    ordering_staff_id VARCHAR2(255) REFERENCES Staff(staff_id),
    order_datetime TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    collection_datetime TIMESTAMP WITHOUT TIME ZONE,
    status VARCHAR2(50),
    notes TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP

CREATE TABLE LabResult (
    lab_order_id VARCHAR2(255) REFERENCES LabOrder(lab_order_id),
    lab_test_definition_id VARCHAR2(255) REFERENCES LabTestDefinition(lab_test_definition_id),
    result_value TEXT,
    result_unit VARCHAR2(50),
    is_abnormal BOOLEAN,
    reference_range_used TEXT,
    result_datetime TIMESTAMP WITHOUT TIME ZONE,
    notes TEXT,
        appointment_datetime TIMESTAMP NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE BillingInvoice (
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    hospital_id VARCHAR2(255) REFERENCES Hospital(hospital_id),
    encounter_id VARCHAR2(255) REFERENCES Encounter(encounter_id),
    invoice_date DATE,
    due_date DATE,
    total_amount DECIMAL(10, 2),
    amount_paid DECIMAL(10, 2) DEFAULT 0.00,
    status VARCHAR2(50),
        encounter_datetime_start TIMESTAMP NOT NULL,
        encounter_datetime_end TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE InvoiceItem (
    invoice_item_id VARCHAR2(255) PRIMARY KEY,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    lab_result_id VARCHAR2(255) REFERENCES LabResult(lab_result_id),
    description TEXT,
    quantity INTEGER,
    unit_price DECIMAL(10, 2),
    total_price DECIMAL(10, 2),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
        order_datetime TIMESTAMP NOT NULL,
CREATE TABLE Payment (
    payment_id VARCHAR2(255) PRIMARY KEY,
    invoice_id VARCHAR2(255) REFERENCES BillingInvoice(invoice_id),
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    payment_method VARCHAR2(255),
    transaction_id VARCHAR2(255),
    notes TEXT,
    processed_by_user_id VARCHAR2(255) REFERENCES "security"."users"(id),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE InsuranceProvider (
    provider_id VARCHAR2(255) PRIMARY KEY,
    name VARCHAR2(255) NOT NULL,
    contact_email VARCHAR2(255),
    contact_phone VARCHAR2(255),
    is_active BOOLEAN DEFAULT TRUE,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE InsurancePlan (
    plan_id VARCHAR2(255) PRIMARY KEY,
    provider_id VARCHAR2(255) REFERENCES InsuranceProvider(provider_id),
        payment_date TIMESTAMP,
    plan_type VARCHAR2(255),
    coverage_details TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP

CREATE TABLE PatientInsurance (
    patient_insurance_id VARCHAR2(255) PRIMARY KEY,
    patient_id VARCHAR2(255) REFERENCES Patient(patient_id),
    plan_id VARCHAR2(255) REFERENCES InsurancePlan(plan_id),
    member_id VARCHAR2(255),
    group_number VARCHAR2(255),
    effective_date DATE,
    expiration_date DATE,
    is_primary BOOLEAN,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP

CREATE TABLE AuditEventLog (
    event_id VARCHAR2(255) PRIMARY KEY,
    user_id VARCHAR2(255) REFERENCES "security"."users"(id),
    event_type VARCHAR2(255),
    event_description TEXT,
    event_timestamp TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
        event_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR2(255),
    details TEXT
);

CREATE TABLE ServiceTranslation (
    translation_id VARCHAR2(255) PRIMARY KEY,
    service_id VARCHAR2(255) REFERENCES Service(service_id),
    language_code VARCHAR2(10) NOT NULL,
    service_name VARCHAR2(255),
    description TEXT,
    UNIQUE (service_id, language_code)
);

CREATE TABLE DepartmentTranslation (
    translation_id VARCHAR2(255) PRIMARY KEY,
    department_id VARCHAR2(255) REFERENCES Department(department_id),
    language_code VARCHAR2(10) NOT NULL,
    name VARCHAR2(255),
    description TEXT,
    UNIQUE (department_id, language_code)
);

