package com.example.hms.service;

import com.example.hms.model.Encounter;
import com.example.hms.payload.dto.clinical.PatientTrackerEventDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Publishes patient-tracker status transitions to STOMP topic
 * {@code /topic/patient-tracker/{hospitalId}}. Subscribers refresh the tracker
 * board on receipt — replacing the previous 30-second poll.
 * <p>
 * Failures here must NOT roll back the originating clinical transaction, so all
 * I/O errors are caught and logged at WARN.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatientTrackerEventPublisher {

    public static final String TOPIC_PREFIX = "/topic/patient-tracker/";

    private final SimpMessagingTemplate messagingTemplate;

    public void publishStatusTransition(Encounter encounter, String previousStatus, String newStatus) {
        if (encounter == null || encounter.getHospital() == null
                || encounter.getHospital().getId() == null) {
            return;
        }
        UUID hospitalId = encounter.getHospital().getId();
        UUID departmentId = encounter.getDepartment() != null ? encounter.getDepartment().getId() : null;
        UUID patientId = encounter.getPatient() != null ? encounter.getPatient().getId() : null;

        PatientTrackerEventDTO event = PatientTrackerEventDTO.builder()
                .hospitalId(hospitalId)
                .departmentId(departmentId)
                .encounterId(encounter.getId())
                .patientId(patientId)
                .previousStatus(previousStatus)
                .newStatus(newStatus)
                .emittedAt(LocalDateTime.now())
                .build();

        try {
            messagingTemplate.convertAndSend(TOPIC_PREFIX + hospitalId, event);
        } catch (Exception ex) {
            log.warn("Failed to publish tracker event for encounter {} ({} -> {}): {}",
                    encounter.getId(), previousStatus, newStatus, ex.getMessage());
        }
    }
}
