package com.example.hms.repository.platform;

import com.example.hms.enums.platform.MailOutboxStatus;
import com.example.hms.model.platform.MailOutboxMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface MailOutboxRepository extends JpaRepository<MailOutboxMessage, UUID> {

    /**
     * The sweep's candidate list: pending rows under the attempt ceiling whose
     * backoff (or claim lease) has run out, most overdue first. Ids only: each
     * row is then claimed on its own before any network work.
     */
    @Query("select m.id from MailOutboxMessage m where m.status = :status "
        + "and m.attempts < :maxAttempts and m.nextAttemptAt <= :now "
        + "order by m.nextAttemptAt asc")
    List<UUID> findDispatchableIds(@Param("status") MailOutboxStatus status,
                                   @Param("maxAttempts") int maxAttempts,
                                   @Param("now") LocalDateTime now,
                                   Pageable pageable);

    /**
     * The atomic claim, the webhook outbox's conditional update: it matches
     * only while the row is still dispatchable, so of two instances sweeping
     * at once exactly one gets 1 back and the other 0. Counting the attempt
     * and pushing {@code nextAttemptAt} out to the lease happen in the same
     * statement, so a crash between the claim and the outcome retries the row
     * after the lease instead of stranding it. A bulk update skips
     * {@code @PreUpdate}, hence the explicit {@code updatedAt}.
     */
    @Modifying
    @Query("update MailOutboxMessage m set m.attempts = m.attempts + 1, m.lastAttemptAt = :now, "
        + "m.nextAttemptAt = :leaseUntil, m.updatedAt = :now "
        + "where m.id = :id and m.status = :status "
        + "and m.attempts < :maxAttempts and m.nextAttemptAt <= :now")
    int claim(@Param("id") UUID id,
              @Param("status") MailOutboxStatus status,
              @Param("maxAttempts") int maxAttempts,
              @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    /**
     * Rows that used their last attempt and never reported back (a crash after
     * the final claim): nothing will ever pick them up again, so once their
     * lease is over they are closed as FAILED and their body dropped.
     */
    @Modifying
    @Query("update MailOutboxMessage m set m.status = :failed, m.subject = null, m.htmlBody = null, "
        + "m.lastError = :reason, m.updatedAt = :now "
        + "where m.status = :pending and m.attempts >= :maxAttempts and m.nextAttemptAt <= :now")
    int closeExhausted(@Param("pending") MailOutboxStatus pending,
                       @Param("failed") MailOutboxStatus failed,
                       @Param("maxAttempts") int maxAttempts,
                       @Param("now") LocalDateTime now,
                       @Param("reason") String reason);

    /** Retention: terminal rows older than the cutoff go, recipients and all. */
    @Modifying
    @Query("delete from MailOutboxMessage m where m.status in :terminal and m.updatedAt < :cutoff")
    int deleteTerminalBefore(@Param("terminal") List<MailOutboxStatus> terminal,
                             @Param("cutoff") LocalDateTime cutoff);

    long countByStatus(MailOutboxStatus status);
}
