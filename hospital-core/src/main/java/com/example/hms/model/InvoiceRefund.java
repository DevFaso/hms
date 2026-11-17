package com.example.hms.model;

import com.example.hms.enums.InvoiceRefundStatus;
import com.example.hms.enums.MobileMoneyOperator;
import com.example.hms.enums.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Billing-money plan PR 8 (V307, QA D1900): money handed back to a patient on an invoice, the outgoing entry of the
 * billing ledger. It never rewrites a payment that came in: the payment rows keep their amounts, and the invoice
 * counts what went out in {@code amount_refunded}. A refund is asked for, approved by another person (owner
 * decision D-3), then paid out at the till; it counts as money out on the day it is paid.
 */
@Entity
@Table(
    name = "invoice_refunds",
    schema = "billing",
    indexes = {
        @Index(name = "idx_refund_invoice", columnList = "invoice_id"),
        @Index(name = "idx_refund_hospital_status", columnList = "hospital_id,status")
    }
)
@Check(constraints = "amount > 0")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"invoice"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class InvoiceRefund extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, foreignKey = @ForeignKey(name = "fk_refund_invoice"))
    private BillingInvoice invoice;

    /** The invoice's hospital, kept on the row so every read is scoped without a join. */
    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 30)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "mobile_money_operator", length = 20)
    private MobileMoneyOperator mobileMoneyOperator;

    @Column(name = "reference", length = 120)
    private String reference;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceRefundStatus status;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(name = "paid_by")
    private UUID paidBy;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    /** PR 8 review: the receipt's rank on the invoice (-RB1, -RB2...), assigned once at payout under the invoice lock. */
    @Column(name = "receipt_seq")
    private Integer receiptSeq;

    /** The receipt number printed on the refund receipt, stored at payout and never renumbered. */
    @Column(name = "receipt_number", length = 80)
    private String receiptNumber;

    /** A retried request carrying the same key returns the refund already asked for (unique per hospital). */
    @Column(name = "client_request_id")
    private UUID clientRequestId;
}
