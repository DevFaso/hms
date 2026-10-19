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

@ExtendWith(MockitoExtension.class)
class EncounterAutoCompletionServiceTest {

    @Mock private EncounterRepository encounterRepository;
    @Mock private PatientTrackerEventPublisher trackerEventPublisher;
    @Mock private AuditEventLogService auditEventLogService;

    @InjectMocks
    private EncounterAutoCompletionService service;

    private UUID admissionId;

    @BeforeEach
    void setUp() {
        admissionId = UUID.randomUUID();
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
    }

    @Test
    void noActiveEncounters_noSaveNoEvents() {
        when(encounterRepository.findByAdmissionIdAndStatusNotIn(
                eq(admissionId), anyCollection()))
                .thenReturn(Collections.emptyList());

        service.completeAdmissionEncounters(admissionId);

        verify(encounterRepository, never()).saveAll(any());
        verify(trackerEventPublisher, never()).publishStatusTransition(any(), any(), any());
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
}
