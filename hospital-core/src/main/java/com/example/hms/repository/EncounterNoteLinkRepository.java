package com.example.hms.repository;

import com.example.hms.enums.EncounterNoteLinkType;
import com.example.hms.model.encounter.EncounterNoteLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EncounterNoteLinkRepository extends JpaRepository<EncounterNoteLink, UUID> {
    List<EncounterNoteLink> findByNote_Id(UUID noteId);
    boolean existsByNote_IdAndArtifactIdAndArtifactType(UUID noteId, UUID artifactId, EncounterNoteLinkType type);
}
