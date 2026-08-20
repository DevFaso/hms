package com.example.hms.repository;

import com.example.hms.enums.LaborStatus;
import com.example.hms.model.labor.LaborEpisode;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LaborEpisodeRepository extends JpaRepository<LaborEpisode, UUID> {

    Optional<LaborEpisode> findFirstByPatient_IdAndHospital_IdAndStatusOrderByAdmittedAtDesc(
        UUID patientId, UUID hospitalId, LaborStatus status);

    boolean existsByPatient_IdAndHospital_IdAndStatus(UUID patientId, UUID hospitalId, LaborStatus status);

    List<LaborEpisode> findByPatient_IdAndHospital_IdOrderByAdmittedAtDesc(
        UUID patientId, UUID hospitalId, Pageable pageable);

    /** Tenant guard — single-row lookups go through id + hospital. */
    Optional<LaborEpisode> findByIdAndHospital_Id(UUID id, UUID hospitalId);
}
