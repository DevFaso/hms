package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * QA D1105: why billing reverses a discount, a waiver or a credit note (a
 * mistaken waiver on an invoice whose check-in copay was folded in). The
 * reason is mandatory, as it is for the adjustment itself (D974) and for a
 * payment reversal (D142).
 */
public record AdjustmentReversalRequestDTO(
    /** Over 500 characters is refused by the service, with the limit in the message. */
    @NotBlank(message = "{billing.adjustment.reversalReasonRequired}")
    String reason
) {
}
