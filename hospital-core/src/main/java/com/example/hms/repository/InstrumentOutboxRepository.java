package com.example.hms.repository;

import com.example.hms.enums.InstrumentOutboxStatus;
import com.example.hms.model.InstrumentOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InstrumentOutboxRepository extends JpaRepository<InstrumentOutbox, UUID> {

    List<InstrumentOutbox> findByStatus(InstrumentOutboxStatus status);

    List<InstrumentOutbox> findByLabOrder_Id(UUID labOrderId);

    /**
     * Dispatch sweep feed: queued messages that have either never been tried or
     * whose backoff has elapsed, below the attempt ceiling.
     *
     * <p>Unscoped by design — the sender is a system actor covering every
     * hospital, the same shape as the critical-value sweep.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT o FROM InstrumentOutbox o "
        + "WHERE o.status = :status "
        + "AND o.attempts < :maxAttempts "
        + "AND (o.lastAttemptAt IS NULL OR o.lastAttemptAt < :retryBefore) "
        + "ORDER BY o.createdAt ASC")
    List<InstrumentOutbox> findDispatchable(
        @org.springframework.data.repository.query.Param("status") InstrumentOutboxStatus status,
        @org.springframework.data.repository.query.Param("maxAttempts") int maxAttempts,
        @org.springframework.data.repository.query.Param("retryBefore") java.time.LocalDateTime retryBefore,
        org.springframework.data.domain.Pageable pageable);
}
