package com.example.hms.service.pharmacy;

import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.service.i18n.NotificationLocalizer;
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
    private final HospitalRepository hospitals;
    private final InventoryItemRepository items;
    private final LowStockAlertDelivery delivery;

    @Scheduled(cron = "${pharmacy.low-stock.alert.cron:0 0 8 * * *}", zone = "UTC")
    @SchedulerLock(name = "LowStockAlertScheduler.sendAlerts", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5S")
    public void sendAlerts() {
        for (var hospital : hospitals.findByActiveTrueAndLifecycleStateOrderByNameAsc(HospitalLifecycleState.ACTIVE)) {
            for (var item : items.findBelowReorderThresholdByHospital(hospital.getId())) {
                try {
                    String drug = item.getMedicationCatalogItem() == null ? null
                        : com.example.hms.utility.DrugDisplayNames.forReader(item.getMedicationCatalogItem(), Locale.FRENCH);
                    Object[] arguments = {drug == null ? NotificationLocalizer.key("inventory.lowStock.unknown") : drug,
                        item.getPharmacy() == null ? NotificationLocalizer.key("inventory.lowStock.unknown") : item.getPharmacy().getName(),
                        item.getQuantityOnHand(), item.getReorderThreshold()};
                    delivery.deliver(item, arguments);
                } catch (RuntimeException failure) {
                    log.warn("Low-stock alert delivery failed for inventory item {}", item.getId(), failure);
                }
            }
        }
    }
}
