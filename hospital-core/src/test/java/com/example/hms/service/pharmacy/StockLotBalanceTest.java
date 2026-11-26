package com.example.hms.service.pharmacy;

import com.example.hms.model.pharmacy.InventoryItem;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockLotBalanceTest {
    @Test
    void introducingLotTrackingRequiresTheExistingBookAndLotTotalsToAgree() {
        var item = InventoryItem.builder().quantityOnHand(new BigDecimal("20.00")).build();
        StockLotBalance.requireReconciled(item, new BigDecimal("20"));
        assertThatThrownBy(() -> StockLotBalance.requireReconciled(item, BigDecimal.ZERO))
            .hasFieldOrPropertyWithValue("messageKey", "pharmacy.inventory.lot.balanceUnreconciled");
        assertThatThrownBy(() -> StockLotBalance.requireReconciled(item, new BigDecimal("25")))
            .hasFieldOrPropertyWithValue("messageKey", "pharmacy.inventory.lot.balanceUnreconciled");
        StockLotBalance.requireReconciled(InventoryItem.builder().quantityOnHand(BigDecimal.ZERO).build(), BigDecimal.ZERO);
    }

    @Test
    void anOldLotlessReturnCannotDebitStockNowTrackedInLots() {
        StockLotBalance.requireLotlessReturn(false);
        assertThatThrownBy(() -> StockLotBalance.requireLotlessReturn(true))
            .hasFieldOrPropertyWithValue("messageKey", "pharmacy.inventory.lot.trackingChanged");
    }
}
