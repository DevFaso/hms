package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Nursing intake submission: allergy reconciliation, medication reconciliation, "
    + "nursing assessment notes, pain assessment, and fall risk detail.")
public class NursingIntakeRequestDTO {

    /**
     * Allergies to add or update. Each entry follows the existing PatientAllergyRequestDTO shape.
     * Omitted entries are left unchanged (this is additive, not a full replace).
     */
    @Valid
    private List<PatientAllergyRequestDTO> allergies;

    /**
     * Medication reconciliation entries. Each entry represents a current medication the patient
     * reports taking. Existing prescriptions are not modified; these are informational records
     * stored as a nursing assessment note.
     */
    @Valid
    private List<MedicationReconciliationEntry> medications;

    /** Free-text nursing assessment notes linked to this encounter. */
    @Size(max = 4000, message = "Nursing assessment notes must not exceed 4000 characters.")
    private String nursingAssessmentNotes;

    /** Detailed chief complaint captured during nursing intake (overrides triage value if non-blank). */
    @Size(max = 2048, message = "Chief complaint must not exceed 2048 characters.")
    private String chiefComplaint;

    /** Pain assessment details (e.g. 0-10 scale, location, quality). */
    @Size(max = 1024, message = "Pain assessment must not exceed 1024 characters.")
    private String painAssessment;

    /** Fall risk detail notes (e.g. Morse Fall Scale result). */
    @Size(max = 1024, message = "Fall risk detail must not exceed 1024 characters.")
    private String fallRiskDetail;

    /**
     * A single medication reconciliation entry reported by the patient.
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Medication the patient reports currently taking.")
    public static class MedicationReconciliationEntry {

        @Size(max = 255, message = "Medication name must not exceed 255 characters.")
        private String medicationName;

        @Size(max = 100, message = "Dosage must not exceed 100 characters.")
        private String dosage;

        @Size(max = 100, message = "Frequency must not exceed 100 characters.")
        private String frequency;

        @Size(max = 50, message = "Route must not exceed 50 characters.")
        private String route;

        /** Whether the patient is still taking this medication. */
        @Builder.Default
        private boolean stillTaking = true;

        @Size(max = 512, message = "Notes must not exceed 512 characters.")
        private String notes;
    }
}
