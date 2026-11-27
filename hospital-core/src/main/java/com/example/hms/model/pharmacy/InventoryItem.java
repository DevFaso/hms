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

    /** Database-maintained revision: includes native stock updates and committed unit changes. */
    @org.hibernate.annotations.ColumnDefault("0")
    @org.hibernate.annotations.Generated(event = {org.hibernate.generator.EventType.INSERT, org.hibernate.generator.EventType.UPDATE})
    @Column(name = "stock_revision", nullable = false, insertable = false, updatable = false)
    @Builder.Default
    private Long stockRevision = 0L;


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

    /*
     * Never written by an entity flush (updatable = false): every change goes
     * through the atomic UPDATEs in the repository (#825 round 5), so a stale
     * managed copy can never overwrite a concurrent fill, return, receipt or
     * adjustment. Refresh the entity after one of those UPDATEs to read it.
     */
    @NotNull
    @Column(name = "quantity_on_hand", nullable = false, updatable = false, precision = 12, scale = 2)
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

    /** QA D264: the selling price of one unit, in XOF; null until the pharmacy sets it. */
    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice;

    /**
     * QA D4181 (r81, V420): why this item is handed over at no charge at this pharmacy (a free national programme,
     * a donation). Null on every item that is sold: a fill of an item with neither a selling price nor this reason
     * is refused ({@code FillPriceGate}). Set, like the price, by the store manager or a hospital administrator.
     */
    @Size(max = 500)
    @Column(name = "free_of_charge_reason", length = 500)
    private String freeOfChargeReason;

    /**
     * QA D2674 (r65, V311): how many of {@link #unit} one pack holds (a "plaquette de 10 comprimés" is 10), set when
     * stock received in packs is converted to the unit prescriptions are written in. Null on an item never converted.
     */
    @Column(name = "units_per_pack")
    private Integer unitsPerPack;

    /** QA D2674: the pack's own name ("plaquette", "boîte"), shown beside the stock counted in {@link #unit}. */
    @Size(max = 60)
    @Column(name = "pack_unit", length = 60)
    private String packUnit;

    /**
     * Review of #1239 (V311): when the pack size was set. What was recorded before it (a sale line, a fill, a return)
     * counts packs, what was recorded after counts {@link #unit}; a refund converts between the two.
     */
    @Column(name = "pack_size_set_at")
    private java.time.LocalDateTime packSizeSetAt;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;
}
