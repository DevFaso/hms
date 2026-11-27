package com.example.hms.service.scheduled;

import com.example.hms.upload.UploadLedger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Asks the malware scanner again about the stored uploads it could not answer
 * for (kept, unserved, in the pending state). With no scanner plugged in no
 * upload is ever pending and the sweep reads one empty page.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadRescanScheduler {

    private final UploadLedger uploadLedger;

    @SchedulerLock(name = "UploadRescanScheduler.runSweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5S")
    @Scheduled(fixedDelayString = "${app.upload.scan.rescan-interval-ms:300000}")
    public void runSweep() {
        try {
            int settled = uploadLedger.rescanPending();
            if (settled > 0) {
                log.info("Upload rescan sweep: {} pending upload(s) settled", settled);
            }
        } catch (RuntimeException ex) {
            log.warn("Upload rescan sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
