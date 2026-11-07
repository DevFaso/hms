package com.example.hms.repository;

import com.example.hms.model.DeathDispute;
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

public interface DeathDisputeRepository extends JpaRepository<DeathDispute, UUID> {

    /** The one question {@code DeceasedPatientGuard} asks. */
    @Query("SELECT COUNT(d) > 0 FROM DeathDispute d WHERE d.patientId = :patientId AND d.hospitalId = :hospitalId "
        + "AND d.status = com.example.hms.enums.DeathDisputeStatus.OPEN")
    boolean existsOpen(@Param("patientId") UUID patientId, @Param("hospitalId") UUID hospitalId);

    @Query("SELECT d FROM DeathDispute d WHERE d.deathRecordId = :recordId AND d.hospitalId = :hospitalId "
        + "AND d.status = com.example.hms.enums.DeathDisputeStatus.OPEN")
    Optional<DeathDispute> findOpen(@Param("recordId") UUID recordId, @Param("hospitalId") UUID hospitalId);

    /** Locked: an outcome waits for this row, and reads it as the other one left it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DeathDispute d WHERE d.id = :id")
    Optional<DeathDispute> lockById(@Param("id") UUID id);

    /** Locked: entered in error ends them all, and one upheld meanwhile drops out of this list. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DeathDispute d WHERE d.deathRecordId = :recordId "
        + "AND d.status = com.example.hms.enums.DeathDisputeStatus.OPEN")
    List<DeathDispute> lockOpenForRecord(@Param("recordId") UUID recordId);

    @Query("SELECT DISTINCT d.deathRecordId FROM DeathDispute d WHERE d.deathRecordId IN :recordIds "
        + "AND d.status = com.example.hms.enums.DeathDisputeStatus.OPEN")
    List<UUID> findOpenRecordIds(@Param("recordIds") Collection<UUID> recordIds);

    /** A hospital reviews what was disputed there, and what was disputed of a death it recorded. */
    @Query("SELECT d FROM DeathDispute d WHERE d.status = com.example.hms.enums.DeathDisputeStatus.OPEN "
        + "AND (d.hospitalId = :hospitalId OR d.deathRecordId IN "
        + "(SELECT r.id FROM DeathRecord r WHERE r.hospital.id = :hospitalId)) ORDER BY d.disputedAt ASC")
    List<DeathDispute> findOpenForReview(@Param("hospitalId") UUID hospitalId);

    /** Open, and no review item reached anyone, or the last one has gone unreviewed since {@code before}. */
    @Query("SELECT d FROM DeathDispute d WHERE d.status = com.example.hms.enums.DeathDisputeStatus.OPEN "
        + "AND (d.reviewFiledAt IS NULL OR COALESCE(d.lastEscalatedAt, d.reviewFiledAt) < :before)")
    List<DeathDispute> findDueForEscalation(@Param("before") LocalDateTime before);
}
