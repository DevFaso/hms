package com.example.hms.repository;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.model.LabOrder;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface LabOrderRepository extends JpaRepository<LabOrder, UUID>, LabOrderCustomRepository {
    List<LabOrder> findByPatient_Id(UUID patientId);
    List<LabOrder> findByOrderingStaff_Id(UUID staffId);
    List<LabOrder> findByLabTestDefinition_Id(UUID labTestDefinitionId);
    List<LabOrder> findByStatus(LabOrderStatus status);

    // Hospital-scoped queries for tenant isolation
    List<LabOrder> findByHospital_Id(UUID hospitalId);
    List<LabOrder> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId);
    List<LabOrder> findByOrderingStaff_IdAndHospital_Id(UUID staffId, UUID hospitalId);
    List<LabOrder> findByStatusAndHospital_Id(LabOrderStatus status, UUID hospitalId);

    @Query(value = """
    SELECT * FROM lab_orders l
    WHERE (:patientId IS NULL OR l.patient_id = CAST(:patientId AS uuid))
      AND (:startDate IS NULL OR l.order_datetime >= CAST(:startDate AS timestamp))
      AND (:endDate IS NULL OR l.order_datetime <= CAST(:endDate AS timestamp))
    """,
            countQuery = "SELECT count(*) FROM lab_orders l WHERE (:patientId IS NULL OR l.patient_id = CAST(:patientId AS uuid)) AND (:startDate IS NULL OR l.order_datetime >= CAST(:startDate AS timestamp)) AND (:endDate IS NULL OR l.order_datetime <= CAST(:endDate AS timestamp))",
            nativeQuery = true)
    Page<LabOrder> searchNative(
            @Param("patientId") UUID patientId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable
    );

    boolean existsByPatient_IdAndLabTestDefinition_IdAndOrderDatetime(UUID id, UUID id1, @NotNull LocalDateTime orderDatetime);

    // Count lab orders placed by a specific ordering staff with a given status
    long countByOrderingStaff_IdAndStatus(UUID staffId, LabOrderStatus status);

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
}
