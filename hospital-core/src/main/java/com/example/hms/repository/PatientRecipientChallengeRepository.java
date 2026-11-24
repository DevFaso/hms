package com.example.hms.repository;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.model.PatientRecipientChallenge;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface PatientRecipientChallengeRepository extends JpaRepository<PatientRecipientChallenge, UUID> {
    Optional<PatientRecipientChallenge> findByPatientIdAndChannel(UUID patientId, NotificationChannel channel);
}
