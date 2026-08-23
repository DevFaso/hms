package com.example.hms.service.scheduled;

import com.example.hms.service.ReceptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Returns lapsed waitlist offers to WAITING (P3 #22).
 *
 * <p>The slot side is already covered — SlotHoldReclaimScheduler frees the
 * expired offer-hold — but nothing else would ever flip the entry itself back,
 * so an unanswered offer would strand the patient in OFFERED forever.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WaitlistOfferReconcileScheduler {

    private final ReceptionService receptionService;

    @Scheduled(fixedDelayString = "${app.scheduling.waitlist-offer-reconcile-ms:300000}")
    public void reconcile() {
        try {
            receptionService.reconcileExpiredWaitlistOffers();
        } catch (RuntimeException ex) {
            // Never propagate: an escaped exception cancels the whole
            // fixed-delay schedule in Spring.
            log.error("Waitlist offer reconcile sweep failed: {}", ex.getMessage(), ex);
        }
    }
}
