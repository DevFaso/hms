package com.example.hms.repository;

import com.example.hms.model.labor.DeliveryRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryRecordRepository extends JpaRepository<DeliveryRecord, UUID> {

    Optional<DeliveryRecord> findByEpisode_Id(UUID episodeId);

    boolean existsByEpisode_Id(UUID episodeId);

    /** A birth on or after {@code since} for this mother (QA D164: no antenatal plan after delivery). */
    boolean existsByPatient_IdAndBirthDateTimeGreaterThanEqual(UUID patientId, java.time.LocalDateTime since);
}
