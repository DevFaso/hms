package com.example.hms.repository;

import com.example.hms.model.IncompatibleReleaseDecision;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncompatibleReleaseDecisionRepository extends JpaRepository<IncompatibleReleaseDecision, UUID> {

    /** The row under its lock, for the system's own sweeps, which act at every hospital. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM IncompatibleReleaseDecision d WHERE d.id = :id")
    Optional<IncompatibleReleaseDecision> lockById(@Param("id") UUID id);

    /**
     * The row of one hospital under its lock, for a user's action: a row of another hospital is not found, and
     * is neither read nor locked.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM IncompatibleReleaseDecision d WHERE d.id = :id AND d.hospital.id = :hospitalId")
    Optional<IncompatibleReleaseDecision> lockByIdAndHospital(@Param("id") UUID id, @Param("hospitalId") UUID hospitalId);

    List<IncompatibleReleaseDecision> findByRelease_IdIn(Collection<UUID> releaseIds);

    /** The releases of one hospital, the newest first. */
    List<IncompatibleReleaseDecision> findByHospital_IdOrderByIssuedAtDesc(UUID hospitalId);

    /** The releases of this bag whose monitoring is still open (the bag is, or was last known, out). */
    @Query("SELECT d FROM IncompatibleReleaseDecision d WHERE d.bloodUnitId = :bloodUnitId AND d.monitoringOutcome IS NULL")
    List<IncompatibleReleaseDecision> findOpenForUnit(@Param("bloodUnitId") UUID bloodUnitId);

    /** Whether this patient ever received a bag released despite an incompatible crossmatch, at any hospital. */
    boolean existsByPatientId(UUID patientId);

    /** The releases whose notices are still owed, at every hospital, the oldest first. */
    @Query("SELECT d.id FROM IncompatibleReleaseDecision d WHERE d.noticesFiledAt IS NULL ORDER BY d.issuedAt")
    List<UUID> findPendingNoticeIds();

    /** Countersignatures past their deadline that nobody was told about yet, at every hospital. */
    @Query("""
        SELECT d.id FROM IncompatibleReleaseDecision d
        WHERE d.countersignDueAt < :now
          AND d.countersignedAt IS NULL
          AND d.countersignEscalatedAt IS NULL
        ORDER BY d.countersignDueAt
        """)
    List<UUID> findOverdueCountersignIds(@Param("now") LocalDateTime now);

    /** How many times this bag was hung on the request it was released against. */
    @Query("""
        SELECT COUNT(a) FROM TransfusionAdministration a
        WHERE a.bloodUnit.id = :bloodUnitId AND a.request.id = :requestId
        """)
    long countAdministrations(@Param("bloodUnitId") UUID bloodUnitId, @Param("requestId") UUID requestId);
}
