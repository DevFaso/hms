package com.example.hms.payload.dto;

import com.example.hms.enums.InvoiceAdjustmentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * QA D974: lower an issued invoice. A credit note names the line it reverses
 * and may leave the amount empty (the line's remaining total is credited).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceAdjustmentRequestDTO {

    @NotNull(message = "{billing.adjustment.typeRequired}")
    private InvoiceAdjustmentType type;

    /** Whole francs CFA; required for a discount or a waiver. */
    @DecimalMin(value = "1", message = "{billing.adjustment.amountPositive}")
    // QA D1484: the digit count only bounds what is parsed. A whole number too large for the
    // invoice reaches the service, which says it is above the total; only decimals say decimals.
    @Digits(integer = 30, fraction = 0, message = "{billing.payment.wholeAmountOnly}")
    private BigDecimal amount;

    @NotBlank(message = "{billing.adjustment.reasonRequired}")
    @Size(max = 500)
    private String reason;

    /** The line a credit note reverses (required for a credit note). */
    private UUID invoiceItemId;
}
