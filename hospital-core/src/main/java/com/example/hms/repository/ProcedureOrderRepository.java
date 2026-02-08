package com.example.hms.repository;

import com.example.hms.enums.ProcedureOrderStatus;
import com.example.hms.model.ProcedureOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ProcedureOrderRepository extends JpaRepository<ProcedureOrder, UUID> {

    List<ProcedureOrder> findByPatient_IdOrderByOrderedAtDesc(UUID patientId);

    List<ProcedureOrder> findByHospital_IdAndStatusOrderByScheduledDatetimeAsc(UUID hospitalId, ProcedureOrderStatus status);

    List<ProcedureOrder> findByOrderingProvider_IdOrderByOrderedAtDesc(UUID providerId);

    List<ProcedureOrder> findByHospital_IdAndScheduledDatetimeBetween(
        UUID hospitalId, 
        LocalDateTime startDate, 
        LocalDateTime endDate
    );

    List<ProcedureOrder> findByStatusAndConsentObtainedFalse(ProcedureOrderStatus status);
}
