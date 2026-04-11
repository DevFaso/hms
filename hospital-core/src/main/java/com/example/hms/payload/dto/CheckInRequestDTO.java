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
}
