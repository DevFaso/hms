package com.example.hms.payload.dto.portal;

import io.swagger.v3.oas.annotations.media.Schema;
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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Patient request to make a payment towards an invoice")
public class PatientPaymentRequestDTO {

    @NotNull
    @DecimalMin(value = "0.01", message = "{patientPayment.amount.min}")
    @Digits(integer = 10, fraction = 2, message = "{patientPayment.amount.invalid}")
    @Schema(description = "Amount to pay", example = "150.00", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal amount;

    // QA D1317 (round 29): a blank method read "size must be between 1 and 50".
    @NotBlank
    @Size(max = 50)
    // QA D1363 (round 31): an unsupported method is named with a malformed amount, in one refusal.
    @jakarta.validation.constraints.Pattern(regexp = com.example.hms.enums.PaymentMethod.CODES,
        flags = jakarta.validation.constraints.Pattern.Flag.CASE_INSENSITIVE, message = "{billing.payment.methodUnsupported}")

    @Schema(description = "Payment method used", example = "CARD",
            allowableValues = {"CARD", "CASH", "BANK_TRANSFER", "MOBILE_MONEY", "INSURANCE"},
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String paymentMethod;

    /** {@code billing.payment_transactions.reference_number} is VARCHAR(120). */
    @Size(max = 120)
    @Schema(description = "Optional reference or transaction ID from external payment provider",
            example = "TXN-20260308-ABC123")
    private String transactionReference;

    @Size(max = 1000)
    @Schema(description = "Optional notes about the payment", example = "Partial payment for consultation")
    private String notes;

    /**
     * QA D433: a card, transfer or mobile-money payment is declared with the
     * reference staff need to confirm it; only cash is paid at the desk.
     */
    @com.example.hms.validation.ReportedOn("transactionReference")
    @jakarta.validation.constraints.AssertTrue(message = "{patientPayment.reference.required}")
    @io.swagger.v3.oas.annotations.Hidden
    public boolean isReferenceGivenForNonCashPayment() {
        if (paymentMethod == null || "CASH".equalsIgnoreCase(paymentMethod.trim())) {
            return true;
        }
        return transactionReference != null && !transactionReference.isBlank();
    }
}
