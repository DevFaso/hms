package com.example.hms.repository;

import com.example.hms.model.EncounterTreatment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EncounterTreatmentRepository extends JpaRepository<EncounterTreatment, UUID> {
    List<EncounterTreatment> findByEncounter_Id(UUID encounterId);
    List<EncounterTreatment> findByTreatment_Id(UUID treatmentId);
    @EntityGraph(value = "Treatment.withBasics")
    Optional<EncounterTreatment> findById(UUID id);

}
