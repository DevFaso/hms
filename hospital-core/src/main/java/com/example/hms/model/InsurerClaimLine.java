package com.example.hms.model;

import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
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
import java.time.LocalDate;
import java.util.UUID;

/**
 * Insurer claims plan PRs 2 and 3 (V455): one claimed act of a bordereau: what one payer share claims of one invoice
 * line. While the bordereau is a draft only the ids and the claimed amount are kept; at submission the line takes a
 * snapshot of what was sent (patient, policy, act, dates, amounts), which is never rewritten. The settlement amounts
 * then follow the payer's answer, in whole francs:
 *
 * <pre>claimed = paid + re-billed to the patient + written off + outstanding</pre>
 *
 * and {@code writeOffPendingAmount} is the part of the outstanding amount held by a write-off request not yet
 * decided. A line is released when its bordereau is cancelled.
 */
@Entity
@Table(
    name = "insurer_claim_lines",
    schema = "billing",
    indexes = {
        @Index(name = "idx_claim_line_batch", columnList = "batch_id"),
        @Index(name = "idx_claim_line_share", columnList = "share_id")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"patientName", "subscriberName", "policyNumber"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class InsurerClaimLine extends BaseEntity {

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "share_id", nullable = false, updatable = false)
    private UUID shareId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "invoice_item_id", nullable = false, updatable = false)
    private UUID invoiceItemId;

    /** True once the bordereau was cancelled: the act can be claimed on another one. */
    @Builder.Default
    @Column(name = "released", nullable = false)
    private boolean released = false;

    /** What the share claims of the invoice line. Whole francs, more than 0. Never updated. */
    @Column(name = "claimed_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal claimedAmount;

    @Builder.Default
    @Column(name = "paid_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "rebilled_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal rebilledAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "written_off_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal writtenOffAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "write_off_pending_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal writeOffPendingAmount = BigDecimal.ZERO;

    /** The documents sent with the act (prescription, report, authorisation letter), as typed on the draft. */
    @Column(name = "attached_documents", length = 300)
    private String attachedDocuments;

    /* ------------------------------------------------- snapshot, written once at submission */

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "patient_name", columnDefinition = "TEXT")
    private String patientName;

    @Column(name = "patient_mrn", length = 60)
    private String patientMrn;

    @Column(name = "policy_number", length = 120)
    private String policyNumber;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "subscriber_name", columnDefinition = "TEXT")
    private String subscriberName;

    @Column(name = "subscriber_relationship", length = 60)
    private String subscriberRelationship;

    @Column(name = "plan_name", length = 200)
    private String planName;

    @Column(name = "invoice_number", length = 50)
    private String invoiceNumber;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    /** The day of the act (the invoice date, the checkout day). */
    @Column(name = "service_date")
    private LocalDate serviceDate;

    /** The day the patient left, for a hospital stay: the date that places the stay in a period. */
    @Column(name = "discharge_date")
    private LocalDate dischargeDate;

    @Column(name = "act_code", length = 80)
    private String actCode;

    @Column(name = "act_label", columnDefinition = "TEXT")
    private String actLabel;

    @Column(name = "category", length = 50)
    private String category;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "gross_amount", precision = 12, scale = 2)
    private BigDecimal grossAmount;

    /** The patient's part of the act (ticket modérateur): the gross amount less what the payer is asked. */
    @Column(name = "patient_amount", precision = 12, scale = 2)
    private BigDecimal patientAmount;

    @Column(name = "pre_authorisation_number", length = 120)
    private String preAuthorisationNumber;

    /* ------------------------------------------------------------------ amounts */

    /** Neither paid, re-billed nor written off: still expected from the payer. */
    public BigDecimal outstandingAmount() {
        return claimedAmount.subtract(paidAmount).subtract(rebilledAmount).subtract(writtenOffAmount);
    }

    /** What a payment, a refusal or a new write-off request may still take: the outstanding amount not held. */
    public BigDecimal availableAmount() {
        return outstandingAmount().subtract(writeOffPendingAmount);
    }
}
