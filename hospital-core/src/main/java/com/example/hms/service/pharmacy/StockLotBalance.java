package com.example.hms.service.pharmacy;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.pharmacy.InventoryItem;
import java.math.BigDecimal;

/** Lot tracking may not absorb or discard an existing unallocated stock balance. Call under the item lock. */
final class StockLotBalance {
    private StockLotBalance() { }

    static void requireReconciled(InventoryItem item, BigDecimal lotBalance) {
        BigDecimal onHand = item.getQuantityOnHand() == null ? BigDecimal.ZERO : item.getQuantityOnHand();
        BigDecimal tracked = lotBalance == null ? BigDecimal.ZERO : lotBalance;
        if (onHand.compareTo(tracked) != 0) {
            throw new FieldBusinessException("stockLotId", "pharmacy.inventory.lot.balanceUnreconciled");
        }
    }

    static void requireLotlessReturn(boolean tracksLots) {
        if (tracksLots) {
            throw new FieldBusinessException("stockLotId", "pharmacy.inventory.lot.trackingChanged");
        }
    }
}
