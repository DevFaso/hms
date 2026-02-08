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
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Clinical problem entry associated with the patient.")
public class PatientProblemResponseDTO {

    private UUID id;
    private UUID patientId;
    private UUID hospitalId;
    private String hospitalName;
    private String problemCode;
    private String problemDisplay;
    private String icdVersion;
    private String status;
    private String severity;
    private LocalDate onsetDate;
    private LocalDate resolvedDate;
    private LocalDateTime lastReviewedAt;
    private String recordedBy;
    private String sourceSystem;
    private String notes;
    private String supportingEvidence;
    private String statusChangeReason;
    private Boolean chronic;
    private List<String> diagnosisCodes;
}
