package com.example.hms.payload.dto.billing;

import com.example.hms.validation.NoCardNumber;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Billing-money plan PR 8 (QA D1900): a request to give money back to the patient on an invoice. The amount, the
 * method and the reason are required (named together at the edge, and again by the service);
 * mobile money also needs the operator and the operator's reference. No text field may hold a card number.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceRefundRequestDTO {

    /** Whole francs, more than 0, at most what is left to refund. */
    @jakarta.validation.constraints.NotNull(message = "{billing.refund.amountRequired}")
    private BigDecimal amount;

    /** A {@code PaymentMethod} code: how the money is handed back (never INSURANCE). */
    @jakarta.validation.constraints.NotBlank(message = "{billing.payment.methodRequired}")
    @jakarta.validation.constraints.Pattern(regexp = com.example.hms.enums.PaymentMethod.CODES,
        flags = jakarta.validation.constraints.Pattern.Flag.CASE_INSENSITIVE,
        message = "{billing.payment.methodUnsupported}")
    private String method;

    /** A {@code MobileMoneyOperator} code; required for MOBILE_MONEY. */
    @jakarta.validation.constraints.Pattern(regexp = com.example.hms.enums.MobileMoneyOperator.CODES,
        flags = jakarta.validation.constraints.Pattern.Flag.CASE_INSENSITIVE,
        message = "{billing.refund.operatorUnsupported}")
    private String mobileMoneyOperator;

    /** The operator's or the bank's transaction reference; required for mobile money and bank transfer. */
    @Size(max = 120, message = "{billing.refund.referenceTooLong}")
    @NoCardNumber
    private String reference;

    @jakarta.validation.constraints.NotBlank(message = "{billing.refund.reasonRequired}")
    @Size(max = 500, message = "{billing.refund.reasonTooLong}")
    @NoCardNumber
    private String reason;

    /** A key the client keeps across retries: the same key returns the refund already asked for. */
    private UUID clientRequestId;
}
