package com.example.hms.repository.pharmacy;

import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.enums.RoutingType;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PrescriptionRoutingDecisionRepository extends JpaRepository<PrescriptionRoutingDecision, UUID> {

    List<PrescriptionRoutingDecision> findByPrescriptionId(UUID prescriptionId);

    List<PrescriptionRoutingDecision> findByPrescriptionIdOrderByDecidedAtDesc(UUID prescriptionId);

    List<PrescriptionRoutingDecision> findByDecidedForPatientIdOrderByDecidedAtDesc(UUID patientId);

    Page<PrescriptionRoutingDecision> findByPrescriptionId(UUID prescriptionId, Pageable pageable);

    Page<PrescriptionRoutingDecision> findByDecidedForPatientId(UUID patientId, Pageable pageable);

    /** T-59: decisions that are still pending past a given timestamp (used by the scheduler). */
    List<PrescriptionRoutingDecision> findByRoutingTypeAndStatusAndDecidedAtBefore(
            RoutingType routingType, RoutingDecisionStatus status, LocalDateTime before);

    /** T-55: lookup PENDING partner decisions by short-token prefix from inbound SMS replies. */
    List<PrescriptionRoutingDecision> findByRoutingTypeAndStatus(
            RoutingType routingType, RoutingDecisionStatus status);
}
