package com.example.hms.payload.dto.medication;

import com.example.hms.enums.InteractionSeverity;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * DTO for drug interaction information. Doubles as the admin write payload —
 * the constraints below back the controller's {@code @Valid} (which was a
 * no-op until 2026-08-22: the DTO carried no annotations, so a missing drug
 * name surfaced as a persistence error instead of a clean 400).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DrugInteractionDTO {

    private UUID id;

    // Drugs. Codes must be RxNorm RxCUIs — the CDS-Hooks layer joins on
    // rxnormCode with exact equality, so a free-text "code" produces a KB row
    // that never fires there.
    @NotBlank(message = "Drug 1 code is required.")
    @Size(max = 100)
    private String drug1Code;

    @NotBlank(message = "Drug 1 name is required.")
    @Size(max = 255)
    private String drug1Name;

    @NotBlank(message = "Drug 2 code is required.")
    @Size(max = 100)
    private String drug2Code;

    @NotBlank(message = "Drug 2 name is required.")
    @Size(max = 255)
    private String drug2Name;

    // Interaction details
    @NotNull(message = "Severity is required.")
    private InteractionSeverity severity;

    private String description;

    @NotBlank(message = "A recommendation is required — an alert with no action is an alert that gets ignored.")
    private String recommendation;

    @Size(max = 500)
    private String mechanism;

    @Size(max = 500)
    private String clinicalEffects;

    // Management
    private boolean requiresAvoidance;
    private boolean requiresDoseAdjustment;
    private boolean requiresMonitoring;
    private String monitoringParameters;
    private Integer monitoringIntervalHours;

    // Source
    @Size(max = 100)
    private String sourceDatabase;

    @Size(max = 100)
    private String evidenceLevel;

    @Size(max = 1000)
    private String literatureReferences;

    /** Curation note: why the row was added, corrected or retired. */
    @Size(max = 1000)
    private String notes;

    private boolean active;
}
