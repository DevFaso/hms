package com.example.hms.payload.dto.billing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Billing-money plan PR 8: one refund of an invoice as billing staff see it, with who asked, who decided, who paid it
 * out and why. {@code requestedByMe} tells the screen that the reader cannot decide it (owner decision D-3).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceRefundDTO {
    private UUID id;
    private UUID invoiceId;
    private String invoiceNumber;
    private String patientName;
    private BigDecimal amount;
    private String method;
    private String mobileMoneyOperator;
    private String reference;
    private String reason;
    private String status;
    private UUID requestedBy;
    private String requestedByName;
    private LocalDateTime requestedAt;
    private boolean requestedByMe;
    private UUID decidedBy;
    private String decidedByName;
    private LocalDateTime decidedAt;
    private String decisionReason;
    private UUID paidBy;
    private String paidByName;
    private LocalDateTime paidAt;
    /** PR 8 review: the receipt number given at payout (-RB<n>). */
    private String receiptNumber;
    private UUID cancelledBy;
    private String cancelledByName;
    private LocalDateTime cancelledAt;
    private String cancelReason;
}
