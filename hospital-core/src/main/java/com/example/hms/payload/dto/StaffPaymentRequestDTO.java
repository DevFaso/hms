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
    private String method;
    /** billing.payment_transactions.reference_number is VARCHAR(120). */
    @jakarta.validation.constraints.Size(max = 120)
    // QA D768: a client sending "referenceNumber" (the stored column's name) had it dropped silently.
    @com.fasterxml.jackson.annotation.JsonAlias({"referenceNumber", "transactionReference"})
    private String reference;
    /** billing.payment_transactions.notes is VARCHAR(1024). */
    @jakarta.validation.constraints.Size(max = 1024)
    private String notes;
}
