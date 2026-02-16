package com.example.hms.config;

/**
 * Constants for security configuration and role-based authorization
 */
public class SecurityConstants {

    // Role constants
    public static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    public static final String ROLE_HOSPITAL_ADMIN = "ROLE_HOSPITAL_ADMIN";
    public static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    public static final String ROLE_NURSE = "ROLE_NURSE";
    public static final String ROLE_PATIENT = "ROLE_PATIENT";
    public static final String ROLE_RECEPTIONIST = "ROLE_RECEPTIONIST";
    public static final String ROLE_STAFF = "ROLE_STAFF";
    public static final String ROLE_LAB_SCIENTIST = "ROLE_LAB_SCIENTIST";
    public static final String ROLE_PHARMACIST = "ROLE_PHARMACIST";
    public static final String ROLE_LAB_TECHNICIAN = "ROLE_LAB_TECHNICIAN";
    public static final String ROLE_ACCOUNTANT = "ROLE_ACCOUNTANT";
    public static final String ROLE_RADIOLOGIST = "ROLE_RADIOLOGIST";
    public static final String ROLE_SURGEON = "ROLE_SURGEON";
    public static final String ROLE_DENTIST = "ROLE_DENTIST";
    public static final String ROLE_MIDWIFE = "ROLE_MIDWIFE";
    public static final String ROLE_ADMINISTRATIVE_STAFF = "ROLE_ADMINISTRATIVE_STAFF";
    public static final String ROLE_SUPPORT_STAFF = "ROLE_SUPPORT_STAFF";
    public static final String ROLE_IT_STAFF = "ROLE_IT_STAFF";

    // JWT constants
    public static final String TOKEN_PREFIX = "Bearer ";
    public static final String HEADER_STRING = "Authorization";

    // Tenant context claim keys
    public static final String CLAIM_PRIMARY_ORGANIZATION_ID = "primaryOrganizationId";
    public static final String CLAIM_PRIMARY_HOSPITAL_ID = "primaryHospitalId";
    public static final String CLAIM_PERMITTED_ORGANIZATION_IDS = "organizationIds";
    public static final String CLAIM_PERMITTED_HOSPITAL_IDS = "hospitalIds";
    public static final String CLAIM_PERMITTED_DEPARTMENT_IDS = "departmentIds";
    public static final String CLAIM_IS_SUPER_ADMIN = "isSuperAdmin";
    public static final String CLAIM_IS_HOSPITAL_ADMIN = "isHospitalAdmin";

    // Prevent instantiation
    private SecurityConstants() {
        throw new UnsupportedOperationException("Utility class");
    }
}
