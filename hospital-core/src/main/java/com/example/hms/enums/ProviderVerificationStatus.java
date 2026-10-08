package com.example.hms.enums;

/**
 * Where a provider facility's onboarding evidence stands (V180).
 *
 * <ul>
 *   <li>{@link #SUBMITTED}: captured by a super-admin, not yet checked. The
 *       facility is SUSPENDED and inactive.</li>
 *   <li>{@link #VERIFIED}: the documents were checked and the RCCM, IFU and
 *       CNSS details agree. The only way a provider becomes ACTIVE.</li>
 *   <li>{@link #REJECTED}: the evidence was refused. The facility stays
 *       SUSPENDED; the evidence may be submitted again.</li>
 *   <li>{@link #REVOKED}: a verification withdrawn later. The facility is
 *       suspended again.</li>
 * </ul>
 */
public enum ProviderVerificationStatus {
    SUBMITTED,
    VERIFIED,
    REJECTED,
    REVOKED
}
