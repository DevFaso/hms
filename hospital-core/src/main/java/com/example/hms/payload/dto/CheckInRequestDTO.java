package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request payload for patient check-in (MVP 1).
 * Captures the minimum data required to transition a scheduled appointment
 * to CHECKED_IN and create an ARRIVED encounter.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckInRequestDTO {

    /** The appointment being checked in. Required for scheduled visits; null for walk-ins. */
    private UUID appointmentId;

    /** The patient being checked in. Required for walk-in visits when appointmentId is null. */
    private UUID patientId;

    /** Chief complaint text captured at check-in. */
    private String chiefComplaint;

    /** Co-pay amount collected at check-in (nullable — not all visits have a co-pay). */
    private BigDecimal copayAmount;

    /** Whether the receptionist confirmed patient identity (photo ID, DOB, etc.). */
    @Builder.Default
    private boolean identityConfirmed = false;

    /** Whether insurance eligibility was verified at check-in. */
    @Builder.Default
    private boolean insuranceVerified = false;

    /** Optional notes entered by the receptionist at check-in. */
    private String notes;

    /* ── Consent-to-treat capture (P3 #21). RECORDED, never gating: the
       check-in proceeds either way, but unlike the identity/insurance
       attestations above (which survive only inside an audit-log string),
       consent lands as a queryable clinical.patient_treatment_consents
       row. ─────────────────────────────────────────────────────────────── */

    /** When TRUE, a consent-to-treat record is created for this visit. */
    private Boolean consentObtained;

    /** How the consent was captured; defaults to ELECTRONIC when omitted. */
    private com.example.hms.enums.TreatmentConsentMethod consentMethod;

    /** The name as signed/typed at the desk. */
    private String consentSignedName;
}
