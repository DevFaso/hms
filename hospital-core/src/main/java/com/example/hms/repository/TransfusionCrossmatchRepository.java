package com.example.hms.repository;

import com.example.hms.model.TransfusionCrossmatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransfusionCrossmatchRepository extends JpaRepository<TransfusionCrossmatch, UUID> {

    /** One verdict per (request, unit) — re-testing overwrites rather than accumulating. */
    Optional<TransfusionCrossmatch> findByRequest_IdAndBloodUnit_Id(UUID requestId, UUID bloodUnitId);

    List<TransfusionCrossmatch> findByRequest_IdOrderByPerformedAtDesc(UUID requestId);
}
