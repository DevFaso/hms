package com.example.hms.repository;

import com.example.hms.model.encounter.EncounterNoteAddendum;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EncounterNoteAddendumRepository extends JpaRepository<EncounterNoteAddendum, UUID> {
    List<EncounterNoteAddendum> findByNote_IdOrderByDocumentedAtAsc(UUID noteId);
}
