package com.example.hms.model;

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
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "payment_transactions",
    schema = "billing",
    indexes = {
        @Index(name = "idx_pt_invoice", columnList = "invoice_id"),
        @Index(name = "idx_pt_payment_date", columnList = "payment_date"),
        @Index(name = "idx_pt_method", columnList = "payment_method")
    }
)
@Check(constraints = "amount > 0")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"invoice"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PaymentTransaction extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_pt_invoice"))
    private BillingInvoice invoice;

    @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2)
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @NotNull
    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 30)
    private PaymentMethod paymentMethod;

    @Column(name = "reference_number", length = 120)
    private String referenceNumber;

    /**
     * Billing plan PR 9 (V308): the operator of a Mobile Money payment, typed by
     * the cashier (no operator integration). Null on older rows and for every
     * other method ("Opérateur non précisé").
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "mobile_money_operator", length = 20)
    private com.example.hms.enums.MobileMoneyOperator mobileMoneyOperator;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    @Column(name = "notes", length = 1024)
    private String notes;

    /** QA D140/D142: only CONFIRMED rows count toward the invoice's amount paid. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private com.example.hms.enums.PaymentStatus status = com.example.hms.enums.PaymentStatus.CONFIRMED;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at")
    private java.time.LocalDateTime confirmedAt;

    /** QA D228: the invoice total when this money was received (V213); null on older rows. */
    @Column(name = "invoice_total_at_receipt", precision = 12, scale = 2)
    private java.math.BigDecimal invoiceTotalAtReceipt;

    @Column(name = "reversed_by")
    private UUID reversedBy;

    @Column(name = "reversed_at")
    private java.time.LocalDateTime reversedAt;

    @Column(name = "reversal_reason", length = 500)
    private String reversalReason;

    /**
     * Billing plan PR 11 (V310): the invoice's hospital, stamped when the row is written, so the
     * client request key is unique per hospital. Backfilled from the invoice on older rows.
     */
    @Column(name = "hospital_id")
    private UUID hospitalId;

    /**
     * Billing plan PR 11 (V310, QA D2430): the key the portal generates once per payment dialog.
     * A retry or a second submit carrying it answers with this row; none is written again.
     * Unique per hospital when present.
     */
    @Column(name = "client_request_id")
    private UUID clientRequestId;

    /** Billing plan PR 10 (V309): the cash session the money was taken in (null on rows written before it). */
    @Column(name = "cash_session_id")
    private UUID cashSessionId;

    /**
     * QA D2702 (V309): the approved, paid-out refund that reversed this row. The money stays counted in on the day
     * it came in; the outflow is that refund, on the day it left the till.
     */
    @Column(name = "reversal_refund_id")
    private UUID reversalRefundId;
}
