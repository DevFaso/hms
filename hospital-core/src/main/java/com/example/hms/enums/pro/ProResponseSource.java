package com.example.hms.enums.pro;

/**
 * Who answered the instrument. Mirrors the {@code PATIENT_REPORTED} vitals
 * source: a self-report from the patient portal is the same record, tagged.
 */
public enum ProResponseSource {
    /** A clinician read the items to the patient and recorded the answers. */
    STAFF_ADMINISTERED,
    /** The patient answered on the patient portal. */
    PATIENT_REPORTED
}
