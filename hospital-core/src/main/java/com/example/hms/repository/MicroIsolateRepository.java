package com.example.hms.repository;

import com.example.hms.model.MicroIsolate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MicroIsolateRepository extends JpaRepository<MicroIsolate, UUID> {

    List<MicroIsolate> findByCultureResult_IdOrderByIsolateNumberAscCreatedAtAsc(UUID cultureResultId);

    /** FHIR DiagnosticReport search (Tier 2 item 42): isolates for a whole page of cultures. */
    List<MicroIsolate> findByCultureResult_IdInOrderByIsolateNumberAscCreatedAtAsc(
        java.util.Collection<UUID> cultureResultIds);

    long countByCultureResult_Id(UUID cultureResultId);
}
