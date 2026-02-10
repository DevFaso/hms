package com.example.hms.payload.dto.medication;

import com.example.hms.enums.InteractionSeverity;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * DTO for drug interaction information.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DrugInteractionDTO {

    private UUID id;

    // Drugs
    private String drug1Code;
    private String drug1Name;
    private String drug2Code;
    private String drug2Name;

    // Interaction details
    private InteractionSeverity severity;
    private String description;
    private String recommendation;
    private String mechanism;
    private String clinicalEffects;

    // Management
    private boolean requiresAvoidance;
    private boolean requiresDoseAdjustment;
    private boolean requiresMonitoring;
    private String monitoringParameters;
    private Integer monitoringIntervalHours;

    // Source
    private String sourceDatabase;
    private String evidenceLevel;
    private String literatureReferences;

    private boolean active;
}
