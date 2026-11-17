package com.example.hms.enums;

/**
 * Status of a hospital admission throughout its lifecycle.
 */
public enum AdmissionStatus {
    /**
     * Admission pending (pre-registration)
     */
    PENDING,
    
    /**
     * Patient admitted and active
     */
    ACTIVE,
    
    /**
     * On leave (temporary absence)
     */
    ON_LEAVE,
    
    /**
     * Awaiting discharge
     */
    AWAITING_DISCHARGE,
    
    /**
     * Discharged - admission complete
     */
    DISCHARGED,
    
    /**
     * Cancelled (admission did not occur)
     */
    CANCELLED,
    
    /**
     * Transferred to another facility/ward
     */
    TRANSFERRED,
    
    /**
     * Patient deceased
     */
    DECEASED,

    /**
     * QA D1516: closed by a record later entered in error (a death recorded on the wrong patient). The patient
     * neither died nor was discharged; the stay ended when the closure ran ({@code actualDischargeDateTime}), its bed
     * was released then, and it is not reopened (#1068): a clinician readmits if care continues. Not a discharge
     * and not a death, so neither count takes it.
     */
    CLOSED_IN_ERROR;

    /**
     * QA D1065 (round 18): the statuses of a stay the patient is still in. An
     * inpatient act (a discharge request, a NEWS2 escalation to the attending,
     * the chart's "current admission") reads this one set.
     */
    public static final java.util.Set<AdmissionStatus> OPEN =
        java.util.Collections.unmodifiableSet(java.util.EnumSet.of(PENDING, ACTIVE, ON_LEAVE, AWAITING_DISCHARGE));
}
