package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request body for checking out a patient from an encounter (MVP 6).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckOutRequestDTO {

    /** Free-text follow-up / discharge instructions. */
    private String followUpInstructions;

    /** List of discharge diagnosis descriptions or ICD codes. */
    private List<String> dischargeDiagnoses;

    /** Summary of prescriptions issued during the visit. */
    private String prescriptionSummary;

    /** Summary of referrals made. */
    private String referralSummary;

    /** Patient education material references. */
    private String patientEducationMaterials;

    /**
     * Optional — if the provider wants to schedule a follow-up appointment
     * at checkout, this DTO captures the request details.
     */
    private FollowUpAppointmentRequest followUpAppointment;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FollowUpAppointmentRequest {
        /**
         * Optional (QA D426/D321): a follow-up with only a date was refused or
         * dropped. The service files a generic reason when none is given.
         */
        private String reason;
        /** ISO date string (yyyy-MM-dd) for the desired follow-up date. */
        private String preferredDate;
        /** Notes for scheduling staff. */
        private String notes;
    }
}
