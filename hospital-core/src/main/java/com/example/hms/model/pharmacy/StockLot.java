package com.example.hms.model.pharmacy;

import com.example.hms.model.BaseEntity;
import com.example.hms.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
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
import java.time.LocalDate;

/**
 * Individual stock lot with lot number, expiry date, and cost traceability.
 * Supports FEFO (First Expiry, First Out) dispensing policy.
 */
@Entity
@Table(
    name = "stock_lots",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_sl_inventory", columnList = "inventory_item_id"),
        @Index(name = "idx_sl_expiry", columnList = "expiry_date"),
        @Index(name = "idx_sl_lot_number", columnList = "lot_number")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"inventoryItem", "receivedByUser"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class StockLot extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inventory_item_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_sl_inventory"))
    private InventoryItem inventoryItem;

    @NotBlank
    @Size(max = 80)
    @Column(name = "lot_number", nullable = false, length = 80)
    private String lotNumber;

    @NotNull
    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @NotNull
    @Column(name = "initial_quantity", nullable = false, precision = 12, scale = 2)
    private BigDecimal initialQuantity;

    @NotNull
    @Column(name = "remaining_quantity", nullable = false, precision = 12, scale = 2)
    private BigDecimal remainingQuantity;

    @Size(max = 255)
    @Column(name = "supplier", length = 255)
    private String supplier;

    @Column(name = "unit_cost", precision = 12, scale = 4)
    private BigDecimal unitCost;

    @NotNull
    @Column(name = "received_date", nullable = false)
    private LocalDate receivedDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "received_by",
        foreignKey = @ForeignKey(name = "fk_sl_received_by"))
    private User receivedByUser;

    @Size(max = 1000)
    @Column(name = "notes", length = 1000)
    private String notes;

    /**
     * Server-minted scannable identifier for this lot, printed on the lot
     * label and compared to the product scan at dispense time (Tier 2
     * item 34).
     *
     * <p>Minted by the system rather than read off the pack because stock
     * here arrives from government allocation, donation and local purchase:
     * manufacturer barcodes are inconsistent where they exist and absent on
     * repackaged stock, so verifying against one would work for some
     * consignments and silently not for others. Same reasoning, and the same
     * shape, as {@code LabSpecimen.barcodeValue}.
     *
     * <p>Null on lots received before V138 — printing a label backfills it.
     */
    @Size(max = 64)
    @Column(name = "barcode_value", length = 64)
    private String barcodeValue;
}
