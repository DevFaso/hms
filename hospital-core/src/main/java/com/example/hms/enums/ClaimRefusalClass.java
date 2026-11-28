package com.example.hms.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Insurer claims plan, owner decision of 2026-11-26 (Q12): why a payer refused a claimed amount, as a class that
 * decides what the hospital may do with it.
 *
 * <ul>
 *   <li>{@link #PATIENT_SIDE}: the act is not covered, the ceiling is used up, the rights are not valid. The only
 *       class that can be re-billed to the patient, and only as a deliberate act of the hospital admin or the
 *       accountant.</li>
 *   <li>{@link #PROVIDER_FAULT}: a missing authorisation or document, a coding error, a late file. Sent again or
 *       written off; never the patient's debt.</li>
 *   <li>{@link #TARIFF_REDUCTION}: billed above the agreed tariff. A contractual adjustment; never the patient's
 *       debt.</li>
 *   <li>{@link #DISPUTED}: the hospital contests the refusal, or sends the file again.</li>
 * </ul>
 */
public enum ClaimRefusalClass {
    PATIENT_SIDE(EnumSet.of(ClaimRefusalResolution.REBILL_PATIENT, ClaimRefusalResolution.WRITE_OFF_REQUEST,
        ClaimRefusalResolution.RESUBMIT)),
    PROVIDER_FAULT(EnumSet.of(ClaimRefusalResolution.RESUBMIT, ClaimRefusalResolution.WRITE_OFF_REQUEST)),
    TARIFF_REDUCTION(EnumSet.of(ClaimRefusalResolution.WRITE_OFF_REQUEST)),
    DISPUTED(EnumSet.of(ClaimRefusalResolution.CONTEST, ClaimRefusalResolution.RESUBMIT));

    private final Set<ClaimRefusalResolution> resolutions;

    ClaimRefusalClass(Set<ClaimRefusalResolution> resolutions) {
        this.resolutions = resolutions;
    }

    /** Whether a refusal of this class may be resolved that way. */
    public boolean allows(ClaimRefusalResolution resolution) {
        return resolution != null && resolutions.contains(resolution);
    }
}
