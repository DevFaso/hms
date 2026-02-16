package com.example.hms.repository;

import com.example.hms.enums.RefillStatus;
import com.example.hms.model.RefillRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface RefillRequestRepository extends JpaRepository<RefillRequest, UUID> {

    Page<RefillRequest> findByPatientId(UUID patientId, Pageable pageable);

    Page<RefillRequest> findByPatientIdAndStatus(UUID patientId, RefillStatus status, Pageable pageable);

    Page<RefillRequest> findByPrescriptionId(UUID prescriptionId, Pageable pageable);
}
