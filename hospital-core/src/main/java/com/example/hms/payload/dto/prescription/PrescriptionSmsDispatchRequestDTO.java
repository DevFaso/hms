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

    /**
     * QA D1563 / D1555: the reason for going ahead although the patient's current allergies stop this
     * drug, after reading the allergy card ({@code CurrentAllergyRecheck}). Required only then; kept
     * on the record and audited.
     */
    @jakarta.validation.constraints.Size(max = 500)
    private String allergyAcknowledgementReason;

    /** QA D1563 / D1555: the token the allergy card carried ({@code exceptionKey}): which allergy was acknowledged. */
    @jakarta.validation.constraints.Size(max = 200)
    private String allergyAcknowledgementToken;
}
