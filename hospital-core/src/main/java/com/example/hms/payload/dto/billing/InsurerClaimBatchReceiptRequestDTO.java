package com.example.hms.payload.dto.billing;

import com.example.hms.validation.NoCardNumber;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Insurer claims plan, owner decision of 2026-11-26 (Q3): the day the payer acknowledged the bordereau, kept apart
 * from the day it was sent; the payer's delays run from it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerClaimBatchReceiptRequestDTO {

    @NotNull(message = "{billing.claim.receivedOnRequired}")
    private LocalDate receivedOn;

    @Size(max = 120, message = "{billing.claim.referenceTooLong}")
    @NoCardNumber
    private String payerReference;
}
