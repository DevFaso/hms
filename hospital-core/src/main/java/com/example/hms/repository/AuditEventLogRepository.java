package com.example.hms.repository;

import com.example.hms.enums.AuditEventType;
import com.example.hms.model.AuditEventLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.example.hms.enums.AuditStatus;

import jakarta.persistence.QueryHint;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditEventLogRepository
    extends JpaRepository<AuditEventLog, UUID>, JpaSpecificationExecutor<AuditEventLog> {

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

    /**
     * Per-hospital audit-event count over a date window. Roadmap row 44
     * (per-tenant cost observability) foundation pass. Grouped by the
     * denormalized {@code hospitalName} snapshot so the rollup can
     * render without joining hospital.hospitals back in. Rows whose
     * snapshot is null (SYSTEM-actor writes with no hospital
     * assignment) are excluded — those belong to a separate
     * platform-shared bucket the row-44 follow-on will surface.
     */
    @Query("SELECT a.hospitalName AS hospitalName, COUNT(a) AS cnt "
           + "FROM AuditEventLog a "
           + "WHERE a.hospitalName IS NOT NULL "
           + "AND a.eventTimestamp >= :from "
           + "AND a.eventTimestamp <= :to "
           + "GROUP BY a.hospitalName "
           + "ORDER BY a.hospitalName ASC")
    List<Object[]> countByHospitalBetween(@Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /**
     * Stable-key variant of {@link #countByHospitalBetween} (row-44
     * follow-on). Groups by {@code assignment.hospital.id} so a
     * hospital rename does not split historic data across old/new
     * snapshots, AND so two hospitals that happen to share a display
     * name aren't collapsed into one chargeback row. Hospital name
     * comes back as a JOIN projection for the UI; the load-bearing
     * key is the UUID. Caught on the foundation-pass repo query in
     * PR #352 Copilot review (multi-tenancy-scoping skill — "Aggregate
     * queries must group by a stable key, not display name").
     *
     * <p>Rows without an {@code assignment} (SYSTEM-actor writes —
     * MLLP / schedulers / Kafka consumers) are excluded — those carry
     * no per-tenant attribution and roll up under the
     * platform-shared bucket the follow-on still owes.
     */
    @Query("SELECT a.assignment.hospital.id AS hospitalId, "
           + "       a.assignment.hospital.name AS hospitalName, "
           + "       COUNT(a) AS cnt "
           + "FROM AuditEventLog a "
           + "WHERE a.assignment.hospital.id IS NOT NULL "
           + "AND a.eventTimestamp >= :from "
           + "AND a.eventTimestamp <= :to "
           + "GROUP BY a.assignment.hospital.id, a.assignment.hospital.name "
           + "ORDER BY a.assignment.hospital.name ASC")
    List<Object[]> countByHospitalIdBetween(@Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to);

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

    /**
     * Date-range query with optional from/to bounds. Both params are nullable —
     * if null the respective bound is ignored, returning all records in the other direction.
     * passDistinctThrough=false avoids SQL DISTINCT breaking pagination COUNT(*).
     */
    @Query("SELECT DISTINCT a FROM AuditEventLog a WHERE " +
           "(:fromDate IS NULL OR a.eventTimestamp >= :fromDate) AND " +
           "(:toDate IS NULL OR a.eventTimestamp <= :toDate) " +
           "ORDER BY a.eventTimestamp DESC")
    @QueryHints(@QueryHint(name = "hibernate.query.passDistinctThrough", value = "false"))
    Page<AuditEventLog> findByDateRange(@Param("fromDate") LocalDateTime fromDate,
                                        @Param("toDate") LocalDateTime toDate,
                                        Pageable pageable);

    /**
     * MVP-c3 — date-range query restricted to a set of event types. Used
     * by the audit-aggregation service to split SUPPORT vs.
     * PLATFORM_CONFIG without double-counting: PLATFORM_CONFIG passes
     * the platform-config event-type set; SUPPORT passes the same set
     * to {@link #findByDateRangeAndEventTypeNotIn}.
     */
    @Query("SELECT DISTINCT a FROM AuditEventLog a WHERE " +
           "(:fromDate IS NULL OR a.eventTimestamp >= :fromDate) AND " +
           "(:toDate IS NULL OR a.eventTimestamp <= :toDate) AND " +
           "a.eventType IN :eventTypes " +
           "ORDER BY a.eventTimestamp DESC")
    @QueryHints(@QueryHint(name = "hibernate.query.passDistinctThrough", value = "false"))
    Page<AuditEventLog> findByDateRangeAndEventTypeIn(@Param("fromDate") LocalDateTime fromDate,
                                                     @Param("toDate") LocalDateTime toDate,
                                                     @Param("eventTypes") Collection<AuditEventType> eventTypes,
                                                     Pageable pageable);

    /** Counterpart of {@link #findByDateRangeAndEventTypeIn} — everything not in the set. */
    @Query("SELECT DISTINCT a FROM AuditEventLog a WHERE " +
           "(:fromDate IS NULL OR a.eventTimestamp >= :fromDate) AND " +
           "(:toDate IS NULL OR a.eventTimestamp <= :toDate) AND " +
           "a.eventType NOT IN :eventTypes " +
           "ORDER BY a.eventTimestamp DESC")
    @QueryHints(@QueryHint(name = "hibernate.query.passDistinctThrough", value = "false"))
    Page<AuditEventLog> findByDateRangeAndEventTypeNotIn(@Param("fromDate") LocalDateTime fromDate,
                                                        @Param("toDate") LocalDateTime toDate,
                                                        @Param("eventTypes") Collection<AuditEventType> eventTypes,
                                                        Pageable pageable);
}
