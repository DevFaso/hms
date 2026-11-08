package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PharmacyPaymentResponseDTO {

    private UUID id;
    /**
     * QA D1032: the readable receipt number printed on the receipt,
     * {@code CODE-PH-YYYY-000001} (the payment's rank among the hospital's
     * pharmacy payments of that year), never a slice of the payment id.
     */
    private String receiptNumber;
    private UUID dispenseId;
    private UUID patientId;
    private UUID hospitalId;
    private String paymentMethod;
    private BigDecimal amount;
    private String currency;
    private String referenceNumber;
    private UUID receivedBy;
    private String notes;
    /** QA D404: POSTED, or VOIDED when the dispense was cancelled. */
    private String status;
    private LocalDateTime voidedAt;
    private String voidReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
