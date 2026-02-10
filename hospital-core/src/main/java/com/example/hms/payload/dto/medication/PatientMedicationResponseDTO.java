package com.example.hms.payload.dto.medication;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Simplified medication entry for patient dashboards.")
public class PatientMedicationResponseDTO {

    private UUID id;
    private String medicationName;
    private String dosage;
    private String frequency;
    private String route;
    private String status;
    private LocalDate startDate;
    private LocalDate endDate;
    private String prescribedBy;
    private String indication;
    private String instructions;
}
