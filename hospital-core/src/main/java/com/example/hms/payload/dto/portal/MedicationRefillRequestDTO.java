package com.example.hms.payload.dto.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Patient request to refill an existing prescription")
public class MedicationRefillRequestDTO {

    @NotNull
    @Schema(description = "ID of the prescription to refill", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID prescriptionId;

    @Size(max = 500)
    @Schema(description = "Optional pharmacy or pickup preference", example = "CVS Pharmacy, 123 Main St")
    private String preferredPharmacy;

    @Size(max = 1000)
    @Schema(description = "Additional notes for the provider", example = "Running low, need refill within 3 days")
    private String notes;
}
