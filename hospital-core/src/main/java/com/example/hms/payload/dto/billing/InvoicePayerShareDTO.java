package com.example.hms.payload.dto.billing;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Billing-money plan PR 7: one payer's share of an invoice as staff and the patient see it: the payer and plan names
 * as the hospital typed them (D-8), the amount in whole francs, the status, and the payer's reason when it rejected
 * the share. Rejected and cancelled shares stay in the history.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InvoicePayerShareDTO {
    private UUID id;
    private String payerName;
    private String planName;
    private BigDecimal amount;
    private int coverageYear;
    private String status;
    /** The payer's own reason for refusing its share, as typed; the patient is told it (they now owe the amount). */
    private String rejectionReason;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
}
