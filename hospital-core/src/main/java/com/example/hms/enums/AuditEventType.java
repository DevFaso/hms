package com.example.hms.enums;

public enum AuditEventType {
    // Authentication & session
    LOGIN,
    LOGOUT,
    LOGIN_FAILURE,
    PASSWORD_RESET_REQUEST,
    PASSWORD_RESET_COMPLETE,
    PASSWORD_CHANGED,
    MFA_CHALLENGE,
    MFA_FAILURE,
    MFA_ENROLLED,
    MFA_VERIFIED,
    MFA_BACKUP_USED,
    ACCOUNT_LOCKED,
    ACCOUNT_UNLOCKED,
    SESSION_TERMINATED,
    TOKEN_REFRESH,
    TOKEN_REVOKED,
    PASSWORD_HISTORY_VIOLATION,

    // User & access administration
    USER_CREATE,
    USER_UPDATE,
    USER_DELETE,
    USER_DISABLE,
    USER_ENABLE,
    USER_BOOTSTRAP,
    ROLE_ASSIGNED,
    ROLE_REVOKED,
    PERMISSION_GRANTED,
    PERMISSION_REVOKED,
    ASSIGNMENT_CONFIRMED,

    // Patient identity & consent
    PATIENT_CREATE,
    PATIENT_UPDATE,
    PATIENT_DELETE,
    PATIENT_ACCESS,
    PATIENT_EXPORT,
    PATIENT_MERGE,
    BREAK_GLASS_ACCESS,
    CONSENT_GRANTED,
    CONSENT_REVOKED,
    CONSENT_UPDATE,
    RECORD_SHARE,

    // Care delivery workflow
    APPOINTMENT_CREATED,
    APPOINTMENT_UPDATED,
    APPOINTMENT_CANCELLED,
    ADMISSION_AUTOCREATED,
    ADMISSION_DISCHARGED,
    ADMISSION_TRANSFERRED,
    ENCOUNTER_AUTOCREATED,
    ENCOUNTER_UPDATE,
    PRESCRIPTION_CREATED,
    PRESCRIPTION_UPDATED,
    PRESCRIPTION_DISCONTINUED,
    LAB_ORDER_CREATED,
    LAB_RESULT_UPDATED,
    LAB_RESULT_RELEASED,
    IMAGING_ORDER_CREATED,
    IMAGING_RESULT_UPDATED,
    // Disease-programme registries (Tier 2 item 35). Care-management
    // writes, not disclosures: none is whitelisted in DisclosureCategory,
    // so keying them by patient serves the internal audit views without
    // publishing them to the patient-facing disclosure log.
    PROGRAM_ENROLLED,
    PROGRAM_STATUS_CHANGED,
    PROGRAM_VISIT_RECORDED,
    /** The care-gap sweep created a tracing recall - a system write, no human actor. */
    PROGRAM_DEFAULTER_TRACED,

    // Data operations
    ACCESS,
    DATA_ACCESS,
    DATA_UPDATE,
    DATA_EXPORT,
    DATA_DELETE,
    DATA_IMPORT,
    UPDATE,

    // Billing & finance
    INVOICE_CREATED,
    INVOICE_UPDATED,
    PAYMENT_POSTED,
    REFUND_ISSUED,
    CLAIM_SUBMITTED,

    // Pharmacy & inventory
    STOCK_RECEIPT,
    STOCK_ADJUSTMENT,
    STOCK_TRANSFER,
    STOCK_RETURN,
    STOCK_REORDER_ALERT,
    DISPENSE_CREATED,
    DISPENSE_CANCELLED,
    DISPENSE_SUBSTITUTED,
    MEDICATION_DEACTIVATED,
    PHARMACY_DEACTIVATED,
    MTM_REVIEW_STARTED,
    MTM_INTERVENTION_RECORDED,
    PRESCRIPTION_ROUTED_EXTERNAL,
    PRESCRIPTION_SENT_TO_PARTNER,
    PRESCRIPTION_PRINTED,
    PRESCRIPTION_BACKORDER,

    // Security & platform configuration
    SECURITY_POLICY_UPDATED,
    SECURITY_ALERT_TRIGGERED,
    CONFIGURATION_CHANGED,
    API_KEY_CREATED,
    API_KEY_REVOKED,
    INTEGRATION_CONFIGURED,
    PLATFORM_REGISTRY_UPDATED,

    // Tenant lifecycle (MVP-2)
    TENANT_SUSPENDED,
    TENANT_RESTORED,
    TENANT_ARCHIVED,
    TENANT_PURGE_SCHEDULED,
    TENANT_PURGE_CANCELLED,
    TENANT_PURGED,

    // Support impersonation (MVP-4 — gap #4 in docs/super-admin-gaps.md)
    IMPERSONATION_STARTED,
    IMPERSONATION_ENDED,

    // Data residency (MVP-9 — gap #9 in docs/super-admin-gaps.md)
    ORGANIZATION_REGION_UPDATED,

    // Hospital-level lifecycle (MVP-c batch — Hospital lifecycle item)
    HOSPITAL_SUSPENDED,
    HOSPITAL_RESTORED,
    HOSPITAL_ARCHIVED,
    HOSPITAL_PURGE_SCHEDULED,
    HOSPITAL_PURGE_CANCELLED,
    HOSPITAL_PURGED,

    // Plan-tier feature gating (MVP-c batch — MVP-6c plan-tier audit emission)
    PLAN_FEATURE_GATE_BLOCKED,

    // Per-region policy updates (MVP-c batch — MVP-9c)
    REGION_POLICY_UPDATED,

    // Tenant data export packaging (MVP-c batch — MVP-2c follow-up)
    TENANT_PURGE_PACKAGED,
    TENANT_PURGE_PACKAGING_FAILED,

    // Schema-per-tenant cache control (roadmap row 33 follow-on)
    TENANT_SCHEMA_CACHE_INVALIDATED,
    // Schema-per-tenant provisioning (roadmap row 33 follow-on,
    // provisioning script wiring). Past-tense — see naming-convention
    // lesson in the pr-review-response skill.
    TENANT_SCHEMA_PROVISIONED,

    // Panel management / empanelment (Tier 2 item 37). Past-tense per the
    // naming convention above.
    PANEL_ASSIGNED,
    PANEL_ENDED,

    // Release-of-information workflow (Tier 2 item 39b). Past-tense per
    // the naming convention above. Fulfilment ALSO emits PATIENT_EXPORT -
    // that is the row the disclosure accounting shows the patient.
    ROI_REQUESTED,
    ROI_FULFILLED,
    ROI_DENIED,
    ROI_CANCELLED,

    // Third-party access (Tier 2 item 45). Past-tense per the naming
    // convention above. API_KEY_CREATED / API_KEY_REVOKED already existed
    // in the security block (declared with no emitter until now); item 45
    // gives them their first writers and adds the rest. Credential
    // lifecycle events are the trail a key-compromise investigation
    // starts from - every issuance, rotation and revocation gets a row.
    API_KEY_ROTATED,
    WEBHOOK_ENDPOINT_REGISTERED,
    WEBHOOK_ENDPOINT_UPDATED,
    WEBHOOK_ENDPOINT_DISABLED,

    OTHER
}


