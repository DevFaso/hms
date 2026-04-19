package com.example.hms.model.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.model.BaseEntity;
import com.example.hms.model.User;
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
import java.util.UUID;

/**
 * Immutable ledger entry for any stock movement.
 * Each row records receipt, dispense, adjustment, transfer, or return.
 */
@Entity
@Table(
    name = "stock_transactions",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_st_inventory", columnList = "inventory_item_id"),
        @Index(name = "idx_st_lot", columnList = "stock_lot_id"),
        @Index(name = "idx_st_type", columnList = "transaction_type"),
        @Index(name = "idx_st_date", columnList = "created_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"inventoryItem", "stockLot", "performedByUser"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class StockTransaction extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inventory_item_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_st_inventory"))
    private InventoryItem inventoryItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_lot_id",
        foreignKey = @ForeignKey(name = "fk_st_lot"))
    private StockLot stockLot;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 30)
    private StockTransactionType transactionType;

    @NotNull
    @Column(name = "quantity", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantity;

    @Size(max = 500)
    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "reference_id")
    private UUID referenceId;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performed_by", nullable = false,
        foreignKey = @ForeignKey(name = "fk_st_user"))
    private User performedByUser;
}
