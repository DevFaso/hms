package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.model.Encounter;
import com.example.hms.repository.EncounterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Completes every non-terminal encounter for a patient at a hospital when the
 * patient is discharged. Shared by both discharge paths — the direct
 * admission discharge ({@code POST /admissions/{id}/discharge}) and the
 * nurse-request/doctor-approve flow ({@code POST /discharge-approvals/{id}/approve})
 * — so neither leaves encounters stuck In&nbsp;Progress.
 *
 * <p>Publishes a tracker status-transition event per encounter so
 * WebSocket-connected tracker boards update immediately instead of waiting
 * for the next heartbeat poll.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EncounterAutoCompletionService {

    private static final Set<EncounterStatus> TERMINAL_STATUSES = EnumSet.of(
            EncounterStatus.COMPLETED, EncounterStatus.CANCELLED);

    private final EncounterRepository encounterRepository;
    private final PatientTrackerEventPublisher trackerEventPublisher;

    @Transactional
    public void completeActiveEncounters(UUID patientId, UUID hospitalId) {
        List<Encounter> active = encounterRepository.findByPatient_IdAndHospital_IdAndStatusNotIn(
                patientId, hospitalId, TERMINAL_STATUSES);
        if (active.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<String> previousStatuses = active.stream()
                .map(e -> e.getStatus() != null ? e.getStatus().name() : null)
                .toList();
        for (Encounter enc : active) {
            enc.setStatus(EncounterStatus.COMPLETED);
            enc.setCheckoutTimestamp(now);
        }
        encounterRepository.saveAll(active);
        for (int i = 0; i < active.size(); i++) {
            trackerEventPublisher.publishStatusTransition(active.get(i),
                    previousStatuses.get(i), EncounterStatus.COMPLETED.name());
        }
        log.info("Auto-completed {} active encounter(s) for patient {} at hospital {} on discharge",
                active.size(), patientId, hospitalId);
    }
}
