package com.example.hms.repository;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.LabOrder;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface LabOrderRepository extends JpaRepository<LabOrder, UUID>, LabOrderCustomRepository {
    List<LabOrder> findByPatient_Id(UUID patientId);

    /**
     * The order row under a write lock, for the release path that decides
     * whether every result is released: two concurrent releases must
     * serialise on the order or both see the other's result as unreleased.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM LabOrder o WHERE o.id = :id")
    java.util.Optional<LabOrder> findWithLockById(@Param("id") UUID id);

    /**
     * Compare-and-set on the status, as a statement rather than a dirty field.
     *
     * <p>The entry path loads the order UNLOCKED (so the permission checks do
     * not hold a row lock) and only then takes the write lock, which means
     * Hibernate's snapshot for that instance holds the status as it was BEFORE
     * the lock. Writing through the entity is then unreliable in exactly the
     * case that matters: when the target equals the snapshot value — snapshot
     * RESULTED, database COMPLETED, target RESULTED — the dirty check sees no
     * change and flushes nothing, so a re-opened order silently stayed
     * COMPLETED and the amendment never reached the doctor's queue.
     *
     * <p>{@code expected} is the status read under the lock: the update is a
     * no-op (returns 0) if anything moved the row in between, so the caller
     * learns rather than overwrites.
     *
     * @return the number of rows updated: 1, or 0 if the status is no longer
     *         {@code expected}
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE LabOrder o SET o.status = :target, o.updatedAt = CURRENT_TIMESTAMP "
        + "WHERE o.id = :id AND o.status = :expected")
    int updateStatusFrom(@Param("id") UUID id,
                         @Param("expected") LabOrderStatus expected,
                         @Param("target") LabOrderStatus target);

    /**
     * The status as the database currently holds it, bypassing the entity
     * instance this persistence context may already have: a scalar projection
     * is not served from the first-level cache, so a status another
     * transaction committed (a cancellation) is visible here even though the
     * managed order still carries the value it was loaded with.
     */
    @Query("SELECT o.status FROM LabOrder o WHERE o.id = :id")
    LabOrderStatus findStatusById(@Param("id") UUID id);
    List<LabOrder> findByOrderingStaff_Id(UUID staffId);
    List<LabOrder> findByLabTestDefinition_Id(UUID labTestDefinitionId);
    List<LabOrder> findByStatus(LabOrderStatus status);

    // Hospital-scoped queries for tenant isolation
    List<LabOrder> findByHospital_Id(UUID hospitalId);

    // ── Audit gap B1: an order is handled by its ordering hospital AND by the
    // laboratory that performs it (performing_hospital_id, V161). The lab-side
    // worklists read "ordered here OR sent to us". ──────────────────────────

    /** Every order the hospital orders or performs. */
    @Query("""
        SELECT o FROM LabOrder o
        WHERE o.hospital.id = :hospitalId
           OR o.performingHospital.id = :hospitalId
    """)
    List<LabOrder> findHandledBy(@Param("hospitalId") UUID hospitalId);

    /** Orders in a status that the hospital orders or performs. */
    @Query("""
        SELECT o FROM LabOrder o
        WHERE o.status = :status
          AND (o.hospital.id = :hospitalId OR o.performingHospital.id = :hospitalId)
    """)
    List<LabOrder> findByStatusHandledBy(@Param("status") LabOrderStatus status,
                                         @Param("hospitalId") UUID hospitalId);

    /**
     * E9 #59b widened by B1: the patient's orders across the readable hospitals,
     * plus the orders sent to the acting hospital's laboratory to perform.
     */
    @Query("""
        SELECT o FROM LabOrder o
        WHERE o.patient.id = :patientId
          AND (o.hospital.id IN :hospitalIds OR o.performingHospital.id = :performingHospitalId)
    """)
    List<LabOrder> findByPatientIdReadableOrPerformedAt(@Param("patientId") UUID patientId,
                                                        @Param("hospitalIds") java.util.Collection<UUID> hospitalIds,
                                                        @Param("performingHospitalId") UUID performingHospitalId);
    List<LabOrder> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId);
    /** E9 #59b — lab orders across the readable hospitals ({@code RecordAccessPolicy.readableHospitalIds}). */
    List<LabOrder> findByPatient_IdAndHospital_IdIn(UUID patientId, java.util.Collection<UUID> hospitalIds);

    /** FHIR ServiceRequest/DiagnosticReport search (Tier 2 item 42): newest first, capped by the caller. */
    org.springframework.data.domain.Page<LabOrder>
        findByPatient_IdAndHospital_IdOrderByOrderDatetimeDesc(
            UUID patientId, UUID hospitalId, org.springframework.data.domain.Pageable pageable);
    List<LabOrder> findByOrderingStaff_IdAndHospital_Id(UUID staffId, UUID hospitalId);
    List<LabOrder> findByStatusAndHospital_Id(LabOrderStatus status, UUID hospitalId);
    List<LabOrder> findByHospital_IdAndStatusIn(UUID hospitalId, java.util.Collection<LabOrderStatus> statuses);

    @Query(value = """
    SELECT * FROM lab_orders l
    WHERE (:patientId IS NULL OR l.patient_id = CAST(:patientId AS uuid))
      AND (CAST(:startDate AS LocalDateTime) IS NULL OR l.order_datetime >= CAST(:startDate AS timestamp))
      AND (CAST(:endDate AS LocalDateTime) IS NULL OR l.order_datetime <= CAST(:endDate AS timestamp))
    """,
            countQuery = "SELECT count(*) FROM lab_orders l WHERE (:patientId IS NULL OR l.patient_id = CAST(:patientId AS uuid)) AND (CAST(:startDate AS LocalDateTime) IS NULL OR l.order_datetime >= CAST(:startDate AS timestamp)) AND (CAST(:endDate AS LocalDateTime) IS NULL OR l.order_datetime <= CAST(:endDate AS timestamp))",
            nativeQuery = true)
    Page<LabOrder> searchNative(
            @Param("patientId") UUID patientId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable
    );

    boolean existsByPatient_IdAndLabTestDefinition_IdAndOrderDatetime(UUID id, UUID id1, @NotNull LocalDateTime orderDatetime);

    /** True when the encounter has at least one lab order whose status is NOT in the given terminal set. */
    boolean existsByEncounter_IdAndStatusNotIn(UUID encounterId, java.util.Collection<LabOrderStatus> terminalStatuses);

    // Count lab orders placed by a specific ordering staff with a given status
    long countByOrderingStaff_IdAndStatus(UUID staffId, LabOrderStatus status);

    /**
     * Orders of one provider sitting in any of {@code statuses} — the critical
     * strip's "still with the laboratory" tile. A per-status count cannot
     * express it any more: the lifecycle now moves an order through COLLECTED
     * and RECEIVED as well.
     */
    long countByOrderingStaff_IdAndStatusIn(UUID staffId, java.util.Collection<LabOrderStatus> statuses);

    // ── Dashboard count queries ──────────────────────────────────────────────

    /** Orders in a hospital within a datetime window. */
    long countByHospital_IdAndOrderDatetimeBetween(UUID hospitalId,
                                                    LocalDateTime from,
                                                    LocalDateTime to);

    /** Orders with a specific status in a hospital within a datetime window. */
    long countByHospital_IdAndStatusAndOrderDatetimeBetween(UUID hospitalId,
                                                             LabOrderStatus status,
                                                             LocalDateTime from,
                                                             LocalDateTime to);

    /** Orders matching any of the given statuses in a hospital. */
    @Query("""
        SELECT COUNT(o) FROM LabOrder o
        WHERE o.hospital.id = :hospitalId
          AND o.status IN :statuses
    """)
    long countByHospitalIdAndStatusIn(@Param("hospitalId") UUID hospitalId,
                                      @Param("statuses") java.util.List<LabOrderStatus> statuses);

    /**
     * Average turnaround time in minutes for COMPLETED orders in a hospital completed today.
     * TAT = updatedAt - orderDatetime (updatedAt is the completion timestamp for COMPLETED orders).
     * Returns null when no completed orders exist in the window.
     */
    @Query(value = """
        SELECT AVG(EXTRACT(EPOCH FROM (o.updated_at - o.order_datetime)) / 60.0)
        FROM lab.lab_orders o
        WHERE o.hospital_id = :hospitalId
          AND o.status = 'COMPLETED'
          AND o.updated_at >= :from
          AND o.updated_at <= :to
    """, nativeQuery = true)
    Double avgTurnaroundMinutes(@Param("hospitalId") UUID hospitalId,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to);

    // ── Lab Ops Dashboard queries ────────────────────────────────────────────

    /** Count active orders by priority in a hospital (priority stored as string). */
    @Query("""
        SELECT COUNT(o) FROM LabOrder o
        WHERE o.hospital.id = :hospitalId
          AND o.priority = :priority
          AND o.status IN :activeStatuses
    """)
    long countByHospitalIdAndPriorityAndStatusIn(
            @Param("hospitalId") UUID hospitalId,
            @Param("priority") String priority,
            @Param("activeStatuses") java.util.List<LabOrderStatus> activeStatuses);

    /** Count active orders older than the given cutoff (potential bottlenecks). */
    @Query("""
        SELECT COUNT(o) FROM LabOrder o
        WHERE o.hospital.id = :hospitalId
          AND o.status IN :activeStatuses
          AND o.orderDatetime < :cutoff
    """)
    long countByHospitalIdAndStatusInAndOrderDatetimeBefore(
            @Param("hospitalId") UUID hospitalId,
            @Param("activeStatuses") java.util.List<LabOrderStatus> activeStatuses,
            @Param("cutoff") LocalDateTime cutoff);

    /** Status breakdown — returns [status, count] rows for a hospital (active statuses). */
    @Query("""
        SELECT o.status, COUNT(o) FROM LabOrder o
        WHERE o.hospital.id = :hospitalId
          AND o.status IN :statuses
        GROUP BY o.status
    """)
    List<Object[]> countGroupedByStatus(
            @Param("hospitalId") UUID hospitalId,
            @Param("statuses") java.util.List<LabOrderStatus> statuses);

    /** Priority breakdown — returns [priority, count] rows for active orders in a hospital. */
    @Query("""
        SELECT o.priority, COUNT(o) FROM LabOrder o
        WHERE o.hospital.id = :hospitalId
          AND o.status IN :activeStatuses
        GROUP BY o.priority
    """)
    List<Object[]> countGroupedByPriority(
            @Param("hospitalId") UUID hospitalId,
            @Param("activeStatuses") java.util.List<LabOrderStatus> activeStatuses);

    /** Hospital-scoped tile count for the super-admin dashboard. */
    long countByHospital_Id(UUID hospitalId);
}
