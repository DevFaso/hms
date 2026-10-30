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

    /** QA D1067: the delivery that ended the pregnancy a maternal history dates (at or after its start). */
    Optional<DeliveryRecord> findFirstByPatient_IdAndBirthDateTimeGreaterThanEqualOrderByBirthDateTimeDesc(
        UUID patientId, java.time.LocalDateTime since);
}
