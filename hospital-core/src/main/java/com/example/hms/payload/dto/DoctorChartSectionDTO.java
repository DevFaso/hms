package com.example.hms.payload.dto;

import com.example.hms.enums.DoctorChartSectionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Structured entry captured inside a doctor-authored chart update.")
public class DoctorChartSectionDTO {

    @NotNull
    @Schema(description = "Categorization for the section", requiredMode = Schema.RequiredMode.REQUIRED)
    private DoctorChartSectionType sectionType;

    @Schema(description = "Clinical code associated with the entry (ICD/SNOMED/etc)")
    private String code;

    @Schema(description = "Display text for the associated clinical code")
    private String display;

    @Schema(description = "Narrative description of the update")
    private String narrative;

    @Schema(description = "Status or disposition (e.g. ACTIVE, RESOLVED)")
    private String status;

    @Schema(description = "Severity classification for allergies/problems")
    private String severity;

    @Schema(description = "Source system for the data, when not authored directly")
    private String sourceSystem;

    @Schema(description = "Clinical date associated with the entry")
    private LocalDate occurredOn;

    @Schema(description = "Reference to an existing resource that was updated")
    private UUID linkedResourceId;

    @Schema(description = "Whether the section contains sensitive information")
    private Boolean sensitive;

    @Schema(description = "Optional author notes separate from the narrative")
    private String authorNotes;

    @Schema(description = "Preserves ordering when returning sections to clients")
    private Integer orderIndex;

    @Schema(description = "Structured detail payload for specialized section types")
    private Map<String, Object> details;
}
