package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.model.Encounter;
import com.example.hms.repository.EncounterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class EncounterAutoCompletionServiceTest {

    @Mock private EncounterRepository encounterRepository;
    @Mock private PatientTrackerEventPublisher trackerEventPublisher;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private EncounterAutoCompletionService service;

    private UUID admissionId;

    @BeforeEach
    void setUp() {
        admissionId = UUID.randomUUID();
    }

    @Test
    @SuppressWarnings("unchecked")
    void round13_aVisitLeftWithoutBeingSeenIsNeverAutoCompletedIntoAConsultation() {
        when(encounterRepository.findByAdmissionIdAndStatusNotIn(eq(admissionId), anyCollection()))
                .thenReturn(List.of());

        service.completeAdmissionEncounters(admissionId);

        org.mockito.ArgumentCaptor<java.util.Collection<EncounterStatus>> excluded =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(encounterRepository).findByAdmissionIdAndStatusNotIn(eq(admissionId), excluded.capture());
        assertThat(excluded.getValue()).containsExactlyInAnyOrder(
                EncounterStatus.COMPLETED, EncounterStatus.CANCELLED, EncounterStatus.LEFT_WITHOUT_BEING_SEEN);
    }

    @Test
    void completesActiveEncountersAndPublishesTrackerEvents() {
        Encounter active = new Encounter();
        active.setId(UUID.randomUUID());
        active.setStatus(EncounterStatus.IN_PROGRESS);

        when(encounterRepository.findByAdmissionIdAndStatusNotIn(
                eq(admissionId), anyCollection()))
                .thenReturn(List.of(active));

        service.completeAdmissionEncounters(admissionId);

        assertThat(active.getStatus()).isEqualTo(EncounterStatus.COMPLETED);
        assertThat(active.getCheckoutTimestamp()).isNotNull();
        verify(encounterRepository).saveAll(List.of(active));
        // The event carries the pre-transition status so WS boards can move
        // the card out of the right lane.
        verify(trackerEventPublisher).publishStatusTransition(
                active, EncounterStatus.IN_PROGRESS.name(), EncounterStatus.COMPLETED.name());
        // QA D972 (round 18): the closed stay is handed to billing (bed days, the stay's orders).
        org.mockito.ArgumentCaptor<com.example.hms.event.EncounterCheckedOutEvent> billed =
                org.mockito.ArgumentCaptor.forClass(com.example.hms.event.EncounterCheckedOutEvent.class);
        verify(eventPublisher).publishEvent(billed.capture());
        assertThat(billed.getValue().encounterId()).isEqualTo(active.getId());
        assertThat(billed.getValue().checkedOutAt()).isEqualTo(active.getCheckoutTimestamp());
    }

    @Test
    void noActiveEncounters_noSaveNoEvents() {
        when(encounterRepository.findByAdmissionIdAndStatusNotIn(
                eq(admissionId), anyCollection()))
                .thenReturn(Collections.emptyList());

        service.completeAdmissionEncounters(admissionId);

        verify(encounterRepository, never()).saveAll(any());
        verify(trackerEventPublisher, never()).publishStatusTransition(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void publishesOneEventPerEncounterWithItsOwnPreviousStatus() {
        Encounter waiting = new Encounter();
        waiting.setId(UUID.randomUUID());
        waiting.setStatus(EncounterStatus.WAITING_FOR_PHYSICIAN);
        Encounter awaitingResults = new Encounter();
        awaitingResults.setId(UUID.randomUUID());
        awaitingResults.setStatus(EncounterStatus.AWAITING_RESULTS);

        when(encounterRepository.findByAdmissionIdAndStatusNotIn(
                eq(admissionId), anyCollection()))
                .thenReturn(List.of(waiting, awaitingResults));

        service.completeAdmissionEncounters(admissionId);

        verify(trackerEventPublisher).publishStatusTransition(
                waiting, EncounterStatus.WAITING_FOR_PHYSICIAN.name(), EncounterStatus.COMPLETED.name());
        verify(trackerEventPublisher).publishStatusTransition(
                awaitingResults, EncounterStatus.AWAITING_RESULTS.name(), EncounterStatus.COMPLETED.name());
    }

    @Test
    void qaD134_neverReadsThePatientsOtherEncounters_andAuditsEachClosure() {
        Encounter stay = new Encounter();
        stay.setId(UUID.randomUUID());
        stay.setStatus(EncounterStatus.IN_PROGRESS);
        when(encounterRepository.findByAdmissionIdAndStatusNotIn(eq(admissionId), anyCollection()))
                .thenReturn(List.of(stay));

        service.completeAdmissionEncounters(admissionId);

        // The patient-wide finder is what closed the unrelated outpatient visit.
        verify(encounterRepository, never()).findByPatient_IdAndHospital_IdAndStatusNotIn(any(), any(), any());
        org.mockito.ArgumentCaptor<com.example.hms.payload.dto.AuditEventRequestDTO> audit =
                org.mockito.ArgumentCaptor.forClass(com.example.hms.payload.dto.AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getResourceId()).isEqualTo(stay.getId().toString());
        assertThat(audit.getValue().getEventType()).isEqualTo(com.example.hms.enums.AuditEventType.ENCOUNTER_UPDATE);
    }

    @Test
    void anAuditFailureDoesNotUndoTheDischarge() {
        Encounter stay = new Encounter();
        stay.setId(UUID.randomUUID());
        stay.setStatus(EncounterStatus.IN_PROGRESS);
        when(encounterRepository.findByAdmissionIdAndStatusNotIn(eq(admissionId), anyCollection()))
                .thenReturn(List.of(stay));
        when(auditEventLogService.logEvent(any())).thenThrow(new IllegalStateException("audit down"));

        service.completeAdmissionEncounters(admissionId);

        assertThat(stay.getStatus()).isEqualTo(EncounterStatus.COMPLETED);
    }

    /* ── QA D712 ── */

    private Encounter visit(com.example.hms.enums.EncounterType type, java.time.LocalDateTime began) {
        Encounter e = new Encounter();
        e.setId(UUID.randomUUID());
        e.setEncounterType(type);
        e.setStatus(EncounterStatus.IN_PROGRESS);
        e.setEncounterDate(began);
        return e;
    }

    @Test
    void d712_theEmergencyVisitAndSameDayConsultationEndWithTheAdmission() {
        java.time.LocalDateTime admittedAt = java.time.LocalDateTime.of(2026, 10, 24, 10, 0);
        com.example.hms.model.Admission admission = new com.example.hms.model.Admission();
        admission.setId(admissionId);
        admission.setAdmissionDateTime(admittedAt);
        com.example.hms.model.Patient patient = new com.example.hms.model.Patient();
        patient.setId(UUID.randomUUID());
        admission.setPatient(patient);
        com.example.hms.model.Hospital hospital = new com.example.hms.model.Hospital();
        hospital.setId(UUID.randomUUID());
        admission.setHospital(hospital);
        Encounter ed = visit(com.example.hms.enums.EncounterType.EMERGENCY, admittedAt.minusDays(1));
        Encounter consult = visit(com.example.hms.enums.EncounterType.CONSULTATION, admittedAt.minusHours(3));
        Encounter oldConsult = visit(com.example.hms.enums.EncounterType.CONSULTATION, admittedAt.minusDays(5));
        Encounter stay = visit(com.example.hms.enums.EncounterType.INPATIENT, admittedAt);
        stay.setAdmissionId(admissionId);
        when(encounterRepository.findByPatient_IdAndHospital_IdAndStatusNotIn(
                eq(patient.getId()), eq(hospital.getId()), anyCollection()))
                .thenReturn(List.of(ed, consult, oldConsult, stay));

        service.completeVisitsThatLedToAdmission(admission);

        assertThat(ed.getStatus()).isEqualTo(EncounterStatus.COMPLETED);
        assertThat(consult.getStatus()).isEqualTo(EncounterStatus.COMPLETED);
        // Path re-verified (D134): an unrelated older visit and the stay itself stay open.
        assertThat(oldConsult.getStatus()).isEqualTo(EncounterStatus.IN_PROGRESS);
        assertThat(stay.getStatus()).isEqualTo(EncounterStatus.IN_PROGRESS);
        verify(encounterRepository).saveAll(List.of(ed, consult));
        // QA D972 (round 18): the visits that led to the stay are billed like a checkout.
        verify(eventPublisher, times(2))
                .publishEvent(org.mockito.ArgumentMatchers.any(com.example.hms.event.EncounterCheckedOutEvent.class));
    }

    @Test
    void d712_aVisitBegunAfterTheAdmissionIsNotClosed() {
        java.time.LocalDateTime admittedAt = java.time.LocalDateTime.of(2026, 10, 24, 10, 0);
        assertThat(EncounterAutoCompletionService.ledToAdmission(
            visit(com.example.hms.enums.EncounterType.CONSULTATION, admittedAt.plusHours(1)), admittedAt)).isFalse();
        assertThat(EncounterAutoCompletionService.ledToAdmission(
            visit(com.example.hms.enums.EncounterType.SURGERY, admittedAt.minusHours(1)), admittedAt)).isFalse();
    }

    /* ── QA D760 (D712 not fixed) ── */

    @Test
    void d760_aWalkInOpenedSecondsBeforeABackdatedAdmissionEndsWithIt() {
        // The QA scenario: reception registers the emergency walk-in, then the
        // doctor admits with admissionDateTime = now - 60 s. The walk-in began
        // after the stated admission time but before the admission was recorded.
        java.time.LocalDateTime recordedAt = java.time.LocalDateTime.of(2026, 10, 24, 15, 41, 0);
        java.time.LocalDateTime admittedAt = recordedAt.minusSeconds(60);
        com.example.hms.model.Admission admission = new com.example.hms.model.Admission();
        admission.setId(admissionId);
        admission.setAdmissionDateTime(admittedAt);
        admission.setCreatedAt(recordedAt);
        com.example.hms.model.Patient patient = new com.example.hms.model.Patient();
        patient.setId(UUID.randomUUID());
        admission.setPatient(patient);
        com.example.hms.model.Hospital hospital = new com.example.hms.model.Hospital();
        hospital.setId(UUID.randomUUID());
        admission.setHospital(hospital);
        Encounter walkIn = visit(com.example.hms.enums.EncounterType.EMERGENCY, recordedAt.minusSeconds(20));
        walkIn.setStatus(EncounterStatus.ARRIVED);
        Encounter later = visit(com.example.hms.enums.EncounterType.EMERGENCY, recordedAt.plusHours(2));
        when(encounterRepository.findByPatient_IdAndHospital_IdAndStatusNotIn(
                eq(patient.getId()), eq(hospital.getId()), anyCollection()))
                .thenReturn(List.of(walkIn, later));

        service.completeVisitsThatLedToAdmission(admission);

        assertThat(walkIn.getStatus()).isEqualTo(EncounterStatus.COMPLETED);
        // Never checked out before it began.
        assertThat(walkIn.getCheckoutTimestamp()).isEqualTo(recordedAt);
        // Path re-verified: a visit begun after the admission was recorded stays open.
        assertThat(later.getStatus()).isEqualTo(EncounterStatus.IN_PROGRESS);
        verify(encounterRepository).saveAll(List.of(walkIn));
    }
}
