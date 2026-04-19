package com.example.hms.model.pharmacy;

import com.example.hms.model.BaseEntity;
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
 * Per-medication stock summary at a pharmacy.
 * Aggregates stock lots for a single catalog medication.
 */
@Entity
@Table(
    name = "inventory_items",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_inv_pharmacy", columnList = "pharmacy_id"),
        @Index(name = "idx_inv_medication", columnList = "medication_catalog_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"pharmacy", "medicationCatalogItem"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class InventoryItem extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pharmacy_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_inv_pharmacy"))
    private Pharmacy pharmacy;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "medication_catalog_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_inv_medication"))
    private MedicationCatalogItem medicationCatalogItem;

    @NotNull
    @Column(name = "quantity_on_hand", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal quantityOnHand = BigDecimal.ZERO;

    @Column(name = "reorder_threshold", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal reorderThreshold = BigDecimal.ZERO;

    @Column(name = "reorder_quantity", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal reorderQuantity = BigDecimal.ZERO;

    @Size(max = 60)
    @Column(name = "unit", length = 60)
    private String unit;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
