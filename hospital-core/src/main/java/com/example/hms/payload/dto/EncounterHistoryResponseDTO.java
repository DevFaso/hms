package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Historical change entry for a patient encounter.")
public class EncounterHistoryResponseDTO {

    @Schema(description = "Unique identifier for the history entry.")
    private UUID id;

    @Schema(description = "Encounter identifier associated with this history entry.")
    private UUID encounterId;

    @Schema(description = "Timestamp when the change occurred.")
    private LocalDateTime changedAt;

    @Schema(description = "User or system that performed the change.")
    private String changedBy;

    @Schema(description = "Encounter type recorded at the time of change.")
    private String encounterType;

    @Schema(description = "Encounter status at the time of change.")
    private String status;

    @Schema(description = "Encounter date captured in the history entry.")
    private LocalDateTime encounterDate;

    @Schema(description = "Notes recorded for this change.")
    private String notes;

    @Schema(description = "Type of change tracked (e.g., CREATED, UPDATED, DELETED).")
    private String changeType;

    @Schema(description = "Serialized payload describing previous values, when available.")
    private String previousValuesJson;

    @Schema(description = "Serialized payload for additional context captured by the encounter workflow.")
    private String extraFieldsJson;
}
