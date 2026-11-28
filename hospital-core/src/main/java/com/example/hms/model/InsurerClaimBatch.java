package com.example.hms.model;

import com.example.hms.enums.InsurerClaimBatchStatus;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Insurer claims plan PR 2 (V455, QA D3983): a bordereau, the claim sheet a hospital sends to one payer for one
 * period. Built as a DRAFT from the payer's open shares, then submitted: it takes its number, its lines are frozen
 * and its totals are kept. The date it was sent and the date the payer received it are stored apart (owner decision
 * of 2026-11-26): the payer's delays run from receipt. Plain ids; the foreign keys are in the schema.
 */
@Entity
@Table(
    name = "insurer_claim_batches",
    schema = "billing",
    indexes = {
        @Index(name = "idx_claim_batch_hospital_status", columnList = "hospital_id,status"),
        @Index(name = "idx_claim_batch_hospital_payer", columnList = "hospital_id,payer_id")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class InsurerClaimBatch extends BaseEntity {

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "payer_id", nullable = false, updatable = false)
    private UUID payerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InsurerClaimBatchStatus status;

    /** The period claimed: service dates (the discharge date for a hospital stay), both ends included. */
    @Column(name = "period_from", nullable = false, updatable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false, updatable = false)
    private LocalDate periodTo;

    /** BORD-year-rank, per hospital and year; given once, at submission. */
    @Column(name = "number", length = 30)
    private String number;

    @Column(name = "number_year")
    private Integer numberYear;

    @Column(name = "number_seq")
    private Integer numberSeq;

    /** The day the bordereau was sent to the payer. */
    @Column(name = "submitted_on")
    private LocalDate submittedOn;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    /** The payer's own reference for the file, when it gave one. */
    @Column(name = "payer_reference", length = 120)
    private String payerReference;

    /** The day the payer acknowledged the file, when known. */
    @Column(name = "payer_received_on")
    private LocalDate payerReceivedOn;

    /** Frozen at submission: the number of lines and the sums of their gross, payer and patient amounts. */
    @Builder.Default
    @Column(name = "line_count", nullable = false)
    private Integer lineCount = 0;

    @Builder.Default
    @Column(name = "gross_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal grossTotal = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "payer_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal payerTotal = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "patient_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal patientTotal = BigDecimal.ZERO;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    /** A retried creation carrying the same key answers with the draft already made (unique per hospital). */
    @Column(name = "client_request_id", updatable = false)
    private UUID clientRequestId;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;
}
