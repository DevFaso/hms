package com.example.hms.model.pharmacy;

import com.example.hms.enums.MobileMoneyOperator;
import com.example.hms.enums.PharmacyPaymentMethod;
import com.example.hms.enums.PharmacyRefundStatus;
import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA D2676 (r65, V311; billing-money plan PR 13): money given back to a patient for a return of a counter sale or a
 * dispense. It names the RETURN stock movement that put the medication back, so the stock and the money of a return
 * are one record. Asked for with a reason, approved by ANOTHER hospital admin or accountant (owner decision D-3, the
 * same four-eyes rule as an invoice refund), then paid out; it is money out on the day it is paid ({@code paid_at}).
 * Ids only, no associations: every read is scoped by {@code hospital_id} without a join.
 */
@Entity
@Table(
    name = "pharmacy_refunds",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_prefund_hospital_status", columnList = "hospital_id,status"),
        @Index(name = "idx_prefund_pharmacy_paid", columnList = "pharmacy_id,paid_at"),
        @Index(name = "idx_prefund_sale", columnList = "sale_id"),
        @Index(name = "idx_prefund_dispense", columnList = "dispense_id")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PharmacyRefund extends BaseEntity {

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Column(name = "pharmacy_id", nullable = false)
    private UUID pharmacyId;

    /** The RETURN stock movement this refund pays for; at most one live refund per movement. */
    @Column(name = "return_transaction_id", nullable = false)
    private UUID returnTransactionId;

    /** The counter sale returned, or null when the return is of a dispense. */
    @Column(name = "sale_id")
    private UUID saleId;

    /** The dispense returned, or null when the return is of a counter sale. */
    @Column(name = "dispense_id")
    private UUID dispenseId;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantity;

    /** Whole francs, computed by the server from the price the patient paid; never sent by the client. */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 30)
    private PharmacyPaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "mobile_money_operator", length = 20)
    private MobileMoneyOperator mobileMoneyOperator;

    @Column(name = "reference", length = 120)
    private String reference;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PharmacyRefundStatus status;

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
}
