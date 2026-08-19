package com.example.hms.payload.dto.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Provider decision on a medication refill request (approve or reject).")
public class RefillDecisionRequestDTO {

    @Size(max = 1000)
    @Schema(description = "Optional provider notes shown to the patient (e.g. reason for denial).",
            example = "Approved — please pick up at preferred pharmacy by Friday.")
    private String providerNotes;
}
