package com.example.hms.repository;

import com.example.hms.enums.EmergencyReleaseType;
import com.example.hms.model.EmergencyBloodRelease;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmergencyBloodReleaseRepository extends JpaRepository<EmergencyBloodRelease, UUID> {

    List<EmergencyBloodRelease> findByRequest_IdOrderByIssuedAtDesc(UUID requestId);

    /** The release this bag is out on, for this request. */
    Optional<EmergencyBloodRelease> findFirstByRequest_IdAndBloodUnit_IdOrderByIssuedAtDesc(UUID requestId, UUID bloodUnitId);

    boolean existsByRequest_IdAndBloodUnit_IdAndReleaseType(UUID requestId, UUID bloodUnitId, EmergencyReleaseType releaseType);
}
