package com.example.hms.enums;

/**
 * Go-live D2 (V385): on what authority a version of the medication catalogue was approved or refused, kept on
 * the version so that its history says it. The name is stored in
 * {@code clinical.medication_catalog_versions.decision_basis}: never rename a constant.
 */
public enum MedicationCatalogDecisionBasis {
    /** A pharmacist of the hospital. */
    PHARMACIST,
    /** The doctor the hospital names médecin-chef, deciding dose limits. */
    MEDICAL_LEAD,
    /**
     * The médecin-chef deciding a FORMULARY version because the hospital had no active pharmacist at that
     * moment: the fallback, never the usual path.
     */
    MEDICAL_LEAD_NO_PHARMACIST,
    /** A platform administrator, for an entry of the platform list. */
    PLATFORM_ADMIN
}
