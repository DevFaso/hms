package com.example.hms.model;

import com.example.hms.enums.InvoiceAdjustmentType;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * QA D974 (V242): a discount, a waiver or a credit note on an issued
 * invoice. The invoice total is lowered by {@link #amount}; the row keeps who
 * did it and why. A credit note names the line it reverses.
 */
@Entity
@Table(
    name = "invoice_adjustments",
    schema = "billing",
    indexes = {
        @Index(name = "idx_ia_invoice", columnList = "invoice_id"),
        @Index(name = "idx_ia_invoice_item", columnList = "invoice_item_id")
    }
)
@Check(constraints = "amount > 0")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"invoice", "invoiceItem"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class InvoiceAdjustment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ia_invoice"))
    private BillingInvoice invoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_item_id", foreignKey = @ForeignKey(name = "fk_ia_invoice_item"))
    private InvoiceItem invoiceItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 20)
    private InvoiceAdjustmentType type;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "created_by")
    private UUID createdBy;

    /**
     * QA D1105 (V254): when the adjustment was reversed (a mistaken waiver
     * undone), by whom and why. A reversed row stays on the ledger and no
     * longer lowers the total. Null while the adjustment stands.
     */
    @Column(name = "reversed_at")
    private java.time.LocalDateTime reversedAt;

    @Column(name = "reversed_by")
    private UUID reversedBy;

    @Column(name = "reversal_reason", length = 500)
    private String reversalReason;

    /** Whether the adjustment still lowers what is owed. */
    public boolean isLive() {
        return reversedAt == null;
    }
}
