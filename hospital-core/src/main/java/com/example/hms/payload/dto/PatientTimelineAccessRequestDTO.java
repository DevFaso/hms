package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Doctor timeline access parameters including reason capture and sensitivity toggles.")
public class PatientTimelineAccessRequestDTO {

    @Schema(description = "Hospital context override. Optional when the JWT already carries a hospital scope.")
    private UUID hospitalId;

    @NotBlank
    @Size(max = 500)
    @Schema(description = "Reason for accessing the patient's longitudinal record.", example = "Pre-op review of vitals")
    private String accessReason;

    @Schema(description = "Entries per page (1-200). Defaults to 50 when not provided.", example = "75")
    private Integer maxEvents;

    /**
     * QA D1380 (D1347 class): the 0-based page of {@code maxEvents} entries.
     * The timeline used to answer only the newest 50 and call that the total.
     */
    @Schema(description = "0-based page of maxEvents entries; totalEntries counts every entry. Defaults to 0.", example = "0")
    private Integer page;

    @Schema(description = "Whether the doctor explicitly requested sensitive data segments.", defaultValue = "false")
    private Boolean includeSensitiveData;

    @Schema(description = "Optional category filters (e.g., ENCOUNTER, LAB_RESULT, PRESCRIPTION).")
    private List<String> categories;
}
