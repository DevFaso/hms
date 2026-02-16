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
}
