package com.example.hms.payload.dto.portal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Patient request to make a payment towards an invoice")
public class PatientPaymentRequestDTO {

    @NotNull
    @DecimalMin(value = "0.01", message = "Payment amount must be greater than zero")
    @Digits(integer = 10, fraction = 2, message = "Payment amount must have at most 2 decimal places")
    @Schema(description = "Amount to pay", example = "150.00", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal amount;

    @NotNull
    @Size(min = 1, max = 50)
    @Schema(description = "Payment method used", example = "CARD",
            allowableValues = {"CARD", "CASH", "BANK_TRANSFER", "MOBILE_MONEY", "INSURANCE"},
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String paymentMethod;

    @Size(max = 500)
    @Schema(description = "Optional reference or transaction ID from external payment provider",
            example = "TXN-20260308-ABC123")
    private String transactionReference;

    @Size(max = 1000)
    @Schema(description = "Optional notes about the payment", example = "Partial payment for consultation")
    private String notes;
}
