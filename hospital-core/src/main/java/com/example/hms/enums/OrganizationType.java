package com.example.hms.enums;

/**
 * Represents different types of healthcare organizations
 */
public enum OrganizationType {
    HEALTH_SYSTEM("Health System"),
    HEALTHCARE_NETWORK("Healthcare Network"),
    HOSPITAL_CHAIN("Hospital Chain"),
    COMMUNITY_HOSPITAL("Community Hospital"),
    HOSPITAL("Hospital"),
    REGIONAL_NETWORK("Regional Network"),
    AMBULATORY("Ambulatory Network"),
    PEDIATRIC_SYSTEM("Pediatric System"),
    SPECIALTY_NETWORK("Specialty Network"),
    SPECIALTY_GROUP("Specialty Group"),
    GOVERNMENT_AGENCY("Government Agency"),
    PRIVATE_PRACTICE("Private Practice"),
    RESEARCH_INSTITUTION("Research Institution"),
    ACADEMIC_CENTER("Academic Center"),
    ACADEMIC_MEDICAL_CENTER("Academic Medical Center"),
    /** A pharmacy chain grouping provider facilities (D5; one site per facility in v1). */
    PHARMACY("Pharmacy"),
    /** A laboratory group grouping provider facilities (D5; one site per facility in v1). */
    LABORATORY("Laboratory");

    private final String displayName;

    OrganizationType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}