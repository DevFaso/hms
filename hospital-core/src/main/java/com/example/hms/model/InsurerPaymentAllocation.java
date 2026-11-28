package com.example.hms.model;

import jakarta.persistence.Column;
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
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Insurer claims plan PR 3 (V456): the part of a payer's remittance put on one bordereau line. Insert-only: the
 * amount is never rewritten, and {@code voidedAt} is set when the payment is reversed. The live allocations of a
 * payment never exceed its amount; what is left stays on the payment, to allocate.
 */
@Entity
@Table(
    name = "insurer_payment_allocations",
    schema = "billing",
    indexes = {
        @Index(name = "idx_insurer_alloc_payment", columnList = "payment_id"),
        @Index(name = "idx_insurer_alloc_line", columnList = "claim_line_id")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class InsurerPaymentAllocation extends BaseEntity {

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "claim_line_id", nullable = false, updatable = false)
    private UUID claimLineId;

    @Column(name = "share_id", nullable = false, updatable = false)
    private UUID shareId;

    /** Whole francs, more than 0. Never updated. */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "allocated_by", nullable = false, updatable = false)
    private UUID allocatedBy;

    @Column(name = "allocated_at", nullable = false, updatable = false)
    private LocalDateTime allocatedAt;

    /** Set when the payment was reversed: the allocation no longer counts. */
    @Column(name = "voided_at")
    private LocalDateTime voidedAt;
}
