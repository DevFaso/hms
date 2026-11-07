package com.example.hms.repository;

import com.example.hms.model.TransfusionCrossmatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransfusionCrossmatchRepository extends JpaRepository<TransfusionCrossmatch, UUID> {

    /** One verdict per (request, unit) — re-testing overwrites rather than accumulating. */
    Optional<TransfusionCrossmatch> findByRequest_IdAndBloodUnit_Id(UUID requestId, UUID bloodUnitId);

    List<TransfusionCrossmatch> findByRequest_IdOrderByPerformedAtDesc(UUID requestId);

    /**
     * QA CX-012: an incompatible result that stands for this bag and patient, on another of their requests: written
     * while the bag was out, or on file when it was released knowing it, which writes nothing on the row.
     */
    @Query("SELECT COUNT(c) > 0 FROM TransfusionCrossmatch c WHERE c.bloodUnit.id = :unitId "
        + "AND c.request.patient.id = :patientId AND c.request.id <> :requestId "
        + "AND c.compatible = FALSE AND (c.unitIssued = TRUE OR EXISTS (SELECT e.id FROM EmergencyBloodRelease e "
        + "WHERE e.request = c.request AND e.bloodUnit = c.bloodUnit "
        + "AND e.releaseType = com.example.hms.enums.EmergencyReleaseType.SEROLOGICALLY_INCOMPATIBLE))")
    boolean existsStandingIncompatibleForPatient(@Param("unitId") UUID unitId,
                                                 @Param("patientId") UUID patientId,
                                                 @Param("requestId") UUID requestId);

    /** The bags found incompatible for this patient, on any of their requests. */
    @Query("SELECT c.bloodUnit.id FROM TransfusionCrossmatch c WHERE c.request.patient.id = :patientId AND c.compatible = FALSE")
    List<UUID> findIncompatibleUnitIdsForPatient(@Param("patientId") UUID patientId);
}
