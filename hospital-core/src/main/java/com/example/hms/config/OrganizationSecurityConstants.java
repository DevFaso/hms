package com.example.hms.config;

/**
 * Constants for organization-level security policies and rules
 */
public class OrganizationSecurityConstants {

    // Default Organization Security Policy Codes
    public static final String DEFAULT_ACCESS_CONTROL_POLICY = "DEFAULT_ACCESS_CONTROL";
    public static final String DEFAULT_DATA_PROTECTION_POLICY = "DEFAULT_DATA_PROTECTION";
    public static final String DEFAULT_AUDIT_POLICY = "DEFAULT_AUDIT_LOGGING";
    public static final String DEFAULT_PASSWORD_POLICY = "DEFAULT_PASSWORD_POLICY";
    public static final String DEFAULT_SESSION_POLICY = "DEFAULT_SESSION_MANAGEMENT";
    public static final String DEFAULT_ROLE_POLICY = "DEFAULT_ROLE_MANAGEMENT";

    // Default Organization Security Rule Codes
    public static final String PATIENT_DATA_ACCESS_RULE = "PATIENT_DATA_ACCESS_RULE";
    public static final String ADMIN_ENDPOINT_ACCESS_RULE = "ADMIN_ENDPOINT_ACCESS_RULE";
    public static final String DOCTOR_PRESCRIPTION_RULE = "DOCTOR_PRESCRIPTION_RULE";
    public static final String LAB_RESULTS_ACCESS_RULE = "LAB_RESULTS_ACCESS_RULE";
    public static final String BILLING_ACCESS_RULE = "BILLING_ACCESS_RULE";
    public static final String PASSWORD_MIN_LENGTH_RULE = "PASSWORD_MIN_LENGTH_RULE";
    public static final String SESSION_TIMEOUT_RULE = "SESSION_TIMEOUT_RULE";
    public static final String MFA_REQUIREMENT_RULE = "MFA_REQUIREMENT_RULE";
    public static final String AUDIT_SENSITIVE_OPERATIONS_RULE = "AUDIT_SENSITIVE_OPERATIONS_RULE";
    public static final String API_RATE_LIMIT_RULE = "API_RATE_LIMIT_RULE";

    // Default Rule Values
    public static final String DEFAULT_PASSWORD_MIN_LENGTH = "8";
    public static final String DEFAULT_SESSION_TIMEOUT_MINUTES = "480"; // 8 hours
    public static final String DEFAULT_API_RATE_LIMIT = "100/hour";
    public static final String SENSITIVE_ROLES_FOR_MFA = "ROLE_DOCTOR,ROLE_NURSE,ROLE_LAB_SCIENTIST,ROLE_PHARMACIST";

    // Organization Types with Default Security Levels
    public static final String HIGH_SECURITY_ORG_TYPES = "GOVERNMENT_AGENCY,RESEARCH_INSTITUTION";
    public static final String MEDIUM_SECURITY_ORG_TYPES = "HEALTHCARE_NETWORK,HOSPITAL_CHAIN,ACADEMIC_MEDICAL_CENTER";
    public static final String STANDARD_SECURITY_ORG_TYPES = "PRIVATE_PRACTICE";

    // Prevent instantiation
    private OrganizationSecurityConstants() {
        throw new UnsupportedOperationException("Utility class");
    }
}