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

    /**
     * QA D4102 (r81): whether an incident is already open for another urgent alert of the same patient at the
     * hospital. The administrators are flagged once for a patient's unanswered alerts, not once per alert.
     */
    @org.springframework.data.jpa.repository.Query("""
        SELECT COUNT(i) > 0 FROM UrgentAlertIncident i, com.example.hms.model.labor.ObstetricAlertEscalation e
        WHERE i.sourceType = 'URGENT_ALERT' AND i.sourceId = e.id AND i.resolvedAt IS NULL
          AND e.hospitalId = :hospitalId AND e.patientId = :patientId
        """)
    boolean existsOpenForPatient(@org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
                                 @org.springframework.data.repository.query.Param("patientId") UUID patientId);

    /** QA D4102: the register of a hospital: every open incident, and those opened since {@code since}. */
    @org.springframework.data.jpa.repository.Query("""
        SELECT i FROM UrgentAlertIncident i
        WHERE i.hospitalId = :hospitalId AND (i.resolvedAt IS NULL OR i.openedAt >= :since)
        ORDER BY i.openedAt DESC
        """)
    List<UrgentAlertIncident> findRegister(@org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
                                           @org.springframework.data.repository.query.Param("since") java.time.LocalDateTime since);
}
