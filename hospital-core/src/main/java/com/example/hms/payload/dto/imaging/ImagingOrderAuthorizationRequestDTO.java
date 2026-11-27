package com.example.hms.payload.dto.imaging;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Provisional rule 1 (2026-11-15): the pre-authorisation number obtained
 * after an urgent study. Recording it clears « Préautorisation en attente »
 * and closes the billing follow-up task, at any status the study reached.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Records the pre-authorisation number of an imaging order")
public class ImagingOrderAuthorizationRequestDTO {

    @NotBlank
    @Size(max = 120)
    @com.example.hms.validation.NoCardNumber
    @Schema(description = "The pre-authorisation number", requiredMode = Schema.RequiredMode.REQUIRED)
    private String authorizationNumber;
}
