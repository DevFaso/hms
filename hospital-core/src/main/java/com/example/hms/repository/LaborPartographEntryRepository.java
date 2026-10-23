package com.example.hms.repository;

import com.example.hms.model.labor.LaborPartographEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LaborPartographEntryRepository extends JpaRepository<LaborPartographEntry, UUID> {

    List<LaborPartographEntry> findByEpisode_IdOrderByObservationTimeAsc(UUID episodeId);

    long countByEpisode_Id(UUID episodeId);

    /** QA D605: the latest charted entry of an episode, to tell whether an urgent alert still stands. */
    java.util.Optional<LaborPartographEntry> findFirstByEpisode_IdOrderByObservationTimeDesc(UUID episodeId);
}
