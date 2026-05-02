package com.example.hms.service.impl;

import com.example.hms.enums.ReferralEventType;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.GeneralReferral;
import com.example.hms.repository.GeneralReferralRepository;
import com.example.hms.service.ReferralEventRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Per-referral commit boundary for the EXPIRED auto-sweep.
 *
 * <p>Lives in a separate Spring bean so {@code REQUIRES_NEW} actually fires
 * through the AOP proxy (Sonar S6809; mirrors {@code Dhis2ExportRunPersistence}).
 * Each call opens its own transaction so a single optimistic-lock failure
 * skips one row without rolling back the whole batch.
 *
 * <p>The sweep is the only writer that bumps a referral from
 * SUBMITTED/ACKNOWLEDGED/SCHEDULED to EXPIRED. If a clinician transitions the
 * referral to IN_PROGRESS / COMPLETED / CANCELLED / REJECTED in another
 * transaction between the sweep's SELECT and its UPDATE, this helper
 * relies on {@code @Version} on {@code GeneralReferral} so Hibernate
 * raises {@link OptimisticLockingFailureException} on flush instead of
 * silently overwriting.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReferralExpiryPersistence {

    private final GeneralReferralRepository referralRepository;
    private final ReferralEventRecorder eventRecorder;

    /**
     * Re-load {@code referralId} inside a fresh transaction and attempt the
     * EXPIRED transition. Returns {@code true} on success, {@code false} on
     * either an entity-guard rejection (status flipped to a terminal state
     * that {@code expire()} disallows, e.g. IN_PROGRESS) or an optimistic-lock
     * failure (status flipped between SELECT and flush by another writer).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryExpire(UUID referralId, String reason, String eventSource) {
        GeneralReferral referral = referralRepository.findById(referralId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "generalReferral.notFound", referralId));
        ReferralStatus before = referral.getStatus();
        try {
            referral.expire(reason);
            // saveAndFlush forces the UPDATE (and the @Version check) to run inside
            // this REQUIRES_NEW transaction so we can catch the optimistic-lock failure
            // here instead of at the outer transaction's commit.
            referralRepository.saveAndFlush(referral);
            eventRecorder.recordSystemEvent(
                referral, ReferralEventType.EXPIRE, before, eventSource, reason);
            return true;
        } catch (IllegalStateException ex) {
            // Pre-write race: clinician already moved the referral to a status
            // that the entity guard rejects. No row written, no audit emitted.
            log.warn("Referral {} skipped — state changed mid-sweep (entity guard): {}",
                referralId, ex.getMessage());
            return false;
        } catch (OptimisticLockingFailureException ex) {
            // Concurrent commit incremented version between our SELECT and our flush.
            // Spring rolls back this REQUIRES_NEW transaction so no audit is written.
            log.warn("Referral {} skipped — concurrent update lost the optimistic-lock race",
                referralId);
            return false;
        }
    }
}
