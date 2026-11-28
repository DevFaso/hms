package com.example.hms.model;

import com.example.hms.enums.PayerShareStatus;
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
 * Billing-money plan PR 7 (V306): what one payer plan owes on one invoice through one of the patient's coverages,
 * computed when the invoice is issued (and again when a credit note changes a line). Whole francs. The amount is
 * never rewritten: a changed share is a new row, and the old one is CANCELLED. A REJECTED share moves back to the
 * patient and keeps its amount in the history. Plain ids (the foreign keys are in the schema), so nothing here loads
 * the invoice, the coverage or the plan.
 */
@Entity
@Table(
    name = "invoice_payer_shares",
    schema = "billing",
    indexes = {
        @Index(name = "idx_payer_share_invoice", columnList = "invoice_id"),
        @Index(name = "idx_payer_share_hospital_status", columnList = "hospital_id,status"),
        @Index(name = "idx_payer_share_insurance_year", columnList = "patient_insurance_id,coverage_year")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class InvoicePayerShare extends BaseEntity {

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "patient_insurance_id", nullable = false, updatable = false)
    private UUID patientInsuranceId;

    @Column(name = "payer_plan_id", nullable = false, updatable = false)
    private UUID payerPlanId;

    /** Whole francs, 0 or more. Never updated. */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    /** The calendar year of the service date: the year whose annual ceiling this share uses (D-13). */
    @Column(name = "coverage_year", nullable = false, updatable = false)
    private Integer coverageYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PayerShareStatus status;

    /** The payer's reason, as typed, when it REJECTED the share. */
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** Insurer claims plan PR 3 (V457): what the payer's remittances paid of the share. Whole francs. */
    @Builder.Default
    @Column(name = "paid_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /** V457: what the payer refused and the hospital re-billed to the patient; it left the invoice's payer share. */
    @Builder.Default
    @Column(name = "rejected_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal rejectedAmount = BigDecimal.ZERO;

    /** V457: what the hospital gave up after an approved write-off. The patient never owes it. */
    @Builder.Default
    @Column(name = "written_off_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal writtenOffAmount = BigDecimal.ZERO;

    /** What the payer still bears of the share: the amount less what was re-billed or written off. */
    public BigDecimal borneAmount() {
        return amount.subtract(zeroIfNull(rejectedAmount)).subtract(zeroIfNull(writtenOffAmount));
    }

    /** What is neither paid, re-billed nor written off: still expected from the payer. */
    public BigDecimal openAmount() {
        return borneAmount().subtract(zeroIfNull(paidAmount));
    }

    /**
     * V457: the status of a claimed share that follows its amounts. Closed ({@link #openAmount} is 0): PAID when the
     * payer paid any of it, else WRITTEN_OFF when any of it was written off, else REJECTED (all of it re-billed to
     * the patient). Not closed: PARTIALLY_PAID once something was paid, else CLAIMED.
     */
    public PayerShareStatus settledStatus() {
        boolean paid = zeroIfNull(paidAmount).signum() > 0;
        if (openAmount().signum() > 0) {
            return paid ? PayerShareStatus.PARTIALLY_PAID : PayerShareStatus.CLAIMED;
        }
        if (paid) {
            return PayerShareStatus.PAID;
        }
        return zeroIfNull(writtenOffAmount).signum() > 0 ? PayerShareStatus.WRITTEN_OFF : PayerShareStatus.REJECTED;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
