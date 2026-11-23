package com.example.hms.enums;

/**
 * What the biologist recorded about a patient's red-cell antibody (rule TRANSFUSION_POSITIVE_ANTIBODY_SCREEN).
 * Stored by name in {@code clinical.patient_antibody_workups.status} (V362): never rename a constant.
 */
public enum AntibodyWorkupStatus {
    /** The antibody is identified: its specificity is on record. */
    IDENTIFIED,
    /**
     * Identification is not available here: the sample is referred to a named centre (CNTS/CRTS), and the
     * biologist decided, with a reason, that one request is served before the answer.
     */
    REFERRED
}
