package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Response payload containing patient consent details.")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientConsentResponseDTO {

    @Schema(description = "Unique ID of the consent record.", example = "9b9d8d60-86d4-47aa-bdd4-d836d67ed77f")
    private UUID id;

    @Schema(description = "Whether the consent is currently active.", example = "true")
    private boolean consentGiven;

    @Schema(description = "Timestamp when the consent was granted.", example = "2025-05-19T08:30:00")
    private LocalDateTime consentTimestamp;

    @Schema(description = "Optional expiration date for the consent.", example = "2025-12-31T23:59:59")
    private LocalDateTime consentExpiration;

    @Schema(description = "Purpose for which the consent was given.", example = "Treatment")
    private String purpose;

    @Schema(description = "ID of the patient associated with the consent.")
    private UUID patientId;

    @Schema(description = "Patient full details")
    private PatientResponseDTO patient;

    @Schema(description = "Source hospital full details")
    private HospitalResponseDTO fromHospital;

    @Schema(description = "Target hospital full details")
    private HospitalResponseDTO toHospital;
}

