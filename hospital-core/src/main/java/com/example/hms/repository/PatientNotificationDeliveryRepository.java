package com.example.hms.repository;

import com.example.hms.enums.NotificationDeliveryStatus;
import com.example.hms.model.PatientNotificationDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The patient notification delivery ledger (V312). The dispatch queries are the mail outbox's
 * (V173): list due ids, claim each with a conditional update, record the outcome. Every staff read
 * and the manual retry take the hospital id and filter on it: a row of another hospital answers
 * exactly like a row that does not exist.
 */
@Repository
public interface PatientNotificationDeliveryRepository extends JpaRepository<PatientNotificationDelivery, UUID> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    /** Due open rows under the attempt ceiling, most overdue first. Ids only. */
    @Query("select d.id from PatientNotificationDelivery d where d.status in :open "
        + "and d.attempts < :maxAttempts and d.nextAttemptAt <= :now order by d.nextAttemptAt asc")
    List<UUID> findDispatchableIds(@Param("open") Collection<NotificationDeliveryStatus> open,
                                   @Param("maxAttempts") int maxAttempts,
                                   @Param("now") LocalDateTime now,
                                   Pageable pageable);

    /**
     * The atomic claim: of two sweeps on two instances exactly one gets 1. Counting the attempt and
     * pushing {@code nextAttemptAt} to the lease happen together, so a crash between the claim and
     * the outcome retries the row once the lease runs out instead of stranding it.
     */
    @Modifying
    @Query("update PatientNotificationDelivery d set d.attempts = d.attempts + 1, d.lastAttemptAt = :now, "
        + "d.nextAttemptAt = :leaseUntil, d.updatedAt = :now "
        + "where d.id = :id and d.status in :open and d.attempts < :maxAttempts and d.nextAttemptAt <= :now")
    int claim(@Param("id") UUID id,
              @Param("open") Collection<NotificationDeliveryStatus> open,
              @Param("maxAttempts") int maxAttempts,
              @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    /**
     * The outcome of a send, recorded only by the sweep that still holds the claim: the claim
     * stamped {@code lastAttemptAt}, which is the claim token. A sweep whose send outlived its lease
     * finds the row claimed again by another one (a later {@code lastAttemptAt}) and gets 0 back, so
     * it never overwrites the newer attempt's state.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PatientNotificationDelivery d set d.status = :sent, d.sentAt = :now, d.errorCode = null, "
        + "d.updatedAt = :now where d.id = :id and d.lastAttemptAt = :claimedAt and d.status in :open")
    int markSent(@Param("id") UUID id,
                 @Param("claimedAt") LocalDateTime claimedAt,
                 @Param("open") Collection<NotificationDeliveryStatus> open,
                 @Param("sent") NotificationDeliveryStatus sent,
                 @Param("now") LocalDateTime now);

    /** A failed send's state (RETRYING, FAILED or SKIPPED), under the same claim-token rule as {@link #markSent}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PatientNotificationDelivery d set d.status = :status, d.errorCode = :code, "
        + "d.nextAttemptAt = :nextAttemptAt, d.updatedAt = :now "
        + "where d.id = :id and d.lastAttemptAt = :claimedAt and d.status in :open")
    int markFailure(@Param("id") UUID id,
                    @Param("claimedAt") LocalDateTime claimedAt,
                    @Param("open") Collection<NotificationDeliveryStatus> open,
                    @Param("status") NotificationDeliveryStatus status,
                    @Param("code") String code,
                    @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                    @Param("now") LocalDateTime now);

    /** Rows whose last claim never reported back: closed as FAILED once the lease is over. */
    @Modifying
    @Query("update PatientNotificationDelivery d set d.status = :failed, d.errorCode = :code, d.updatedAt = :now "
        + "where d.status in :open and d.attempts >= :maxAttempts and d.nextAttemptAt <= :now")
    int closeExhausted(@Param("open") Collection<NotificationDeliveryStatus> open,
                       @Param("failed") NotificationDeliveryStatus failed,
                       @Param("maxAttempts") int maxAttempts,
                       @Param("now") LocalDateTime now,
                       @Param("code") String code);

    /** The staff view: one hospital's rows in the given statuses, the pageable's order. */
    @Query("select d from PatientNotificationDelivery d where d.hospitalId = :hospitalId and d.status in :statuses")
    Page<PatientNotificationDelivery> searchForHospital(@Param("hospitalId") UUID hospitalId,
                                                        @Param("statuses") Collection<NotificationDeliveryStatus> statuses,
                                                        Pageable pageable);

    /** Counts per status for one hospital: rows of {@code [status, count]}. */
    @Query("select d.status, count(d) from PatientNotificationDelivery d where d.hospitalId = :hospitalId "
        + "group by d.status")
    List<Object[]> countByStatusForHospital(@Param("hospitalId") UUID hospitalId);

    Optional<PatientNotificationDelivery> findByIdAndHospitalId(UUID id, UUID hospitalId);

    /**
     * A hospital administrator's retry: a FAILED row of THEIR hospital goes back in the queue with a
     * fresh attempt budget. Conditional, so a double click or two administrators at once requeue it
     * once; a SENT row can never be requeued, which is what keeps a manual retry from texting twice.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PatientNotificationDelivery d set d.status = :pending, d.attempts = 0, d.errorCode = null, "
        + "d.nextAttemptAt = :now, d.manualRetries = d.manualRetries + 1, d.lastRetriedBy = :userId, "
        + "d.lastRetriedAt = :now, d.updatedAt = :now "
        + "where d.id = :id and d.hospitalId = :hospitalId and d.status = :failed and d.manualRetries = :expectedManualRetries")
    int requeueFailed(@Param("id") UUID id,
                      @Param("hospitalId") UUID hospitalId,
                      @Param("failed") NotificationDeliveryStatus failed,
                      @Param("pending") NotificationDeliveryStatus pending,
                      @Param("now") LocalDateTime now,
                      @Param("userId") UUID userId,
                      @Param("expectedManualRetries") int expectedManualRetries);
}
