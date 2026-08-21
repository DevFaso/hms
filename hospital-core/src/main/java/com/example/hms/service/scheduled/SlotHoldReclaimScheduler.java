package com.example.hms.service.scheduled;

import com.example.hms.service.scheduling.SlotInventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Returns abandoned holds to circulation (P2 #11).
 *
 * <p>Belt and braces: the open-slot search already treats an expired hold as
 * available, so a stuck sweep cannot make slots disappear. This just tidies the
 * status so the rota reads honestly to anyone looking at it directly.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlotHoldReclaimScheduler {

    private final SlotInventoryService slotInventoryService;

    @Scheduled(fixedDelayString = "${app.scheduling.slot-hold-reclaim-ms:120000}")
    public void reclaim() {
        try {
            slotInventoryService.reclaimExpiredHolds();
        } catch (RuntimeException ex) {
            // Never propagate: an escaped exception cancels the whole
            // fixed-delay schedule in Spring.
            log.error("Slot hold reclaim sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
