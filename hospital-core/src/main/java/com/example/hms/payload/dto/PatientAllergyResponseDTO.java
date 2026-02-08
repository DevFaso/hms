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
@Schema(description = "Structured allergy entry for patient record sharing.")
public class PatientAllergyResponseDTO {

    private UUID id;
    private UUID patientId;
    private UUID hospitalId;
    private String hospitalName;
    private String allergenDisplay;
    private String allergenCode;
    private String category;
    private String severity;
    private String verificationStatus;
    private String reaction;
    private String reactionNotes;
    private LocalDate onsetDate;
    private LocalDate lastOccurrenceDate;
    private LocalDate recordedDate;
    private Boolean active;
    private String recordedBy;
    private String sourceSystem;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
