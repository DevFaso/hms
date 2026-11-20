package com.example.hms.model.pharmacy;

import com.example.hms.model.BaseEntity;
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
 * Review of #1239 (V311): one change of a pharmacy's selling price. The price of an inventory item was overwritten
 * with no trace, by the same pharmacist who sells at it, and the reconciliation compared every past sale with
 * today's price. Each change is kept here with the old and the new price, the reason, who and when; rows are never
 * updated or deleted. Ids only, no associations: every read names the item.
 */
@Entity
@Table(
    name = "pharmacy_price_changes",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_pprice_item_changed", columnList = "inventory_item_id,changed_at"),
        @Index(name = "idx_pprice_hospital", columnList = "hospital_id")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PharmacyPriceChange extends BaseEntity {

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Column(name = "pharmacy_id", nullable = false)
    private UUID pharmacyId;

    @Column(name = "inventory_item_id", nullable = false)
    private UUID inventoryItemId;

    /** The price of one unit before the change; null when the item had none. */
    @Column(name = "old_price", precision = 12, scale = 2)
    private BigDecimal oldPrice;

    /** The price of one unit from {@link #changedAt} on; null when the price was removed. */
    @Column(name = "new_price", precision = 12, scale = 2)
    private BigDecimal newPrice;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "changed_by", nullable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;
}
