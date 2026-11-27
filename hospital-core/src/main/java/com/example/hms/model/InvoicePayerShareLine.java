package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Insurer claims PR 1 (V383, review of #1353): what one payer share claimed of one invoice line, kept with the
 * share. The line's own {@code payer_share_amount} follows the live share and falls to 0 when the payer rejects it;
 * this row does not, so a rejected or cancelled share still lists the acts it claimed. Written when the share is
 * written; replaced as a set (never updated in place) only when a credit note moves the split while the share's
 * total stands (review of #1353, F1). Plain ids, nothing loaded.
 */
@Entity
@Table(
    name = "invoice_payer_share_lines",
    schema = "billing",
    uniqueConstraints = @UniqueConstraint(name = "uq_payer_share_line", columnNames = {"share_id", "invoice_item_id"}),
    indexes = @Index(name = "idx_payer_share_line_share", columnList = "share_id")
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class InvoicePayerShareLine extends BaseEntity {

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "share_id", nullable = false, updatable = false)
    private UUID shareId;

    @Column(name = "invoice_item_id", nullable = false, updatable = false)
    private UUID invoiceItemId;

    /** Whole francs, more than 0. Never updated. */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;
}
