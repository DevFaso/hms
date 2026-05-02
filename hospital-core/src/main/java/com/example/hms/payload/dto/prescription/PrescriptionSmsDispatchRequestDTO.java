package com.example.hms.payload.dto.prescription;

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
@Schema(description = "Request to dispatch a prescription summary by SMS to a community pharmacy.")
public class PrescriptionSmsDispatchRequestDTO {

    @NotNull
    @Schema(description = "Target pharmacy id (must be of type COMMUNITY_PHARMACY or PARTNER_PHARMACY at the same hospital).",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID pharmacyId;

    @Size(max = 500)
    @Schema(description = "Optional clinician note appended to the SMS body.")
    private String note;
}
