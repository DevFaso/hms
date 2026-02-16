package com.example.hms.enums;

public enum Role {
    ROLE_SUPER_ADMIN,            // Global admin with full authority
    ROLE_HOSPITAL_ADMIN,         // Admin scoped to one hospital
    ROLE_DOCTOR,                 // General doctor
    ROLE_PHYSICIAN,              // Physician (MD/DO)
    ROLE_NURSE,                  // General nurse
    ROLE_NURSE_PRACTITIONER,     // Nurse practitioner with ordering privileges
    ROLE_SURGEON,                // Surgeon
    ROLE_PHARMACIST,             // Pharmacist
    ROLE_LAB_TECHNICIAN,         // Laboratory technician
    ROLE_RADIOLOGIST,            // Radiologist
    ROLE_RECEPTIONIST,           // Reception/front desk
    ROLE_PATIENT,                // Patient
    ROLE_VISITOR,                // Visitor with limited access
    ROLE_IT_SUPPORT,             // IT support staff
    ROLE_CLEANING_STAFF,         // Cleaning and sanitation staff
    ROLE_SECURITY,               // Security personnel
    ROLE_BILLING_SPECIALIST,     // Billing and insurance
    ROLE_HUMAN_RESOURCES,        // HR staff
    ROLE_ADMINISTRATIVE_ASSISTANT, // Administrative assistant
    ROLE_UNKNOWN                 // Undefined or unrecognized role
}

