package com.example.hms.repository;

import com.example.hms.model.BreakGlassSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BreakGlassSessionRepository extends JpaRepository<BreakGlassSession, UUID> {

    /**
     * Finds the single live session — not revoked and not expired — for the
     * given (user, patient) pair. The composite open-session index covers it.
     */
    @EntityGraph(attributePaths = {"user", "patient", "hospital"})
    @Query("""
        SELECT bg
          FROM BreakGlassSession bg
         WHERE bg.user.id = :userId
           AND bg.patient.id = :patientId
           AND bg.revokedAt IS NULL
           AND bg.expiresAt > :now
         ORDER BY bg.startedAt DESC
        """)
    List<BreakGlassSession> findLiveForUserAndPatient(
        @Param("userId") UUID userId,
        @Param("patientId") UUID patientId,
        @Param("now") LocalDateTime now);

    /** All live sessions for a patient (powers the patient-detail banner). */
    @EntityGraph(attributePaths = {"user", "hospital"})
    @Query("""
        SELECT bg
          FROM BreakGlassSession bg
         WHERE bg.patient.id = :patientId
           AND bg.revokedAt IS NULL
           AND bg.expiresAt > :now
         ORDER BY bg.startedAt DESC
        """)
    List<BreakGlassSession> findLiveForPatient(
        @Param("patientId") UUID patientId,
        @Param("now") LocalDateTime now);

    /**
     * Hospital-scoped recent activity for compliance review screens.
     * Includes revoked / expired rows so admins can see the full trail.
     */
    @EntityGraph(attributePaths = {"user", "patient", "hospital", "revokedBy"})
    Page<BreakGlassSession> findByHospitalIdOrderByStartedAtDesc(UUID hospitalId, Pageable pageable);

    Optional<BreakGlassSession> findByIdAndHospitalId(UUID id, UUID hospitalId);

    /**
     * Atomic +1 on the audit counter. Avoids the read-modify-write race that
     * the ORM-based {@code save} path would have under concurrent reads.
     * Returns the number of rows affected (1 on success, 0 if the session has
     * been deleted).
     */
    @Modifying
    @Query("UPDATE BreakGlassSession bg "
         + "   SET bg.auditCount = bg.auditCount + 1, bg.updatedAt = CURRENT_TIMESTAMP "
         + " WHERE bg.id = :id")
    int incrementAuditCount(@Param("id") UUID id);
}

