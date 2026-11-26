package com.example.hms.service.pharmacy;

import com.example.hms.repository.pharmacy.InventoryItemRepository;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** In-app stock warnings only; never contacts a patient or an external SMS/e-mail gateway. */
@Component
@RequiredArgsConstructor
@Slf4j
public class LowStockAlertScheduler {
    private final InventoryItemRepository items;
    private final LowStockAlertDelivery delivery;

    @Scheduled(cron = "${pharmacy.low-stock.alert.cron:0 0 8 * * *}", zone = "UTC")
    @SchedulerLock(name = "LowStockAlertScheduler.sendAlerts", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5S")
    public void sendAlerts() {
        for (var itemId : items.findLowStockIdsAcrossActiveFacilities()) {
            try {
                delivery.deliver(itemId, Locale.FRENCH);
            } catch (RuntimeException failure) {
                log.warn("Low-stock alert delivery failed for inventory item {}", itemId, failure);
            }
        }
    }
}
