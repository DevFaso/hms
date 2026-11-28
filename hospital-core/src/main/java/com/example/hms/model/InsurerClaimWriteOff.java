package com.example.hms.model;

import com.example.hms.enums.ClaimWriteOffReason;
import com.example.hms.enums.ClaimWriteOffStatus;
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
 * Insurer claims plan PR 4 (V458): a request to give up an amount a payer will not pay on a bordereau line. Asked
 * with a reason code, a reason and the reference of the supporting document; approved or refused by another person
 * holding the hospital admin or accountant role (four eyes, as for refunds); cancelled while it still waits. While
 * REQUESTED it holds its amount on the line. An approved write-off never changes what the patient owes.
 */
@Entity
@Table(
    name = "insurer_claim_write_offs",
    schema = "billing",
    indexes = {
        @Index(name = "idx_claim_write_off_hospital_status", columnList = "hospital_id,status"),
        @Index(name = "idx_claim_write_off_line", columnList = "claim_line_id")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class InsurerClaimWriteOff extends BaseEntity {

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "claim_line_id", nullable = false, updatable = false)
    private UUID claimLineId;

    @Column(name = "share_id", nullable = false, updatable = false)
    private UUID shareId;

    /** Whole francs, more than 0. Never updated. */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 40, updatable = false)
    private ClaimWriteOffReason reasonCode;

    @Column(name = "reason", nullable = false, length = 500, updatable = false)
    private String reason;

    /** The reference of the document that supports the write-off (a letter, a record of failed recovery). */
    @Column(name = "supporting_document", nullable = false, length = 200, updatable = false)
    private String supportingDocument;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ClaimWriteOffStatus status;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    /** A retried request carrying the same key answers with the request already made (unique per hospital). */
    @Column(name = "client_request_id", nullable = false, updatable = false)
    private UUID clientRequestId;
}
