package com.example.hms.repository;

import com.example.hms.model.CriticalResultIncident;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The register of unresolved critical results (V363). Read by hospital. */
public interface CriticalResultIncidentRepository extends JpaRepository<CriticalResultIncident, UUID> {

    /** The open incident of a result: at most one (V364's partial unique index). */
    Optional<CriticalResultIncident> findByLabResultIdAndResolvedAtIsNull(UUID labResultId);

    /** Every incident of a result, the newest first: a result amended to another critical value may have several. */
    List<CriticalResultIncident> findByLabResultIdOrderByOpenedAtDesc(UUID labResultId);

    List<CriticalResultIncident> findByHospitalIdOrderByOpenedAtDesc(UUID hospitalId);
}
