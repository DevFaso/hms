package com.example.hms.repository;

import com.example.hms.model.encounter.EncounterNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EncounterNoteRepository extends JpaRepository<EncounterNote, UUID> {
    Optional<EncounterNote> findByEncounter_Id(UUID encounterId);
    boolean existsByEncounter_Id(UUID encounterId);
}
