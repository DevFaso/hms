package com.example.hms.repository;

import com.example.hms.model.encounter.EncounterNoteHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EncounterNoteHistoryRepository extends JpaRepository<EncounterNoteHistory, UUID> {
    List<EncounterNoteHistory> findByEncounterIdOrderByChangedAtDesc(UUID encounterId);
}
