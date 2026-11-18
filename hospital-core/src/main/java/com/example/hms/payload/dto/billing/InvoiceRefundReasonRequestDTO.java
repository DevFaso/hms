package com.example.hms.payload.dto.billing;

import com.example.hms.validation.NoCardNumber;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Billing-money plan PR 8: why a refund is rejected or cancelled (required, named at the edge). Never a card number. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceRefundReasonRequestDTO {

    @jakarta.validation.constraints.NotBlank(message = "{billing.payment.reasonRequired}")
    @Size(max = 500, message = "{billing.refund.reasonTooLong}")
    @NoCardNumber
    private String reason;
}
