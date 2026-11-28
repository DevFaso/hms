package com.example.hms.payload.dto.billing;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Insurer claims plan PR 3 (US-11): the part of a payer's remittance put on one bordereau line. Whole francs, more
 * than 0, at most what the line still expects.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerPaymentAllocationRequestDTO {

    @NotNull(message = "{billing.insurerPayment.lineRequired}")
    private UUID claimLineId;

    @NotNull(message = "{billing.claim.amountRequired}")
    private BigDecimal amount;
}
