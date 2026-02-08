package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Envelope returned to doctors when requesting the longitudinal patient record timeline.")
public class PatientTimelineResponseDTO {

    @Schema(description = "Patient identifier for the timeline payload.")
    private UUID patientId;

    @Schema(description = "Hospital context resolved for the query.")
    private UUID hospitalId;

    @Schema(description = "Patient full name (display only).")
    private String patientName;

    @Schema(description = "Patient date of birth for quick verification.")
    private LocalDate dateOfBirth;

    @Schema(description = "Echoes the doctor's access reason for auditing in the UI.")
    private String accessReason;

    @Builder.Default
    @Schema(description = "Ordered list of events that make up the timeline.")
    private List<PatientTimelineEntryDTO> entries = Collections.emptyList();

    @Builder.Default
    @Schema(description = "Distinct categories that contained sensitive events in this response.")
    private List<String> sensitiveCategories = Collections.emptyList();

    @Schema(description = "Indicates that at least one sensitive event was included in the payload.")
    private boolean containsSensitiveData;

    @Schema(description = "Total number of entries after applying filters and limits.")
    private int totalEntries;

    @Schema(description = "Timestamp when the server assembled the response.")
    private LocalDateTime generatedAt;
}
