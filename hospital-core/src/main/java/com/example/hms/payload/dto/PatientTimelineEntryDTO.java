package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Single clinical event rendered inside the doctor timeline view.")
public class PatientTimelineEntryDTO {

    @Schema(description = "Stable identifier for the timeline entry (derived from the underlying resource).")
    private String entryId;

    @Schema(description = "Category of the event (ENCOUNTER, PRESCRIPTION, LAB_RESULT, ALLERGY).", example = "ENCOUNTER")
    private String category;

    @Schema(description = "When the event occurred.")
    private LocalDateTime occurredAt;

    @Schema(description = "Human readable summary suitable for list rendering.")
    private String summary;

    @Schema(description = "Indicates whether the event should be gated behind a sensitive data acknowledgment.")
    private boolean sensitive;

    @Builder.Default
    @Schema(description = "Structured metadata for the frontend (status, clinician, severity, etc.).")
    private Map<String, Object> metadata = Collections.emptyMap();
}
