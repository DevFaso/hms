package com.example.hms.service.pharmacy;

import com.example.hms.repository.pharmacy.InventoryItemRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class LowStockAlertSchedulerTest {
    @Test
    void eachSelectedIdIsReloadedByDeliveryAndOneFailureDoesNotSkipTheNextItem() {
        var items = mock(InventoryItemRepository.class);
        var delivery = mock(LowStockAlertDelivery.class);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        when(items.findLowStockIdsAcrossActiveFacilities()).thenReturn(List.of(first, second));
        when(delivery.deliver(first)).thenThrow(new IllegalStateException("Synthetic failure"));
        new LowStockAlertScheduler(items, delivery).sendAlerts();
        verify(delivery).deliver(first);
        verify(delivery).deliver(second);
    }
}
