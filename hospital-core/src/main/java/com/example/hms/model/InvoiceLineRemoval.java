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
 * PR 10 review (V309, QA D2703): a line taken off a draft invoice. The line itself is deleted, so what it was, its
 * price, who removed it, when and why are kept here: an unpriced charge removed and typed again at another price
 * left no trace, and the hospital's day report lists these rows (« Lignes retirées »).
 */
@Entity
@Table(
    name = "invoice_line_removals",
    schema = "billing",
    indexes = @Index(name = "idx_line_removal_hospital_at", columnList = "hospital_id,removed_at")
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class InvoiceLineRemoval extends BaseEntity {

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "invoice_number", length = 100)
    private String invoiceNumber;

    /** The line as it read; with {@link #textCode} and {@link #textArgs} when the server worded it (QA D1901). */
    @Column(name = "item_description", nullable = false, length = 255)
    private String itemDescription;

    @Column(name = "text_code", length = 120)
    private String textCode;

    @Column(name = "text_args", columnDefinition = "TEXT")
    private String textArgs;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    /** The price list's price for the line when it had one. */
    @Column(name = "tariff_unit_price", precision = 12, scale = 2)
    private BigDecimal tariffUnitPrice;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "removed_by", nullable = false)
    private UUID removedBy;

    @Column(name = "removed_at", nullable = false)
    private LocalDateTime removedAt;
}
