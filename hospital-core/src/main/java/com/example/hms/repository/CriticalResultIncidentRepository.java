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

    /** QA D4102 (r81): the register of a hospital: every open incident, and those opened since {@code since}. */
    @org.springframework.data.jpa.repository.Query("""
        SELECT i FROM CriticalResultIncident i
        WHERE i.hospitalId = :hospitalId AND (i.resolvedAt IS NULL OR i.openedAt >= :since)
        ORDER BY i.openedAt DESC
        """)
    List<CriticalResultIncident> findRegister(@org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
                                              @org.springframework.data.repository.query.Param("since") java.time.LocalDateTime since);
}
