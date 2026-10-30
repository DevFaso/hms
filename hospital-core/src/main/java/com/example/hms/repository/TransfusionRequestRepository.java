package com.example.hms.repository;

import com.example.hms.enums.TransfusionRequestStatus;
import com.example.hms.model.TransfusionRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TransfusionRequestRepository extends JpaRepository<TransfusionRequest, UUID> {

    /** QA D972: a visit's transfusion requests in the given statuses, for the checkout charge. */
    List<TransfusionRequest> findByEncounter_IdAndStatusIn(UUID encounterId, java.util.Collection<TransfusionRequestStatus> statuses);

    List<TransfusionRequest> findByHospital_IdOrderByRequestedAtDesc(UUID hospitalId);

    List<TransfusionRequest> findByHospital_IdAndStatusOrderByRequestedAtDesc(
        UUID hospitalId, TransfusionRequestStatus status);

    List<TransfusionRequest> findByPatient_IdAndHospital_IdOrderByRequestedAtDesc(UUID patientId, UUID hospitalId);
}
