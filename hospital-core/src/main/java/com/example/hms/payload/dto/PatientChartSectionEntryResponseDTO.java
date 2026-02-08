package com.example.hms.payload.dto;

import com.example.hms.enums.DoctorChartSectionType;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Structured section returned as part of a patient chart update history entry.")
public class PatientChartSectionEntryResponseDTO {

    @Schema(description = "Synthetic identifier for the section entry to help with UI rendering.")
    private String id;

    @Schema(description = "Position of the section inside the chart update, starting at 0.")
    private Integer orderIndex;

    @Schema(description = "Categorization for the section")
    private DoctorChartSectionType sectionType;

    private String code;
    private String display;
    private String narrative;
    private String status;
    private String severity;
    private String sourceSystem;
    private LocalDate occurredOn;
    private UUID linkedResourceId;
    private Boolean sensitive;
    private String authorNotes;
    private Map<String, Object> details;
}
