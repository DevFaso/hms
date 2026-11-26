package com.example.hms.enums;

/**
 * Go-live D2 (V385): where a version of a medication catalogue entry stands. The name is stored in
 * {@code clinical.medication_catalog_versions.status}: never rename a constant.
 */
public enum MedicationCatalogVersionStatus {
    /** Proposed, « en attente de validation »: nothing it carries is in force. */
    PENDING_APPROVAL,
    /** Approved by a second person: the version in force. */
    APPROVED,
    /** Refused, with the reason. It never took effect. */
    REJECTED,
    /** Once in force, replaced by a later approved version. */
    SUPERSEDED,
    /** Taken back by its author before a decision. It never took effect. */
    WITHDRAWN
}
