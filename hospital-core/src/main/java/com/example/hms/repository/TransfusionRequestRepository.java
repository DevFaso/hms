package com.example.hms.repository;

import com.example.hms.enums.TransfusionRequestStatus;
import com.example.hms.model.TransfusionRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TransfusionRequestRepository extends JpaRepository<TransfusionRequest, UUID> {

    List<TransfusionRequest> findByHospital_IdOrderByRequestedAtDesc(UUID hospitalId);

    List<TransfusionRequest> findByHospital_IdAndStatusOrderByRequestedAtDesc(
        UUID hospitalId, TransfusionRequestStatus status);

    List<TransfusionRequest> findByPatient_IdAndHospital_IdOrderByRequestedAtDesc(UUID patientId, UUID hospitalId);
}
