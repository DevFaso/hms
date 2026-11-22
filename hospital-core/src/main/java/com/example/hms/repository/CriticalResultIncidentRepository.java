package com.example.hms.repository;

import com.example.hms.model.CriticalResultIncident;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The register of unresolved critical results (V363). Read by hospital. */
public interface CriticalResultIncidentRepository extends JpaRepository<CriticalResultIncident, UUID> {

    Optional<CriticalResultIncident> findByLabResultId(UUID labResultId);

    List<CriticalResultIncident> findByHospitalIdOrderByOpenedAtDesc(UUID hospitalId);
}
