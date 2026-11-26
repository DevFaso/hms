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

    /** The row under its lock, for the system's own sweeps, which act at every hospital. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM OPositiveReleaseDecision d WHERE d.id = :id")
    Optional<OPositiveReleaseDecision> lockById(@Param("id") UUID id);

    /**
     * The row of one hospital under its lock, for a user's action: a row of another hospital is not found, and
     * is neither read nor locked.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM OPositiveReleaseDecision d WHERE d.id = :id AND d.hospital.id = :hospitalId")
    Optional<OPositiveReleaseDecision> lockByIdAndHospital(@Param("id") UUID id, @Param("hospitalId") UUID hospitalId);

    List<OPositiveReleaseDecision> findByRelease_IdIn(Collection<UUID> releaseIds);

    /** The releases whose notices are still owed, at every hospital, the oldest first. */
    @Query("SELECT d.id FROM OPositiveReleaseDecision d WHERE d.noticesFiledAt IS NULL ORDER BY d.issuedAt")
    List<UUID> findPendingNoticeIds();

    /**
     * How many times this bag was hung ON THIS REQUEST: it exposed this patient only when it was given on the
     * request it was released against. The same bag hung later on another request exposed somebody else.
     */
    @Query("""
        SELECT COUNT(a) FROM TransfusionAdministration a
        WHERE a.bloodUnit.id = :bloodUnitId AND a.request.id = :requestId
        """)
    long countAdministrations(@Param("bloodUnitId") UUID bloodUnitId, @Param("requestId") UUID requestId);

    /** The releases of one hospital, the newest first. */
    List<OPositiveReleaseDecision> findByHospital_IdOrderByIssuedAtDesc(UUID hospitalId);

    /** One O positive release to a patient at risk, with what became of the bag. */
    interface ExposureRow {
        LocalDateTime getIssuedAt();

        UUID getHospitalId();

        String getHospitalName();

        /** The request the bag was released against. */
        UUID getRequestId();

        com.example.hms.enums.BloodUnitStatus getUnitStatus();

        /** The request that holds the bag now; null when it is back in stock. */
        UUID getUnitRequestId();

        /** How many times the bag was hung on the request of this release. */
        long getAdministrations();
    }

    /**
     * The O positive releases to this patient at EVERY hospital, the newest first, each with the state of its
     * bag: the flag follows the patient, as the antibody flag does. Deliberately not narrowed to a hospital; one
     * read, no entity loaded.
     */
    @Query("""
        SELECT d.issuedAt AS issuedAt, h.id AS hospitalId, h.name AS hospitalName, d.requestId AS requestId,
               u.status AS unitStatus, ur.id AS unitRequestId,
               (SELECT COUNT(a) FROM TransfusionAdministration a
                 WHERE a.bloodUnit.id = d.bloodUnitId AND a.request.id = d.requestId) AS administrations
        FROM OPositiveReleaseDecision d
        JOIN d.hospital h
        JOIN BloodUnit u ON u.id = d.bloodUnitId
        LEFT JOIN u.request ur
        WHERE d.patientId = :patientId AND d.rhdExposure = TRUE
        ORDER BY d.issuedAt DESC
        """)
    List<ExposureRow> findExposures(@Param("patientId") UUID patientId);

    /**
     * Marks the antibody screen of this patient's follow-ups at ONE hospital whose window has opened, in one
     * statement that writes that column only: a countersignature, a review or a follow-up recorded at the same
     * moment on the same row is never written over by a stale copy. A screen recorded at one hospital closes
     * nothing in the record of another.
     *
     * @return the number of follow-ups that now have their screen
     */
    @Modifying(flushAutomatically = true)
    @Query("""
        UPDATE OPositiveReleaseDecision d
        SET d.antibodyScreenDoneAt = :performedAt, d.updatedAt = :performedAt
        WHERE d.patientId = :patientId
          AND d.hospital.id = :hospitalId
          AND d.followUpRequired = TRUE
          AND d.antibodyScreenDoneAt IS NULL
          AND d.antibodyScreenDueFrom <= :performedOn
        """)
    int markAntibodyScreenDone(@Param("patientId") UUID patientId, @Param("hospitalId") UUID hospitalId,
                               @Param("performedAt") LocalDateTime performedAt,
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
