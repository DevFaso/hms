package com.example.hms.payload.dto.lab;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Simplified lab result entry for patient dashboards.")
public class PatientLabResultResponseDTO {

    private UUID id;
    private String testName;
    private String testCode;
    private String value;
    private String unit;
    private String referenceRange;
    private String status;
    private LocalDateTime collectedAt;
    private LocalDateTime resultedAt;
    private String orderedBy;
    private String performedBy;
    private String category;
    private String notes;
}
