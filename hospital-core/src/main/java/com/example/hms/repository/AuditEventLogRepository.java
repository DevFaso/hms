package com.example.hms.repository;

import com.example.hms.enums.AuditEventType;
import com.example.hms.model.AuditEventLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.example.hms.enums.AuditStatus;

import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditEventLogRepository extends JpaRepository<AuditEventLog, UUID> {

    Page<AuditEventLog> findByUserId(UUID userId, Pageable pageable);

    Page<AuditEventLog> findByEventType(AuditEventType eventType, Pageable pageable);

    Page<AuditEventLog> findByEntityTypeIgnoreCaseAndResourceId(
        String entityType, String resourceId, Pageable pageable);

    Page<AuditEventLog> findByEventTypeAndStatus(AuditEventType type, AuditStatus status, Pageable pageable);

    /**
     * Override the default findAll(Pageable) with an explicit DISTINCT query so that
     * Hibernate does not trigger a secondary unique-key lookup on Staff when the
     * hospital.staff table contains duplicate rows for the same PK.
     * The passDistinctThrough=false hint removes SQL DISTINCT (which breaks pagination
     * count) and deduplicates in-memory instead.
     */
    @Override
    @Query("SELECT DISTINCT a FROM AuditEventLog a ORDER BY a.eventTimestamp DESC")
    @QueryHints(@QueryHint(name = "hibernate.query.passDistinctThrough", value = "false"))
    Page<AuditEventLog> findAll(Pageable pageable);

    /**
     * Ordered descending by eventTimestamp — used by SuperAdminDashboardServiceImpl.
     * Explicit JPQL DISTINCT prevents the 'More than one row with the given identifier'
     * Hibernate error when navigating to User (which has a OneToOne Staff back-ref).
     */
    @Query("SELECT DISTINCT a FROM AuditEventLog a ORDER BY a.eventTimestamp DESC")
    @QueryHints(@QueryHint(name = "hibernate.query.passDistinctThrough", value = "false"))
    Page<AuditEventLog> findAllByOrderByEventTimestampDesc(Pageable pageable);

    /** Aggregate count of audit events grouped by event type (database-level). */
    @Query("SELECT a.eventType AS eventType, COUNT(a) AS cnt FROM AuditEventLog a GROUP BY a.eventType")
    List<Object[]> countByEventType();

    /** Hospital-scoped audit events, ordered by timestamp descending. */
    Page<AuditEventLog> findByAssignment_Hospital_IdOrderByEventTimestampDesc(UUID hospitalId, Pageable pageable);

    /** Daily audit event counts for a hospital within a date range. */
    @Query("SELECT function('date', a.eventTimestamp) AS day, COUNT(a) " +
           "FROM AuditEventLog a " +
           "WHERE a.assignment.hospital.id = :hospitalId " +
           "AND a.eventTimestamp >= :from " +
           "GROUP BY function('date', a.eventTimestamp) " +
           "ORDER BY function('date', a.eventTimestamp)")
    List<Object[]> countDailyByHospital(@Param("hospitalId") UUID hospitalId,
                                        @Param("from") LocalDateTime from);
}
