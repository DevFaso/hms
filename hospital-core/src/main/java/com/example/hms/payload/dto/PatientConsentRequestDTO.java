package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request payload for granting or updating patient consent to share records.")
public class PatientConsentRequestDTO {

    @NotNull
    @Schema(description = "ID of the patient providing consent.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID patientId;

    @NotNull
    @Schema(description = "ID of the hospital currently holding the records (source hospital).",
            example = "05724583-4e82-4d29-a046-e4bf9e77d885", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID fromHospitalId;

    @NotNull
    @Schema(description = "ID of the hospital receiving the shared records (target hospital).",
            example = "f1e5cee8-4b0a-49d2-92a5-cbd5cba40f9e", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID toHospitalId;

    @Schema(description = "Optional expiration date for the consent. If not provided, consent remains valid indefinitely.",
            example = "2025-12-31T23:59:59")
    private LocalDateTime consentExpiration;

    @Schema(description = "Purpose of the consent. E.g., Treatment, Research, Billing.",
            example = "Treatment")
    private String purpose;
}
