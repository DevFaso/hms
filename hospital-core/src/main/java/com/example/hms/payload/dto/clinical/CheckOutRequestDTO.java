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

    /**
     * The discharge diagnosis the summary stores is the lines joined by "; ",
     * in a column of this length (QA D1671: a longer one was refused at
     * persist with bean validation's raw English).
     */
    public static final int DIAGNOSES_MAX = 5000;

    /** The follow-up reason and notes are kept on the recall, whose columns hold 500 (QA D1670). */
    public static final int FOLLOW_UP_TEXT_MAX = 500;

    /** Free-text follow-up / discharge instructions (the summary's column holds 3000, QA D1671). */
    @jakarta.validation.constraints.Size(max = 3000, message = "{checkout.followUpInstructions.size}")
    private String followUpInstructions;

    /** List of discharge diagnosis descriptions or ICD codes. */
    private List<@jakarta.validation.constraints.Size(max = DIAGNOSES_MAX,
        message = "{checkout.dischargeDiagnoses.size}") String> dischargeDiagnoses;

    /** Summary of prescriptions issued during the visit. */
    @jakarta.validation.constraints.Size(max = 3000, message = "{checkout.prescriptionSummary.size}")
    private String prescriptionSummary;

    /** Summary of referrals made. */
    @jakarta.validation.constraints.Size(max = 3000, message = "{checkout.referralSummary.size}")
    private String referralSummary;

    /** Patient education material references. */
    @jakarta.validation.constraints.Size(max = 2000, message = "{checkout.patientEducationMaterials.size}")
    private String patientEducationMaterials;

    /**
     * QA D1671: the lines are stored joined by "; ", so their sum is held to
     * the column too, and refused naming the field rather than at persist.
     */
    @jakarta.validation.constraints.AssertTrue(message = "{checkout.dischargeDiagnoses.size}")
    @com.example.hms.validation.ReportedOn("dischargeDiagnoses")
    @com.fasterxml.jackson.annotation.JsonIgnore
    @io.swagger.v3.oas.annotations.Hidden
    public boolean isDischargeDiagnosesWithinLimit() {
        if (dischargeDiagnoses == null) {
            return true;
        }
        int joined = 0;
        int lines = 0;
        for (String line : dischargeDiagnoses) {
            if (line != null && !line.isBlank()) {
                joined += line.trim().length();
                lines++;
            }
        }
        return joined + Math.max(0, lines - 1) * 2 <= DIAGNOSES_MAX;
    }

    /**
     * Optional — if the provider wants to schedule a follow-up appointment
     * at checkout, this DTO captures the request details.
     */
    @jakarta.validation.Valid
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
        @jakarta.validation.constraints.Size(max = FOLLOW_UP_TEXT_MAX, message = "{checkout.followUpReason.size}")
        private String reason;
        /** ISO date string (yyyy-MM-dd) for the desired follow-up date. */
        private String preferredDate;
        /** Notes for scheduling staff. */
        @jakarta.validation.constraints.Size(max = FOLLOW_UP_TEXT_MAX, message = "{checkout.followUpNotes.size}")
        private String notes;
    }
}
