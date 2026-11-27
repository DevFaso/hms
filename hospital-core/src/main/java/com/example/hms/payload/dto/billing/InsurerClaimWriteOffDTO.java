package com.example.hms.payload.dto.billing;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Insurer claims plan PR 4: a write-off request on a bordereau line as the approver reads it: the payer, the
 * bordereau, the invoice and the act, the amount, why, the supporting document, who asked and who decided.
 * {@code requestedByMe} tells the portal that the reader cannot approve it (four eyes).
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InsurerClaimWriteOffDTO {

    private UUID id;
    private UUID claimLineId;
    private UUID batchId;
    private String batchNumber;
    private String payerName;
    private String invoiceNumber;
    private String patientName;
    private String actLabel;
    private BigDecimal amount;
    private String reasonCode;
    private String reason;
    private String supportingDocument;
    private String status;
    private String requestedByName;
    private LocalDateTime requestedAt;
    private boolean requestedByMe;
    private String decidedByName;
    private LocalDateTime decidedAt;
    private String decisionReason;
}
