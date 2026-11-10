package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.RoutingType;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoutingDecisionRequestDTO {

    @NotNull(message = "{routingDecision.prescriptionId.required}")
    private UUID prescriptionId;

    @NotNull(message = "{routingDecision.routingType.required}")
    private RoutingType routingType;

    private UUID targetPharmacyId;

    @Size(max = 1024, message = "{routingDecision.reason.size}")
    private String reason;

    private LocalDate estimatedRestockDate;

    /**
     * QA D1563 / D1555: the reason for going ahead although the patient's current allergies stop this
     * drug, after reading the allergy card ({@code CurrentAllergyRecheck}). Required only then; kept
     * on the record and audited.
     */
    @Size(max = 500)
    private String allergyAcknowledgementReason;

    /** QA D1563 / D1555: the token the allergy card carried ({@code exceptionKey}): which allergy was acknowledged. */
    @Size(max = 200)
    private String allergyAcknowledgementToken;
}
