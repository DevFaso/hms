package com.example.hms.repository;

import com.example.hms.enums.LaborStatus;
import com.example.hms.model.labor.LaborEpisode;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LaborEpisodeRepository extends JpaRepository<LaborEpisode, UUID> {

    Optional<LaborEpisode> findFirstByPatient_IdAndHospital_IdAndStatusOrderByAdmittedAtDesc(
        UUID patientId, UUID hospitalId, LaborStatus status);

    boolean existsByPatient_IdAndHospital_IdAndStatus(UUID patientId, UUID hospitalId, LaborStatus status);

    /**
     * QA D796: is this woman in labour anywhere (an antenatal calendar must not
     * be planned for a pregnancy that is being delivered). Existence only.
     */
    boolean existsByPatient_IdAndStatus(UUID patientId, LaborStatus status);

    /** QA D1631: her newest episode of this status at any hospital (one woman, one active labour). */
    Optional<LaborEpisode> findFirstByPatient_IdAndStatusOrderByAdmittedAtDesc(UUID patientId, LaborStatus status);

    List<LaborEpisode> findByPatient_IdAndHospital_IdOrderByAdmittedAtDesc(
        UUID patientId, UUID hospitalId, Pageable pageable);

    /** E9 #59d — across the readable hospitals ({@code RecordAccessPolicy.readableHospitalIds}). */
    List<LaborEpisode> findByPatient_IdAndHospital_IdInOrderByAdmittedAtDesc(
        UUID patientId, java.util.Collection<UUID> hospitalIds, Pageable pageable);

    /**
     * The episode's status as the database holds it now. Read after the
     * patient lock, so a transition decides on the committed state and not on
     * an entity loaded before another transition committed.
     */
    @org.springframework.data.jpa.repository.Query("SELECT e.status FROM LaborEpisode e WHERE e.id = :id")
    Optional<LaborStatus> findStatusById(@org.springframework.data.repository.query.Param("id") UUID id);

    /** Labour transfer: this hospital holds the episode that continues {@code sourceId}. */
    boolean existsByTransferredFrom_IdAndHospital_Id(UUID sourceId, UUID hospitalId);

    /** One woman, one active labour: another active episode than this one, at any hospital. */
    boolean existsByPatient_IdAndStatusAndIdNot(UUID patientId, LaborStatus status, UUID episodeId);

    /** Tenant guard — single-row lookups go through id + hospital. */
    Optional<LaborEpisode> findByIdAndHospital_Id(UUID id, UUID hospitalId);
}
