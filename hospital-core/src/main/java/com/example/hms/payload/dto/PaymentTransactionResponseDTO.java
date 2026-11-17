package com.example.hms.payload.dto;

import com.example.hms.model.PaymentTransaction;

import java.math.BigDecimal;

/** One ledger row of an invoice, as billing staff see it (QA D142). */
public record PaymentTransactionResponseDTO(
    String id,
    BigDecimal amount,
    String paymentDate,
    String paymentMethod,
    String referenceNumber,
    String notes,
    String status,
    String reversalReason,
    String reversedAt,
    String createdAt
) {
    public static PaymentTransactionResponseDTO from(PaymentTransaction row) {
        return new PaymentTransactionResponseDTO(
            row.getId() != null ? row.getId().toString() : null,
            row.getAmount(),
            row.getPaymentDate() != null ? row.getPaymentDate().toString() : null,
            row.getPaymentMethod() != null ? row.getPaymentMethod().name() : null,
            row.getReferenceNumber(),
            row.getNotes(),
            row.getStatus() != null ? row.getStatus().name() : null,
            row.getReversalReason(),
            row.getReversedAt() != null ? row.getReversedAt().toString() : null,
            row.getCreatedAt() != null ? row.getCreatedAt().toString() : null);
    }
}
