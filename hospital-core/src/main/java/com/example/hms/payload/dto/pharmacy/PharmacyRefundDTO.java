package com.example.hms.payload.dto.pharmacy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA D2676 (r65): one pharmacy refund. {@code paidAt} and {@code amount} are the money out of the till (Track C
 * attaches them to the cash session). {@code canDecide} tells the screen whether the reader may approve or reject it
 * (an approver who did not ask for it).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PharmacyRefundDTO {
    private UUID id;
    private UUID pharmacyId;
    private UUID returnTransactionId;
    private UUID saleId;
    private UUID dispenseId;
    private String medicationName;
    private BigDecimal quantity;
    private BigDecimal amount;
    private String method;
    private String mobileMoneyOperator;
    private String reference;
    private String reason;
    private String status;
    private UUID requestedBy;
    private String requestedByName;
    private LocalDateTime requestedAt;
    private UUID decidedBy;
    private LocalDateTime decidedAt;
    private String decisionReason;
    private UUID paidBy;
    private LocalDateTime paidAt;
    private String cancelReason;
    private boolean canDecide;
}
