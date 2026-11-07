package com.example.hms.repository;

import com.example.hms.enums.TransfusionRequestStatus;
import com.example.hms.model.TransfusionRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransfusionRequestRepository extends JpaRepository<TransfusionRequest, UUID> {

    /**
     * Row lock on the request. A cancel, a release, a crossmatch and a hang on it take turns, each
     * reading what the one before wrote. Always before the bag's lock, and before the request is read.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM TransfusionRequest r WHERE r.id = :id")
    Optional<TransfusionRequest> lockById(@Param("id") UUID id);

    /** The patient's requests still being worked, by id, so they are locked in one order before any is read. */
    @Query("SELECT r.id FROM TransfusionRequest r WHERE r.patient.id = :patientId AND r.hospital.id = :hospitalId "
        + "AND r.status NOT IN (com.example.hms.enums.TransfusionRequestStatus.COMPLETED, "
        + "com.example.hms.enums.TransfusionRequestStatus.CANCELLED) ORDER BY r.id")
    List<UUID> findOpenIds(@Param("patientId") UUID patientId, @Param("hospitalId") UUID hospitalId);

    /** QA D972: a visit's transfusion requests in the given statuses, for the checkout charge. */
    /** Order review: this patient's open requests here, under their locks, in id order. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM TransfusionRequest r WHERE r.patient.id = :patientId AND r.hospital.id = :hospitalId "
        + "AND r.status NOT IN (com.example.hms.enums.TransfusionRequestStatus.COMPLETED, "
        + "com.example.hms.enums.TransfusionRequestStatus.CANCELLED) ORDER BY r.id")
    List<TransfusionRequest> lockOpenForPatient(@Param("patientId") UUID patientId, @Param("hospitalId") UUID hospitalId);

    /** Order review: what can replace a held request: written since, not cancelled. */
    @Query("SELECT r FROM TransfusionRequest r WHERE r.patient.id = :patientId AND r.hospital.id = :hospitalId "
        + "AND r.createdAt >= :since AND r.status <> com.example.hms.enums.TransfusionRequestStatus.CANCELLED "
        + "ORDER BY r.createdAt DESC")
    List<TransfusionRequest> findWrittenSince(@Param("patientId") UUID patientId, @Param("hospitalId") UUID hospitalId,
                                              @Param("since") java.time.LocalDateTime since);

    List<TransfusionRequest> findByEncounter_IdAndStatusIn(UUID encounterId, java.util.Collection<TransfusionRequestStatus> statuses);

    List<TransfusionRequest> findByHospital_IdOrderByRequestedAtDesc(UUID hospitalId);

    List<TransfusionRequest> findByHospital_IdAndStatusOrderByRequestedAtDesc(
        UUID hospitalId, TransfusionRequestStatus status);

    List<TransfusionRequest> findByPatient_IdAndHospital_IdOrderByRequestedAtDesc(UUID patientId, UUID hospitalId);
}
