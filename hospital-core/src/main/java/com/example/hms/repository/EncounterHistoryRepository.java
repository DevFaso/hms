package com.example.hms.repository;

import com.example.hms.model.EncounterHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface EncounterHistoryRepository extends JpaRepository<EncounterHistory, UUID> {
    List<EncounterHistory> findByEncounterId(UUID encounterId);

    List<EncounterHistory> findByEncounterIdIn(Collection<UUID> encounterIds);
}
