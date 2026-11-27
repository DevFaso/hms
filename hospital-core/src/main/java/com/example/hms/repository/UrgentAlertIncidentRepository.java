package com.example.hms.repository;

import com.example.hms.model.UrgentAlertIncident;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The register of unresolved urgent alerts (V386). */
public interface UrgentAlertIncidentRepository extends JpaRepository<UrgentAlertIncident, UUID> {

    /** The open incident of an alert: at most one (the partial unique index of V386). */
    Optional<UrgentAlertIncident> findBySourceTypeAndSourceIdAndResolvedAtIsNull(String sourceType, UUID sourceId);

    List<UrgentAlertIncident> findBySourceTypeAndSourceIdOrderByOpenedAtDesc(String sourceType, UUID sourceId);

    List<UrgentAlertIncident> findByHospitalIdOrderByOpenedAtDesc(UUID hospitalId);

    /** The open incidents of one kind of source, so a source acknowledged since can close its own. */
    List<UrgentAlertIncident> findBySourceTypeAndResolvedAtIsNull(String sourceType);
}
