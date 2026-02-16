package com.example.hms.payload.dto.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Patient request to grant data-sharing consent (patientId resolved from JWT)")
public class PortalConsentRequestDTO {

    @NotNull
    @Schema(description = "Hospital currently holding the records (source)", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID fromHospitalId;

    @NotNull
    @Schema(description = "Hospital receiving the shared records (target)", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID toHospitalId;

    @Schema(description = "Optional expiration date for the consent")
    private LocalDateTime consentExpiration;

    @Size(max = 200)
    @Schema(description = "Purpose of the consent", example = "Treatment")
    private String purpose;
}
