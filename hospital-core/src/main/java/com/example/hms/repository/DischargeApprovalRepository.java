package com.example.hms.repository;

import com.example.hms.enums.DischargeStatus;
import com.example.hms.model.DischargeApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DischargeApprovalRepository extends JpaRepository<DischargeApproval, UUID> {

  List<DischargeApproval> findByHospital_IdAndStatusIn(UUID hospitalId, Collection<DischargeStatus> statuses);

  List<DischargeApproval> findByRegistration_IdAndStatusIn(UUID registrationId, Collection<DischargeStatus> statuses);

    @Query("""
        SELECT d FROM DischargeApproval d
        WHERE d.patient.id = :patientId
          AND d.status IN :statuses
        ORDER BY d.requestedAt DESC
    """)
    List<DischargeApproval> findActiveForPatient(@Param("patientId") UUID patientId,
                                                  @Param("statuses") Collection<DischargeStatus> statuses);

    Optional<DischargeApproval> findFirstByPatient_IdAndStatusOrderByRequestedAtDesc(UUID patientId, DischargeStatus status);
}
