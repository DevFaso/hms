package com.example.hms.payload.dto.medication;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Response DTO for medication timeline with interactions and overlaps detected.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MedicationTimelineResponseDTO {

    private List<MedicationTimelineEntryDTO> timeline;

    // Summary statistics
    private int totalMedications;
    private int activeMedications;
    private int controlledSubstances;
    private int medicationsWithOverlaps;
    private int medicationsWithInteractions;

    // Detected interactions
    private List<DrugInteractionDTO> detectedInteractions;

    // Polypharmacy assessment
    private boolean polypharmacyDetected; // 5+ concurrent medications
    private Integer concurrentMedicationsCount;

    // Warnings
    private List<String> warnings; // e.g., "Patient has 3 drug-drug interactions"
}
