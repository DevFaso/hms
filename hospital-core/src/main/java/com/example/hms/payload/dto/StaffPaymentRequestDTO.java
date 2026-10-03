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
    /** CASH | CARD | INSURANCE | OTHER */
    private String method;
    /** billing.payment_transactions.reference_number is VARCHAR(120). */
    @jakarta.validation.constraints.Size(max = 120)
    private String reference;
    /** billing.payment_transactions.notes is VARCHAR(1024). */
    @jakarta.validation.constraints.Size(max = 1024)
    private String notes;
}
