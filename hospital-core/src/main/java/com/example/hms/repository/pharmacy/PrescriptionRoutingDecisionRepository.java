package com.example.hms.repository.pharmacy;

import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PrescriptionRoutingDecisionRepository extends JpaRepository<PrescriptionRoutingDecision, UUID> {

    List<PrescriptionRoutingDecision> findByPrescriptionId(UUID prescriptionId);

    List<PrescriptionRoutingDecision> findByPrescriptionIdOrderByDecidedAtDesc(UUID prescriptionId);

    List<PrescriptionRoutingDecision> findByDecidedForPatientIdOrderByDecidedAtDesc(UUID patientId);

    Page<PrescriptionRoutingDecision> findByPrescriptionId(UUID prescriptionId, Pageable pageable);

    Page<PrescriptionRoutingDecision> findByDecidedForPatientId(UUID patientId, Pageable pageable);
}
