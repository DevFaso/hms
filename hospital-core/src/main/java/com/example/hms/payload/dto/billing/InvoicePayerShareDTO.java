package com.example.hms.payload.dto.billing;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Billing-money plan PR 7: one payer's share of an invoice as staff and the patient see it: the payer and plan names
 * as the hospital typed them (D-8), the amount in whole francs, the status, and the payer's reason when it rejected
 * the share. Rejected and cancelled shares stay in the history.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InvoicePayerShareDTO(
    UUID id,
    String payerName,
    String planName,
    BigDecimal amount,
    int coverageYear,
    String status,
    String rejectionReason,
    LocalDateTime decidedAt,
    LocalDateTime createdAt) {
}
