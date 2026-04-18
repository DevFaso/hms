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

    List<ProcedureOrder> findByPatient_IdAndHospital_IdOrderByOrderedAtDesc(UUID patientId, UUID hospitalId);

    List<ProcedureOrder> findByHospital_IdAndStatusOrderByScheduledDatetimeAsc(UUID hospitalId, ProcedureOrderStatus status);

    List<ProcedureOrder> findByHospital_IdOrderByOrderedAtDesc(UUID hospitalId);

    List<ProcedureOrder> findByOrderingProvider_IdOrderByOrderedAtDesc(UUID providerId);

    List<ProcedureOrder> findByOrderingProvider_IdAndHospital_IdOrderByOrderedAtDesc(UUID providerId, UUID hospitalId);

    List<ProcedureOrder> findByHospital_IdAndScheduledDatetimeBetween(
        UUID hospitalId, 
        LocalDateTime startDate, 
        LocalDateTime endDate
    );

    List<ProcedureOrder> findByStatusAndConsentObtainedFalse(ProcedureOrderStatus status);

    List<ProcedureOrder> findByHospital_IdAndStatusAndConsentObtainedFalse(UUID hospitalId, ProcedureOrderStatus status);

    /** True when the encounter has at least one procedure order whose status is NOT in the given terminal set. */
    boolean existsByEncounter_IdAndStatusNotIn(UUID encounterId, java.util.Collection<ProcedureOrderStatus> terminalStatuses);
}
