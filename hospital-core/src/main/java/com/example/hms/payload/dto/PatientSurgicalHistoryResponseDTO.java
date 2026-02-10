package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Historical surgical procedure recorded for the patient.")
public class PatientSurgicalHistoryResponseDTO {

    private UUID id;
    private UUID patientId;
    private UUID hospitalId;
    private String hospitalName;
    private String procedureCode;
    private String procedureDisplay;
    private LocalDate procedureDate;
    private String outcome;
    private String performedBy;
    private String location;
    private String sourceSystem;
    private LocalDateTime lastUpdatedAt;
    private String notes;
}
