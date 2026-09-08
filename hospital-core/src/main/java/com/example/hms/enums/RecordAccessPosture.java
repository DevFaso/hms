package com.example.hms.enums;

/**
 * How a hospital lets clinicians at <em>other</em> hospitals read the charts
 * of patients it has treated (E8 #52).
 *
 * <p>This is configuration, not product behaviour, on purpose: two Epic sites
 * run different consent models because the posture is a per-organisation
 * setting. It is also the legal escape hatch — if counsel or the CIL rule that
 * treatment-purpose access without patient authorisation is not lawful in a
 * jurisdiction, that hospital flips to {@link #EXPLICIT_CONSENT} and nothing
 * else changes.
 */
public enum RecordAccessPosture {

    /**
     * The record follows the treatment relationship. A clinician with a live
     * relationship to the patient (see {@code TreatmentRelationshipResolver})
     * may read the chart across hospitals without a patient-signed grant.
     * Sensitive categories are still withheld (E8 #51) and every read is
     * accounted for (E8 #53).
     */
    TREATMENT_PRESUMED,

    /**
     * Cross-hospital reads require an explicit, patient-granted consent
     * (the existing {@code PatientRecordSharing} path). The treatment
     * relationship alone is not enough.
     */
    EXPLICIT_CONSENT
}
