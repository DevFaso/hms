package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@Schema(description = "Payload submitted by a doctor when capturing new findings in the patient chart.")
public class DoctorPatientChartUpdateRequestDTO {

    @NotNull
    @Schema(description = "Hospital context for the chart update", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID hospitalId;

    @NotBlank
    @Schema(description = "Why the chart was updated", example = "Post-op day 1 findings")
    private String updateReason;

    @Schema(description = "High level summary displayed alongside version history")
    private String summary;

    @Schema(description = "Whether this update references sensitive content")
    private Boolean includeSensitiveData;

    @Schema(description = "Whether to alert the patient's care team via notifications")
    private Boolean notifyCareTeam;

    @Valid
    @Schema(description = "Structured sections being added to the chart")
    private List<DoctorChartSectionDTO> sections;

    @Valid
    @Schema(description = "Optional supporting documents linked to the update")
    private List<DoctorChartAttachmentDTO> attachments;
}
