package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response payload representing a single patient chart update entry.")
public class PatientChartUpdateResponseDTO {

    private UUID id;
    private UUID patientId;
    private UUID hospitalId;
    private Integer versionNumber;
    private String updateReason;
    private String summary;
    private boolean includeSensitive;
    private boolean notifyCareTeam;
    private Integer sectionCount;
    private Integer attachmentCount;

    @Schema(description = "Timestamp when the update was recorded")
    private LocalDateTime recordedAt;

    private UUID recordedByStaffId;
    private String recordedByName;
    private String recordedByRole;

    @ArraySchema(schema = @Schema(implementation = PatientChartSectionEntryResponseDTO.class))
    private List<PatientChartSectionEntryResponseDTO> sections;

    @ArraySchema(schema = @Schema(implementation = PatientChartAttachmentResponseDTO.class))
    private List<PatientChartAttachmentResponseDTO> attachments;
}
