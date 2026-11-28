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

    /** r54: her newest episode of this status at the hospitals the record-access policy lets the reader see. */
    Optional<LaborEpisode> findFirstByPatient_IdAndHospital_IdInAndStatusOrderByAdmittedAtDesc(
        UUID patientId, java.util.Collection<UUID> hospitalIds, LaborStatus status);

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

    /** QA D3308: of {@code patientIds}, those with an episode of this status at the hospital (the bed board, one query). */
    @org.springframework.data.jpa.repository.Query("""
        SELECT DISTINCT e.patient.id FROM LaborEpisode e
        WHERE e.hospital.id = :hospitalId AND e.status = :status AND e.patient.id IN :patientIds
        """)
    List<UUID> findPatientIdsWithStatusAmong(@org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
                                             @org.springframework.data.repository.query.Param("status") LaborStatus status,
                                             @org.springframework.data.repository.query.Param("patientIds") java.util.Collection<UUID> patientIds);

    /**
     * QA D3868: the women in labour at this hospital, newest admission first, for the maternity Labour tab's list
     * (the tab opened empty and the midwife had to know an identifier). One hospital only: the caller's scope.
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"patient", "hospital"})
    List<LaborEpisode> findByHospital_IdAndStatusOrderByAdmittedAtDesc(UUID hospitalId, LaborStatus status);

    /** Tenant guard — single-row lookups go through id + hospital. */
    Optional<LaborEpisode> findByIdAndHospital_Id(UUID id, UUID hospitalId);

    /**
     * Review of #1237 (ward reads, D1314): of {@code patientIds}, the women in labour at this hospital or who gave
     * birth here since {@code birthSince}, in ONE query for a whole ward read (no per-patient lookup).
     */
    @org.springframework.data.jpa.repository.Query("""
        select p.id from Patient p
        where p.id in :patientIds
          and (exists (select 1 from LaborEpisode e
                       where e.patient = p and e.hospital.id = :hospitalId and e.status = :active)
               or exists (select 1 from DeliveryRecord d
                          where d.patient = p and d.hospital.id = :hospitalId and d.birthDateTime >= :birthSince))
        """)
    List<UUID> obstetricPatientIdsAt(@org.springframework.data.repository.query.Param("patientIds") java.util.Collection<UUID> patientIds,
                                     @org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
                                     @org.springframework.data.repository.query.Param("active") LaborStatus active,
                                     @org.springframework.data.repository.query.Param("birthSince") java.time.LocalDateTime birthSince);
}
