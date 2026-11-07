package com.example.hms.repository;

import com.example.hms.model.encounter.EncounterNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EncounterNoteRepository extends JpaRepository<EncounterNote, UUID> {
    /**
     * QA D831: an encounter holds one row per note entry (an inpatient stay one
     * per progress note), so "the" note is the current entry: the one created
     * last. A single-result finder would fail on the second entry.
     */
    Optional<EncounterNote> findFirstByEncounter_IdOrderByCreatedAtDescIdDesc(UUID encounterId);

    /** QA D831: every entry of the encounter, oldest first. */
    List<EncounterNote> findByEncounter_IdOrderByCreatedAtAscIdAsc(UUID encounterId);

    /** Whether the user wrote a note entry on the visit (TreatingClinician). */
    boolean existsByEncounter_IdAndAuthor_Id(UUID encounterId, UUID authorUserId);

    boolean existsByEncounter_Id(UUID encounterId);

    /**
     * Batch lookup used by the chart-review aggregator so the notes for the
     * encounters loaded in the current page are fetched in one round-trip
     * instead of N+1 single-encounter queries.
     */
    List<EncounterNote> findByEncounter_IdIn(Collection<UUID> encounterIds);

    /**
     * The co-sign queue (P3 #20): signed notes that declared a co-signature
     * requirement and have not received one, at one hospital. Served to every
     * attending-privileged clinician there — no staff-to-staff supervision
     * relation exists to narrow it further.
     */
    List<EncounterNote> findByHospital_IdAndRequiresCosignTrueAndCosignedAtIsNullAndSignedAtIsNotNullOrderBySignedAtAsc(
        UUID hospitalId);
}
