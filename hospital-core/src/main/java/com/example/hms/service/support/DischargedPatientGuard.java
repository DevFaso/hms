package com.example.hms.service.support;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.EncounterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * QA D431/D525: a patient whose stays here are all closed, and who has no
 * visit opened after the last discharge, is not under care here — nothing may
 * be charted for them as if they were still on the ward. A patient never
 * admitted (an outpatient) is not affected. Shared by the nurse station and
 * the chart's own vitals endpoint so the two cannot drift.
 *
 * <p>QA D1477 (round 35): the same for a patient never admitted whose last visit ended unseen (left
 * without being seen, or cancelled) with nothing opened since: nobody is caring for them here. A visit
 * that ended with a consultation does not count: a nurse may still see that patient, and late
 * documentation of care given stays open through a care note, which this guard does not gate.
 */
@Component
@RequiredArgsConstructor
public class DischargedPatientGuard {

    private static final EnumSet<AdmissionStatus> OPEN_STAY = EnumSet.of(
        AdmissionStatus.PENDING, AdmissionStatus.ACTIVE,
        AdmissionStatus.ON_LEAVE, AdmissionStatus.AWAITING_DISCHARGE);

    private final AdmissionRepository admissionRepository;
    private final EncounterRepository encounterRepository;

    /** Throws {@code BusinessException(messageKey)} for a discharged patient with no new visit. */
    public void requireNotDischargedWithoutVisit(UUID patientId, UUID hospitalId, String messageKey) {
        List<Admission> stays = admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(
            patientId, hospitalId, EnumSet.allOf(AdmissionStatus.class));
        if (stays.isEmpty()) {
            requireLastVisitNotEndedUnseen(patientId, hospitalId, messageKey);
            return;
        }
        if (stays.stream().anyMatch(a -> OPEN_STAY.contains(a.getStatus()))) {
            return;
        }
        // QA D525: only a visit that began after the last discharge is a new
        // episode of care; a stale visit left open from before the stay is not.
        LocalDateTime lastDischarge = stays.stream()
            .map(Admission::getActualDischargeDateTime)
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
        boolean openVisit = encounterRepository.findByPatient_IdAndHospital_IdAndStatusNotIn(patientId, hospitalId,
                EncounterStatus.CLOSED).stream()
            .anyMatch(e -> lastDischarge == null || startedAfter(e, lastDischarge));
        if (!openVisit) {
            throw new BusinessException(messageKey);
        }
    }

    private void requireLastVisitNotEndedUnseen(UUID patientId, UUID hospitalId, String messageKey) {
        // A prescription's anchor is not a visit: it says nothing of who is under care.
        List<Encounter> visits = encounterRepository.findByPatient_IdAndHospital_Id(patientId, hospitalId).stream()
            .filter(visit -> !visit.isPrescriptionAnchor())
            .toList();
        if (visits.isEmpty() || visits.stream().anyMatch(v -> v.getStatus() == null || !v.getStatus().isClosed())) {
            return;
        }
        visits.stream()
            .max(Comparator.comparing(DischargedPatientGuard::startOf, Comparator.nullsFirst(Comparator.naturalOrder())))
            .filter(last -> EncounterStatus.ENDED_UNSEEN.contains(last.getStatus()))
            .ifPresent(last -> {
                throw new BusinessException(messageKey);
            });
    }

    private static LocalDateTime startOf(Encounter encounter) {
        if (encounter.getEncounterDate() != null) {
            return encounter.getEncounterDate();
        }
        return encounter.getArrivalTimestamp() != null ? encounter.getArrivalTimestamp() : encounter.getCreatedAt();
    }

    private static boolean startedAfter(Encounter encounter, LocalDateTime instant) {
        LocalDateTime start = startOf(encounter);
        return start != null && start.isAfter(instant);
    }
}
