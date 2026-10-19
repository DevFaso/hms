package com.example.hms.repository;

import com.example.hms.model.NurseHandoff;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NurseHandoffRepository extends JpaRepository<NurseHandoff, UUID> {

    List<NurseHandoff> findByHospital_IdAndStatusOrderByCreatedAtDesc(
        UUID hospitalId, String status, Pageable pageable);

    /** Tenant guard — every single-row lookup goes through id + hospital. */
    Optional<NurseHandoff> findByIdAndHospital_Id(UUID id, UUID hospitalId);

    long countByHospital_IdAndStatus(UUID hospitalId, String status);

    /** QA D234: a patient's open handoffs at one hospital, closed when the stay ends. */
    List<NurseHandoff> findByPatient_IdAndHospital_IdAndStatus(UUID patientId, UUID hospitalId, String status);
}
