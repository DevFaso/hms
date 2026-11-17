package com.example.hms.payload.dto.billing;

import com.example.hms.validation.NoCardNumber;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Billing-money plan PR 8: why a refund is rejected or cancelled (required there; checked by the service so the
 * field is named), or an optional note on an approval. Never a card number.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceRefundReasonRequestDTO {

    @Size(max = 500, message = "{billing.refund.reasonTooLong}")
    @NoCardNumber
    private String reason;
}
