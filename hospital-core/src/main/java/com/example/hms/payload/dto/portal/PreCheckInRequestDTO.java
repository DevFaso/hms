package com.example.hms.payload.dto.portal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for patient pre-check-in via the patient portal.
 * The patient can update demographics, confirm insurance, submit questionnaire
 * responses, and acknowledge consent — all before arriving at the clinic.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PreCheckInRequestDTO {

    @NotNull
    private UUID appointmentId;

    // ── Demographics updates (optional — only override if provided) ──────
    private String phoneNumber;
    private String email;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String zipCode;
    private String country;

    // ── Emergency contact (optional) ─────────────────────────────────────
    private String emergencyContactName;
    private String emergencyContactPhone;
    private String emergencyContactRelationship;

    // ── Insurance (optional) ─────────────────────────────────────────────
    private String insuranceProvider;
    private String insuranceMemberId;
    private String insurancePlan;

    // ── Questionnaire responses ──────────────────────────────────────────
    @Valid
    private List<QuestionnaireSubmissionDTO> questionnaireResponses;

    // ── Consent acknowledgment ───────────────────────────────────────────
    /** Patient acknowledges that submitted info is accurate and consents to care. */
    private Boolean consentAcknowledged;
}
