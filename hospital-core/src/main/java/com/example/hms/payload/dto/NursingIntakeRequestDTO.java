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
    @Size(max = 4000, message = "{nursingIntake.nursingAssessmentNotes.size}")
    private String nursingAssessmentNotes;

    /** Detailed chief complaint captured during nursing intake (overrides triage value if non-blank). */
    @Size(max = 2048, message = "{nursingIntake.chiefComplaint.size}")
    private String chiefComplaint;

    /** Pain assessment details (e.g. 0-10 scale, location, quality). */
    @Size(max = 1024, message = "{nursingIntake.painAssessment.size}")
    private String painAssessment;

    /** Fall risk detail notes (e.g. Morse Fall Scale result). */
    @Size(max = 1024, message = "{nursingIntake.fallRiskDetail.size}")
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

        @Size(max = 255, message = "{nursingIntake.medication.medicationName.size}")
        private String medicationName;

        @Size(max = 100, message = "{nursingIntake.medication.dosage.size}")
        private String dosage;

        @Size(max = 100, message = "{nursingIntake.medication.frequency.size}")
        private String frequency;

        @Size(max = 50, message = "{nursingIntake.medication.route.size}")
        private String route;

        /** Whether the patient is still taking this medication. */
        @Builder.Default
        private boolean stillTaking = true;

        @Size(max = 512, message = "{nursingIntake.medication.notes.size}")
        private String notes;
    }
}
