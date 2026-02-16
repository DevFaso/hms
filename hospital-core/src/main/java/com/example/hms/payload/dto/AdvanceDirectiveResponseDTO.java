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
@Schema(description = "Advance directive metadata associated with the patient consent bundle.")
public class AdvanceDirectiveResponseDTO {

    private UUID id;
    private UUID patientId;
    private UUID hospitalId;
    private String hospitalName;
    private String directiveType;
    private String status;
    private String description;
    private LocalDate effectiveDate;
    private LocalDate expirationDate;
    private String witnessName;
    private String physicianName;
    private String documentLocation;
    private String sourceSystem;
    private LocalDateTime lastReviewedAt;
}
