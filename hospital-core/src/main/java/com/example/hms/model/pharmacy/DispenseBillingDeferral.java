package com.example.hms.model.pharmacy;

import com.example.hms.enums.DispenseBillingDeferralKind;
import com.example.hms.enums.DispenseBillingDeferralStatus;
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
 * Policy rule 6 (provisional, V345; QA r65 D2675): the reason a fill left the pharmacy with money still due, and the
 * people who answer for it. « À facturer » is an exception: somebody asks, ANOTHER person approves (billing for a
 * third-party cover, finance or the hospital admin for credit), and the pharmacist only executes it; the approver
 * never hands the fill over. Two rows are written already EXECUTED: an urgency override (the pharmacist, with a
 * reason; finance reviews it) and an inpatient item charged to the open stay. Ids only, no associations: every read
 * is scoped by {@code hospital_id} without a join.
 */
@Entity
@Table(
    name = "dispense_billing_deferrals",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_dbdef_hospital_status", columnList = "hospital_id,status"),
        @Index(name = "idx_dbdef_prescription", columnList = "prescription_id")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class DispenseBillingDeferral extends BaseEntity {

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Column(name = "prescription_id", nullable = false)
    private UUID prescriptionId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    /**
     * The first fill handed over on this deferral; null until then. One deferral covers one hand-over; a fill the
     * counter splits over two lots is two rows, and each names this deferral ({@code Dispense#billingDeferralId}).
     */
    @Column(name = "dispense_id")
    private UUID dispenseId;

    /** The open stay an ADMISSION_ACCOUNT item is charged to. */
    @Column(name = "admission_id")
    private UUID admissionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30)
    private DispenseBillingDeferralKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DispenseBillingDeferralStatus status;

    /** Why it was asked for, or why the pharmacist overrode; null only on an admission-account row. */
    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    /** Who approved, rejected or cancelled it. An approver is never {@link #requestedBy}. */
    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** The reason the approver gave (required for credit), or the reason of a rejection or a cancellation. */
    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    /** The most that may leave on this credit, in whole francs; null when the approver set none. */
    @Column(name = "credit_limit", precision = 12, scale = 2)
    private BigDecimal creditLimit;

    /** The claim or voucher billing checked for a third-party cover. */
    @Column(name = "cover_reference", length = 120)
    private String coverReference;

    /** Who handed the fill over: never {@link #decidedBy}. */
    @Column(name = "executed_by")
    private UUID executedBy;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    /** What was still due on the fill when it left, in whole francs. */
    @Column(name = "amount_due", precision = 12, scale = 2)
    private BigDecimal amountDue;

    /** The finance review of an urgency override: never by the pharmacist who overrode. */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    /** Who withdrew it before any hand-over, when and why. */
    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;
}
