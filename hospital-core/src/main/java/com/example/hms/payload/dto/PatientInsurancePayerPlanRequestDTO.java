package com.example.hms.payload.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Billing-money plan PR 6: the payer plan ("formule") a patient's coverage is billed under; null removes the link.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientInsurancePayerPlanRequestDTO {

    @Schema(description = "A payer plan of the acting hospital; null removes the link")
    private UUID payerPlanId;
}
