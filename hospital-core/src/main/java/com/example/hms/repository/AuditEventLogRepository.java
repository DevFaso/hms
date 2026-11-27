package com.example.hms.repository;

import com.example.hms.enums.AuditEventType;
import com.example.hms.model.AuditEventLog;
import com.example.hms.payload.dto.provider.ProviderAuditEntryDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.example.hms.enums.AuditStatus;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditEventLogRepository
    extends JpaRepository<AuditEventLog, UUID>, JpaSpecificationExecutor<AuditEventLog> {

    /** The FROM/WHERE of {@link #findProviderFacilityTrail}, shared by its page and count queries. */
    String FACILITY_TRAIL_WHERE = "FROM AuditEventLog a LEFT JOIN a.assignment asg "
        + "WHERE a.entityType IN :entityTypes "
        + "AND a.patientId IS NULL "
        + "AND (a.roleName IS NULL OR UPPER(a.roleName) NOT IN ('PATIENT', 'ROLE_PATIENT')) "
        + "AND a.user.id IN (SELECT seat.user.id FROM UserRoleHospitalAssignment seat "
        + "  WHERE seat.hospital.id = :facilityId "
        + "  AND UPPER(seat.role.code) NOT IN ('PATIENT', 'ROLE_PATIENT')) "
        + "AND (asg.hospital.id = :facilityId "
        + "  OR (a.entityType = 'USER_ROLE_ASSIGNMENT' AND a.resourceId IN ("
        + "    SELECT CAST(target.id AS String) FROM UserRoleHospitalAssignment target "
        + "    WHERE target.hospital.id = :facilityId))) ";

    Page<AuditEventLog> findByUserId(UUID userId, Pageable pageable);

    /** A user's rows without the event types in {@code hidden} (their own activity). */
    @Query("select a from AuditEventLog a where a.user.id = :userId and a.eventType not in :hidden")
    Page<AuditEventLog> findOwnActivity(@Param("userId") UUID userId,
                                        @Param("hidden") java.util.Collection<com.example.hms.enums.AuditEventType> hidden,
                                        Pageable pageable);

    Page<AuditEventLog> findByEventType(AuditEventType eventType, Pageable pageable);

    Page<AuditEventLog> findByEntityTypeIgnoreCaseAndResourceId(
        String entityType, String resourceId, Pageable pageable);

    Page<AuditEventLog> findByEventTypeAndStatus(AuditEventType type, AuditStatus status, Pageable pageable);

    /**
     * Override the default findAll(Pageable) with an explicit DISTINCT query so that
     * Hibernate does not trigger a secondary unique-key lookup on Staff when the
     * hospital.staff table contains duplicate rows for the same PK.
     * (Hibernate 5's passDistinctThrough hint used to keep this DISTINCT out of
     * the SQL; Hibernate 6 removed the setting, so the hint was dead code
     * logging HHH90003003 on every call and has been dropped.)
     */
    @Override
    @Query("SELECT DISTINCT a FROM AuditEventLog a ORDER BY a.eventTimestamp DESC")
    Page<AuditEventLog> findAll(Pageable pageable);

    /**
     * Ordered descending by eventTimestamp — used by SuperAdminDashboardServiceImpl.
     * Explicit JPQL DISTINCT prevents the 'More than one row with the given identifier'
     * Hibernate error when navigating to User (which has a OneToOne Staff back-ref).
     */
    @Query("SELECT DISTINCT a FROM AuditEventLog a ORDER BY a.eventTimestamp DESC")
    Page<AuditEventLog> findAllByOrderByEventTimestampDesc(Pageable pageable);

    /** Aggregate count of audit events grouped by event type (database-level). */
    @Query("SELECT a.eventType AS eventType, COUNT(a) AS cnt FROM AuditEventLog a GROUP BY a.eventType")
    List<Object[]> countByEventType();

    /**
     * The WHERE of every hospital-scoped audit read (QA D25, D786): a row is
     * the hospital's when its assignment is there, or when it carries the
     * hospital itself (a SYSTEM row: a migration or a job that acted on that
     * hospital's data has no assignment). The LEFT join keeps those rows; an
     * inner join through {@code a.assignment} dropped them.
     */
    String ANCHORED_AT_HOSPITALS = "(asg.hospital.id IN :hospitalIds OR a.hospitalId IN :hospitalIds)";

    /** {@link #countByEventType()} restricted to rows anchored at one of the given hospitals (QA D25, D786). */
    @Query("SELECT a.eventType AS eventType, COUNT(a) AS cnt FROM AuditEventLog a LEFT JOIN a.assignment asg "
           + "WHERE " + ANCHORED_AT_HOSPITALS + " GROUP BY a.eventType")
    List<Object[]> countByEventTypeForHospitals(@Param("hospitalIds") Collection<UUID> hospitalIds);

    /**
     * QA D4307: the one per-hospital count of the cost pages. A row belongs to
     * the hospital stamped on it ({@code hospitalId}, V235) and, for a row
     * written before that stamp existed, to its assignment's hospital. The two
     * earlier queries each used a different one of those (the name snapshot,
     * the assignment) and so disagreed for the same period. The name is the
     * hospital's current one; the key is its id.
     *
     * <p>Returns {@code [hospitalId, hospitalName, count]}. Rows that belong to
     * no hospital (platform-wide events) are not attributed to any.
     */
    @Query("SELECT h.id, h.name, COUNT(a) "
           + "FROM AuditEventLog a "
           + "LEFT JOIN a.assignment asg "
           + "JOIN Hospital h ON h.id = COALESCE(a.hospitalId, asg.hospital.id) "
           + "WHERE a.eventTimestamp >= :from "
           + "AND a.eventTimestamp <= :to "
           + "GROUP BY h.id, h.name "
           + "ORDER BY h.name ASC, h.id ASC")
    List<Object[]> countAttributedToHospitalBetween(@Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /**
     * Hospital-scoped audit events, newest first: rows anchored at the
     * hospital through their assignment or, for a SYSTEM row, through their
     * own {@code hospitalId} (QA D786: a derived {@code findByAssignment_Hospital_Id}
     * query inner-joined the assignment and dropped every SYSTEM row).
     */
    @Query(value = "SELECT a FROM AuditEventLog a LEFT JOIN a.assignment asg "
           + "WHERE (asg.hospital.id = :hospitalId OR a.hospitalId = :hospitalId) "
           + "ORDER BY a.eventTimestamp DESC",
        countQuery = "SELECT COUNT(a) FROM AuditEventLog a LEFT JOIN a.assignment asg "
           + "WHERE (asg.hospital.id = :hospitalId OR a.hospitalId = :hospitalId)")
    Page<AuditEventLog> findAnchoredAtHospital(@Param("hospitalId") UUID hospitalId, Pageable pageable);

    /** Daily audit event counts for a hospital within a date range (anchored as {@link #findAnchoredAtHospital}). */
    @Query("SELECT function('date', a.eventTimestamp) AS day, COUNT(a) " +
           "FROM AuditEventLog a LEFT JOIN a.assignment asg " +
           "WHERE (asg.hospital.id = :hospitalId OR a.hospitalId = :hospitalId) " +
           "AND a.eventTimestamp >= :from " +
           "GROUP BY function('date', a.eventTimestamp) " +
           "ORDER BY function('date', a.eventTimestamp)")
    List<Object[]> countDailyByHospital(@Param("hospitalId") UUID hospitalId,
                                        @Param("from") LocalDateTime from);

    /**
     * Date-range query with optional from/to bounds. Both params are nullable —
     * if null the respective bound is ignored, returning all records in the other direction.
     * (The old passDistinctThrough hint is gone: Hibernate 6 removed the
     * setting, so it only logged HHH90003003.)
     */
    @Query("SELECT DISTINCT a FROM AuditEventLog a WHERE " +
           "(CAST(:fromDate AS LocalDateTime) IS NULL OR a.eventTimestamp >= :fromDate) AND " +
           "(CAST(:toDate AS LocalDateTime) IS NULL OR a.eventTimestamp <= :toDate) " +
           "ORDER BY a.eventTimestamp DESC")
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
           "(CAST(:fromDate AS LocalDateTime) IS NULL OR a.eventTimestamp >= :fromDate) AND " +
           "(CAST(:toDate AS LocalDateTime) IS NULL OR a.eventTimestamp <= :toDate) AND " +
           "a.eventType IN :eventTypes " +
           "ORDER BY a.eventTimestamp DESC")
    Page<AuditEventLog> findByDateRangeAndEventTypeIn(@Param("fromDate") LocalDateTime fromDate,
                                                     @Param("toDate") LocalDateTime toDate,
                                                     @Param("eventTypes") Collection<AuditEventType> eventTypes,
                                                     Pageable pageable);

    /**
     * Patient-scoped disclosure accounting (Tier 2 item 39). Keys on the
     * {@code patient_id} column V141 added, <b>not</b> on the older
     * {@code (entityType='PATIENT', resourceId=patientId)} convention —
     * break-the-glass rows key on the session id and eligibility rows on the
     * check id, so the convention never returned either, and emergency
     * access to a chart was invisible on the patient's own access list.
     *
     * <p>Event types are filtered by the caller against
     * {@link com.example.hms.enums.DisclosureCategory#accountableEventTypes()}
     * so that keying a new event type by patient does not silently publish it
     * to patients.
     *
     * <p>Date bounds are both nullable; a null bound is ignored.
     */
    @Query("SELECT DISTINCT a FROM AuditEventLog a WHERE "
           + "a.patientId = :patientId "
           + "AND a.eventType IN :eventTypes "
           + "AND (CAST(:fromDate AS LocalDateTime) IS NULL OR a.eventTimestamp >= :fromDate) "
           + "AND (CAST(:toDate AS LocalDateTime) IS NULL OR a.eventTimestamp <= :toDate) "
           + "ORDER BY a.eventTimestamp DESC")
    Page<AuditEventLog> findDisclosuresForPatient(@Param("patientId") UUID patientId,
                                                  @Param("eventTypes") Collection<AuditEventType> eventTypes,
                                                  @Param("fromDate") LocalDateTime fromDate,
                                                  @Param("toDate") LocalDateTime toDate,
                                                  Pageable pageable);

    /**
     * Review of #1290 (QA D3451): {@link #findDisclosuresForPatient} for the STAFF accounting at one hospital. A
     * row of {@code scopedType} (a result released to the family: it names the category of a sensitive result) is
     * returned only when it was written at {@code scopedHospitalId}; every other accountable row is returned as
     * before. Another hospital's release is never listed to this one's staff.
     */
    @Query("SELECT DISTINCT a FROM AuditEventLog a WHERE "
           + "a.patientId = :patientId "
           + "AND a.eventType IN :eventTypes "
           + "AND (a.eventType <> :scopedType OR a.hospitalId = :scopedHospitalId) "
           + "AND (CAST(:fromDate AS LocalDateTime) IS NULL OR a.eventTimestamp >= :fromDate) "
           + "AND (CAST(:toDate AS LocalDateTime) IS NULL OR a.eventTimestamp <= :toDate) "
           + "ORDER BY a.eventTimestamp DESC")
    Page<AuditEventLog> findDisclosuresForPatientAtHospital(@Param("patientId") UUID patientId,
                                                            @Param("eventTypes") Collection<AuditEventType> eventTypes,
                                                            @Param("scopedType") AuditEventType scopedType,
                                                            @Param("scopedHospitalId") UUID scopedHospitalId,
                                                            @Param("fromDate") LocalDateTime fromDate,
                                                            @Param("toDate") LocalDateTime toDate,
                                                            Pageable pageable);

    /** The counts of {@link #findDisclosuresForPatientAtHospital}, grouped as the unscoped counts are. */
    @Query("SELECT a.eventType AS eventType, a.entityType AS entityType, COUNT(a) AS cnt "
           + "FROM AuditEventLog a "
           + "WHERE a.patientId = :patientId "
           + "AND a.eventType IN :eventTypes "
           + "AND (a.eventType <> :scopedType OR a.hospitalId = :scopedHospitalId) "
           + "AND (CAST(:fromDate AS LocalDateTime) IS NULL OR a.eventTimestamp >= :fromDate) "
           + "AND (CAST(:toDate AS LocalDateTime) IS NULL OR a.eventTimestamp <= :toDate) "
           + "GROUP BY a.eventType, a.entityType")
    List<Object[]> countDisclosureCategoriesForPatientAtHospital(@Param("patientId") UUID patientId,
                                                                 @Param("eventTypes") Collection<AuditEventType> eventTypes,
                                                                 @Param("scopedType") AuditEventType scopedType,
                                                                 @Param("scopedHospitalId") UUID scopedHospitalId,
                                                                 @Param("fromDate") LocalDateTime fromDate,
                                                                 @Param("toDate") LocalDateTime toDate);

    /**
     * Per-category counts for one patient over the same window, so the
     * report can headline "2 emergency accesses, 1 release to another
     * hospital" without paging the whole history client-side. Grouped on
     * {@code (eventType, entityType)} because those two together are what
     * {@link com.example.hms.enums.DisclosureCategory#classify} needs —
     * {@code PATIENT_ACCESS} means a chart open or an insurance disclosure
     * depending on the entity type, and collapsing on event type alone would
     * merge them.
     */
    @Query("SELECT a.eventType AS eventType, a.entityType AS entityType, COUNT(a) AS cnt "
           + "FROM AuditEventLog a "
           + "WHERE a.patientId = :patientId "
           + "AND a.eventType IN :eventTypes "
           + "AND (CAST(:fromDate AS LocalDateTime) IS NULL OR a.eventTimestamp >= :fromDate) "
           + "AND (CAST(:toDate AS LocalDateTime) IS NULL OR a.eventTimestamp <= :toDate) "
           + "GROUP BY a.eventType, a.entityType")
    List<Object[]> countDisclosureCategoriesForPatient(@Param("patientId") UUID patientId,
                                                       @Param("eventTypes") Collection<AuditEventType> eventTypes,
                                                       @Param("fromDate") LocalDateTime fromDate,
                                                       @Param("toDate") LocalDateTime toDate);

    /**
     * A provider facility's OWN audit trail (provider plan §3.1,
     * {@code GET /provider/audit}), newest first.
     *
     * <p>A row is shown when:
     * <ul>
     *   <li>it is ANCHORED at the facility: its assignment is there (a write
     *       the interceptor recorded at the facility, a holder confirming their
     *       own invitation), or it records a role assignment whose target
     *       ({@code target_resource_id}) is an assignment there (a registration
     *       at the facility: the audit service unlinks the registrar's row from
     *       an assignment that is not theirs, so the target is the anchor);</li>
     *   <li>its ACTOR holds a seat at the facility (any assignment of theirs
     *       there): the facility's own staff and admin. A super-admin or a
     *       support user with no seat there is never shown as a facility
     *       actor, so their rows are excluded;</li>
     *   <li>its entity type is in the {@code entityTypes} ALLOWLIST (facility
     *       administration only: nothing clinical written at the facility can
     *       ever appear), it names no patient, and it is not about the PATIENT
     *       role.</li>
     * </ul>
     *
     * <p>The projection carries ids and codes only (plan §6.9): no
     * description, no details, no IP address. The role shown is the ACTOR's
     * own STAFF role at the facility: never PATIENT (defence in depth: the
     * facility guard refuses a PATIENT seat at a provider facility, but a row
     * that names one must still never show it), and PROVIDER_ADMIN
     * first when they hold it, otherwise the lowest other code, so an admin
     * who also dispenses is shown acting as the admin. The role a
     * role-assignment row assigned is its subject, a separate field.
     * Under impersonation the row's actor is the impersonated member; the
     * impersonator columns say who really acted (platform support).
     *
     * @param entityTypes the allowed entity types, exactly as the producers write them (upper-case)
     */
    @Query(value = "SELECT new com.example.hms.payload.dto.provider.ProviderAuditEntryDTO("
           + "a.id, a.eventTimestamp, a.eventType, a.status, a.user.id, a.userName, "
           + "COALESCE("
           + "CASE WHEN a.entityType <> 'USER_ROLE_ASSIGNMENT' THEN a.roleName ELSE NULL END, "
           + "(SELECT MIN(adminSeat.role.code) FROM UserRoleHospitalAssignment adminSeat "
           + "  WHERE adminSeat.user.id = a.user.id AND adminSeat.hospital.id = :facilityId "
           + "  AND adminSeat.active = true "
           + "  AND UPPER(adminSeat.role.code) IN ('PROVIDER_ADMIN', 'ROLE_PROVIDER_ADMIN')), "
           + "(SELECT MIN(seatRole.role.code) FROM UserRoleHospitalAssignment seatRole "
           + "  WHERE seatRole.user.id = a.user.id AND seatRole.hospital.id = :facilityId "
           + "  AND seatRole.active = true "
           + "  AND UPPER(seatRole.role.code) NOT IN ('PATIENT', 'ROLE_PATIENT')), "
           + "(SELECT MIN(anySeat.role.code) FROM UserRoleHospitalAssignment anySeat "
           + "  WHERE anySeat.user.id = a.user.id AND anySeat.hospital.id = :facilityId "
           + "  AND UPPER(anySeat.role.code) NOT IN ('PATIENT', 'ROLE_PATIENT'))), "
           + "a.entityType, a.resourceId, "
           + "CASE WHEN a.entityType = 'USER_ROLE_ASSIGNMENT' THEN a.roleName ELSE NULL END, "
           + "a.impersonatorUserId, a.impersonatorUsername) "
           + FACILITY_TRAIL_WHERE
           + "ORDER BY a.eventTimestamp DESC, a.id DESC",
        countQuery = "SELECT COUNT(a) " + FACILITY_TRAIL_WHERE)
    Page<ProviderAuditEntryDTO> findProviderFacilityTrail(@Param("facilityId") UUID facilityId,
                                                          @Param("entityTypes") Collection<String> entityTypes,
                                                          Pageable pageable);

    /** Counterpart of {@link #findByDateRangeAndEventTypeIn} — everything not in the set. */
    @Query("SELECT DISTINCT a FROM AuditEventLog a WHERE " +
           "(CAST(:fromDate AS LocalDateTime) IS NULL OR a.eventTimestamp >= :fromDate) AND " +
           "(CAST(:toDate AS LocalDateTime) IS NULL OR a.eventTimestamp <= :toDate) AND " +
           "a.eventType NOT IN :eventTypes " +
           "ORDER BY a.eventTimestamp DESC")
    Page<AuditEventLog> findByDateRangeAndEventTypeNotIn(@Param("fromDate") LocalDateTime fromDate,
                                                        @Param("toDate") LocalDateTime toDate,
                                                        @Param("eventTypes") Collection<AuditEventType> eventTypes,
                                                        Pageable pageable);
}
