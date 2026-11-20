package com.example.hms.repository;

import com.example.hms.model.labor.DeliveryRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryRecordRepository extends JpaRepository<DeliveryRecord, UUID> {

    Optional<DeliveryRecord> findByEpisode_Id(UUID episodeId);

    boolean existsByEpisode_Id(UUID episodeId);

    /** QA D959 (round 18): the deliveries on the chronology, at the acting hospital. */
    java.util.List<DeliveryRecord> findByPatient_IdAndHospital_IdOrderByBirthDateTimeDesc(UUID patientId, UUID hospitalId);

    /** The mother's latest birth at this hospital (QA D215: a new delivery starts a new postpartum plan). */
    Optional<DeliveryRecord> findFirstByPatient_IdAndHospital_IdOrderByBirthDateTimeDesc(UUID patientId, UUID hospitalId);

    /** A birth on or after {@code since} for this mother (QA D164: no antenatal plan after delivery). */
    boolean existsByPatient_IdAndEpisode_IdNotAndBirthDateTimeBetween(UUID patientId, UUID episodeId,
        java.time.LocalDateTime from, java.time.LocalDateTime to);

    boolean existsByPatient_IdAndBirthDateTimeGreaterThanEqual(UUID patientId, java.time.LocalDateTime since);

    /**
     * r54 (provisional): her latest birth in [from, to] at the hospitals the record-access policy lets
     * the reader see (never at any hospital: a birth the patient withheld must not surface).
     */
    Optional<DeliveryRecord> findFirstByPatient_IdAndHospital_IdInAndBirthDateTimeBetweenOrderByBirthDateTimeDesc(
        UUID patientId, java.util.Collection<UUID> hospitalIds, java.time.LocalDateTime from,
        java.time.LocalDateTime to);

    /**
     * Policy 2026-11-20 (ward reads, QA D1314): the latest birth since {@code since} of each of
     * {@code patientIds} at this hospital, as (patient id, birth time) rows, in ONE query for a ward.
     */
    @org.springframework.data.jpa.repository.Query("""
        select d.patient.id, max(d.birthDateTime) from DeliveryRecord d
        where d.patient.id in :patientIds
          and d.hospital.id = :hospitalId
          and d.birthDateTime >= :since
        group by d.patient.id
        """)
    java.util.List<Object[]> latestBirthTimesAt(
        @org.springframework.data.repository.query.Param("patientIds") java.util.Collection<UUID> patientIds,
        @org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId,
        @org.springframework.data.repository.query.Param("since") java.time.LocalDateTime since);

    /** QA D1067: the delivery that ended the pregnancy a maternal history dates (at or after its start). */
    Optional<DeliveryRecord> findFirstByPatient_IdAndHospital_IdAndBirthDateTimeGreaterThanEqualOrderByBirthDateTimeDesc(
        UUID patientId, UUID hospitalId, java.time.LocalDateTime since);
}
