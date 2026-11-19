package com.example.hms.payload.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StaffPaymentRequestDTO {
    @NotNull
    @DecimalMin(value = "0.01", message = "{staffPayment.amount.min}")
    private BigDecimal amount;
    /**
     * CASH | CARD | INSURANCE | OTHER. QA D1300 (round 28): always required
     * (D1293), so bean validation names it with the amount in one refusal.
     */
    @jakarta.validation.constraints.NotBlank(message = "{billing.payment.methodRequired}")
    // QA D1363 (round 31): an unsupported method is named with a malformed amount, in one refusal.
    @jakarta.validation.constraints.Pattern(regexp = com.example.hms.enums.PaymentMethod.CODES,
        flags = jakarta.validation.constraints.Pattern.Flag.CASE_INSENSITIVE, message = "{billing.payment.methodUnsupported}")
    private String method;
    /** billing.payment_transactions.reference_number is VARCHAR(120). */
    @jakarta.validation.constraints.Size(max = 120)
    // QA D768: a client sending "referenceNumber" (the stored column's name) had it dropped silently.
    @com.fasterxml.jackson.annotation.JsonAlias({"referenceNumber", "transactionReference"})
    @com.example.hms.validation.NoCardNumber
    private String reference;
    /** billing.payment_transactions.notes is VARCHAR(1024). */
    @jakarta.validation.constraints.Size(max = 1024)
    @com.example.hms.validation.NoCardNumber
    private String notes;

    /**
     * Billing plan PR 9: Orange Money, Moov Money or another operator. Required
     * when the method is MOBILE_MONEY (manual entry, no operator integration).
     */
    private com.example.hms.enums.MobileMoneyOperator mobileMoneyOperator;

    /**
     * Billing plan PR 11 (QA D2430): the key the portal generates once per payment dialog and
     * sends again on a retry. A request carrying a key already recorded at this hospital answers
     * with that payment instead of recording a second one. Optional: without it nothing changes.
     */
    private java.util.UUID clientRequestId;

    /** A payment with no Mobile Money operator (any method other than MOBILE_MONEY). */
    public StaffPaymentRequestDTO(BigDecimal amount, String method, String reference, String notes) {
        this(amount, method, reference, notes, null);
    }

    /** A payment sent without an idempotency key (callers written before PR 11). */
    public StaffPaymentRequestDTO(BigDecimal amount, String method, String reference, String notes,
            com.example.hms.enums.MobileMoneyOperator mobileMoneyOperator) {
        this(amount, method, reference, notes, mobileMoneyOperator, null);
    }
}
