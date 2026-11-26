package com.example.hms.repository;

import com.example.hms.model.OPositiveReleaseDecision;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OPositiveReleaseDecisionRepository extends JpaRepository<OPositiveReleaseDecision, UUID> {

    /** The row under its lock: the countersignature, the review and the follow-up are each written once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM OPositiveReleaseDecision d WHERE d.id = :id")
    Optional<OPositiveReleaseDecision> lockById(@Param("id") UUID id);

    List<OPositiveReleaseDecision> findByRelease_IdIn(Collection<UUID> releaseIds);

    /** The releases of one hospital, the newest first. */
    List<OPositiveReleaseDecision> findByHospital_IdOrderByIssuedAtDesc(UUID hospitalId);

    /**
     * The exposures of one patient at EVERY hospital, the newest first: the flag follows the patient, as the
     * antibody flag does. Deliberately not narrowed to a hospital.
     */
    List<OPositiveReleaseDecision> findByPatientIdAndRhdExposureTrueOrderByIssuedAtDesc(UUID patientId);

    /**
     * Marks the antibody screen of this patient's follow-ups whose window has opened, at every hospital, in one
     * statement that writes that column only: a countersignature, a review or a follow-up recorded at the same
     * moment on the same row is never written over by a stale copy.
     *
     * @return the number of follow-ups that now have their screen
     */
    @Modifying(flushAutomatically = true)
    @Query("""
        UPDATE OPositiveReleaseDecision d
        SET d.antibodyScreenDoneAt = :performedAt, d.updatedAt = :performedAt
        WHERE d.patientId = :patientId
          AND d.followUpRequired = TRUE
          AND d.antibodyScreenDoneAt IS NULL
          AND d.antibodyScreenDueFrom <= :performedOn
        """)
    int markAntibodyScreenDone(@Param("patientId") UUID patientId, @Param("performedAt") LocalDateTime performedAt,
                               @Param("performedOn") java.time.LocalDate performedOn);

    /** Countersignatures past their deadline that nobody was told about yet, at every hospital. */
    @Query("""
        SELECT d.id FROM OPositiveReleaseDecision d
        WHERE d.countersignDueAt < :now
          AND d.countersignedAt IS NULL
          AND d.countersignEscalatedAt IS NULL
        ORDER BY d.countersignDueAt
        """)
    List<UUID> findOverdueCountersignIds(@Param("now") LocalDateTime now);
}
