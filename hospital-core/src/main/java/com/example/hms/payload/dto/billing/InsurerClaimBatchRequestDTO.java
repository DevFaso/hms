package com.example.hms.payload.dto.billing;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Insurer claims plan PR 2 (US-5): a new draft bordereau for one payer and one period. The server proposes every open
 * share of that payer whose service date (the discharge date for a hospital stay) is in the period.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerClaimBatchRequestDTO {

    @NotNull(message = "{billing.claim.payerRequired}")
    private UUID payerId;

    @NotNull(message = "{billing.claim.periodRequired}")
    private LocalDate periodFrom;

    @NotNull(message = "{billing.claim.periodRequired}")
    private LocalDate periodTo;

    /** A key the client keeps across retries: the same key answers with the draft already made. */
    private UUID clientRequestId;
}
