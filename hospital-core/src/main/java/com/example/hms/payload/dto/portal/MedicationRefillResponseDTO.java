package com.example.hms.payload.dto.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Medication refill request status")
public class MedicationRefillResponseDTO {

    @Schema(description = "Refill request ID")
    private UUID id;

    @Schema(description = "Prescription ID")
    private UUID prescriptionId;

    @Schema(description = "Medication name")
    private String medicationName;

    @Schema(description = "Patient ID")
    private UUID patientId;

    /**
     * QA D1276 (round 27): the refill queue named only the drug, so three
     * identical "Paracétamol" cards could not be told apart.
     */
    @Schema(description = "Patient display name")
    private String patientName;

    @Schema(description = "Patient MRN at the hospital of the prescription (never another hospital's)")
    private String patientMrn;

    @Schema(description = "Current status: REQUESTED, APPROVED, DENIED, DISPENSED")
    private String status;

    @Schema(description = "Preferred pharmacy")
    private String preferredPharmacy;

    @Schema(description = "Patient notes")
    private String notes;

    @Schema(description = "Provider response notes")
    private String providerNotes;

    @Schema(description = "When the request was submitted")
    private LocalDateTime requestedAt;

    @Schema(description = "When the request was last updated")
    private LocalDateTime updatedAt;

    /** Policy rule 1 (QA D2308): the pharmacist's flags for the prescriber, or the reason of a refusal to dispense. */
    @Schema(description = "Pharmacist review note (flags, or the reason of a refusal to dispense)")
    private String pharmacistNote;

    @Schema(description = "When the pharmacist reviewed the request")
    private LocalDateTime pharmacistReviewedAt;

    @Schema(description = "On what authority it was decided: PRESCRIBER_DECISION, ORIGINAL_PRESCRIPTION, PHARMACIST_REFUSAL")
    private String decisionBasis;

    /**
     * Policy rule 1: the prescriber already wrote a refill that is not used yet, so the pharmacy may release this
     * request as ordinary dispensing. False: only a prescriber can approve it.
     */
    @Schema(description = "Whether a refill written on the prescription is still unused")
    private boolean writtenRefillLeft;

    /**
     * Policy rule 1: the answer is for a pharmacist (not also a prescriber). The queue then shows the steps of
     * the pharmacy (review, refuse to dispense, release a written refill) and never a decision.
     */
    @Schema(description = "Whether the reader acts for the pharmacy: review, refuse, release; never approve")
    private boolean pharmacyView;
}
