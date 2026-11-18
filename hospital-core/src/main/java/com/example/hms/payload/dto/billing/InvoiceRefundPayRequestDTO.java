package com.example.hms.payload.dto.billing;

import com.example.hms.validation.NoCardNumber;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Billing-money plan PR 8 review: what the cashier records when an approved refund is handed over. For mobile money
 * and bank transfer the money is sent now, so the operator's (or the bank's) transaction reference is required here,
 * and the operator for mobile money. A cash refund needs neither. Never a card number.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceRefundPayRequestDTO {

    @Size(max = 120, message = "{billing.refund.referenceTooLong}")
    @NoCardNumber
    private String reference;

    private com.example.hms.enums.MobileMoneyOperator mobileMoneyOperator;
}
