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

    /**
     * Best-effort tracker notification. Never throws.
     *
     * <p>The whole body is guarded, not just the send. It used to catch only
     * {@code convertAndSend}, which left the lines above it — the ones that
     * read {@code getHospital()}, {@code getDepartment()} and
     * {@code getPatient()} — outside the net.
     *
     * <p>Those are LAZY proxies, and this codebase maps by FIELD access, so
     * {@code proxy.getId()} does <b>not</b> take Hibernate's
     * identifier-without-initialising shortcut (that applies to property
     * access only). Each call fully initialises the proxy, and a proxy whose
     * row has been deleted throws {@link jakarta.persistence.EntityNotFoundException}.
     * That propagated out of {@code completeTriage} as a 500 and rolled back
     * the transition — a websocket refresh nobody was waiting on took the
     * clinical write down with it, which is exactly what the note above says
     * must not happen.
     *
     * <p>{@code assignment_id} columns carry no foreign key on any of the
     * dozen-plus tables that hold one, and V96 deletes duplicate assignment
     * rows, so dangling references are a live possibility here rather than a
     * theoretical one. Auditing that is separate work; this method simply
     * stops caring.
     */
    public void publishStatusTransition(Encounter encounter, String previousStatus, String newStatus) {
        if (encounter == null) {
            return;
        }
        try {
            publishOrThrow(encounter, previousStatus, newStatus);
        } catch (Exception ex) {
            log.warn("Failed to publish tracker event for encounter {} ({} -> {}): {}",
                    encounter.getId(), previousStatus, newStatus, ex.getMessage(), ex);
        }
    }

    private void publishOrThrow(Encounter encounter, String previousStatus, String newStatus) {
        if (encounter.getHospital() == null || encounter.getHospital().getId() == null) {
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

        messagingTemplate.convertAndSend(TOPIC_PREFIX + hospitalId, event);
    }
}
