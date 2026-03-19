package com.example.hms.enums;

public enum LabSpecimenStatus {
    /** Specimen record created but not yet physically collected. */
    PENDING,
    /** Specimen collected from the patient. */
    COLLECTED,
    /** Specimen in transit to the laboratory. */
    IN_TRANSIT,
    /** Specimen received at the laboratory. */
    RECEIVED,
    /** Specimen currently being processed / analysed. */
    PROCESSING,
    /** Analysis completed; results recorded. */
    COMPLETED,
    /** Specimen rejected (e.g. haemolysed, wrong tube, insufficient volume). */
    REJECTED
}
