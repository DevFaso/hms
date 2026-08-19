package com.example.hms.model.pharmacy;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.medication.MedicationCatalogItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * P-07: A single line item on a {@link PharmacySale}.
 *
 * <p>Each line names the medication, the quantity sold, and the unit price at
 * the time of sale. Unit price is captured per line so that subsequent catalog
 * price changes do not retroactively alter the recorded sale.
 */
@Entity
@Table(
    name = "sale_lines",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_sline_sale", columnList = "sale_id"),
        @Index(name = "idx_sline_catalog_item", columnList = "medication_catalog_item_id"),
        @Index(name = "idx_sline_stock_lot", columnList = "stock_lot_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"sale", "medicationCatalogItem", "stockLot"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class SaleLine extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_sline_sale"))
    private PharmacySale sale;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "medication_catalog_item_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_sline_catalog_item"))
    private MedicationCatalogItem medicationCatalogItem;

    /** Nullable: a sale line may not always reference a tracked stock lot. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_lot_id",
        foreignKey = @ForeignKey(name = "fk_sline_stock_lot"))
    private StockLot stockLot;

    @NotNull
    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @NotNull
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @NotNull
    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    @Size(max = 500)
    @Column(name = "notes", length = 500)
    private String notes;
}
