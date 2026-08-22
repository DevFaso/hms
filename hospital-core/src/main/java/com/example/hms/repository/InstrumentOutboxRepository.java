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
     * Monitor feed. The outbox row itself carries no hospital_id (V28 predates
     * the tenancy conventions), so scope is reached through the owning lab
     * order. {@code hospitalId} null = super-admin global view.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT o FROM InstrumentOutbox o "
        + "WHERE (:hospitalId IS NULL OR o.labOrder.hospital.id = :hospitalId) "
        + "AND (:status IS NULL OR o.status = :status)")
    org.springframework.data.domain.Page<InstrumentOutbox> searchScoped(
        @org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
        @org.springframework.data.repository.query.Param("status") InstrumentOutboxStatus status,
        org.springframework.data.domain.Pageable pageable);

    /** Queue-level counts per status for the monitor header, same scoping rule. */
    @org.springframework.data.jpa.repository.Query(
        "SELECT o.status, COUNT(o) FROM InstrumentOutbox o "
        + "WHERE (:hospitalId IS NULL OR o.labOrder.hospital.id = :hospitalId) "
        + "GROUP BY o.status")
    List<Object[]> countByStatusScoped(
        @org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId);

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
