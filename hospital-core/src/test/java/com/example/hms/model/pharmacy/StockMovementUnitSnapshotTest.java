package com.example.hms.model.pharmacy;

import jakarta.persistence.Column;
import jakarta.persistence.PrePersist;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StockMovementUnitSnapshotTest {
    @Test
    void theUnitIsCapturedAtBookingAndDoesNotFollowLaterItemChanges() throws Exception {
        var item = InventoryItem.builder().unit("tablet").build();
        var movement = StockTransaction.builder().inventoryItem(item).unitSnapshot("invented").build();
        movement.captureStockUnit();
        item.setUnit("box");
        assertThat(movement.getUnitSnapshot()).isEqualTo("tablet");
        assertThat(StockTransaction.class.getDeclaredMethod("captureStockUnit").isAnnotationPresent(PrePersist.class)).isTrue();
        assertThat(StockTransaction.class.getDeclaredField("unitSnapshot").getAnnotation(Column.class).updatable()).isFalse();
    }

    @Test
    void aMissingUnitStaysUnknown() {
        var movement = StockTransaction.builder().inventoryItem(new InventoryItem()).build();
        movement.captureStockUnit();
        assertThat(movement.getUnitSnapshot()).isNull();
        movement.setInventoryItem(null);
        movement.captureStockUnit();
        assertThat(movement.getUnitSnapshot()).isNull();
    }
}
