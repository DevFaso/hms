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

    /** QA D972: a visit's transfusion requests in the given statuses, for the checkout charge. */
    List<TransfusionRequest> findByEncounter_IdAndStatusIn(UUID encounterId, java.util.Collection<TransfusionRequestStatus> statuses);

    List<TransfusionRequest> findByHospital_IdOrderByRequestedAtDesc(UUID hospitalId);

    List<TransfusionRequest> findByHospital_IdAndStatusOrderByRequestedAtDesc(
        UUID hospitalId, TransfusionRequestStatus status);

    List<TransfusionRequest> findByPatient_IdAndHospital_IdOrderByRequestedAtDesc(UUID patientId, UUID hospitalId);
}
