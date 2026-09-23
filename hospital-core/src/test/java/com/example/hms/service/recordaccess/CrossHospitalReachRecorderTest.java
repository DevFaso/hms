package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.example.hms.model.Hospital;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class CrossHospitalReachRecorderTest {

    private record Row(UUID hospitalId) {
    }

    @Mock
    private AuditEventLogService auditEventLogService;

    @Mock
    private BreakGlassGate breakGlassGate;

    @Mock
    private com.example.hms.repository.AuditEventLogRepository auditEventLogRepository;

    @InjectMocks
    private CrossHospitalReachRecorder recorder;

    @Test
    @DisplayName("reachOf counts rows per foreign hospital and ignores local and hospital-less rows")
    void reachOfCountsForeignRowsPerSource() {
        UUID acting = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<Row> rows = List.of(new Row(acting), new Row(a), new Row(a), new Row(b), new Row(null));

        Map<String, Long> reach = CrossHospitalReachRecorder.reachOf(rows.stream().map(Row::hospitalId).toList(), acting);

        assertThat(reach).containsOnly(Map.entry(a.toString(), 2L), Map.entry(b.toString(), 1L));
        assertThat(CrossHospitalReachRecorder.reachOf(List.of(), acting)).isEmpty();
    }

    @Test
    @DisplayName("hospitalIdOf reads the id off the association and tolerates its absence")
    void hospitalIdOfTolerantOfNull() {
        Hospital hospital = new Hospital();
        UUID id = UUID.randomUUID();
        hospital.setId(id);
        assertThat(CrossHospitalReachRecorder.hospitalIdOf(hospital)).isEqualTo(id);
        assertThat(CrossHospitalReachRecorder.hospitalIdOf(null)).isNull();
    }

    @Test
    @DisplayName("merge sums counts per source across surfaces")
    void mergeSumsPerSource() {
        UUID a = UUID.randomUUID();
        Map<String, Long> into = new HashMap<>(Map.of(a.toString(), 2L));

        CrossHospitalReachRecorder.merge(into, Map.of(a.toString(), 3L, "x", 1L));

        assertThat(into).containsOnly(Map.entry(a.toString(), 5L), Map.entry("x", 1L));
    }

    @Test
    @DisplayName("one RECORD_SHARE per source hospital, carrying actor, acting hospital, source and count")
    void recordsOneRowPerSource() {
        UUID patient = UUID.randomUUID();
        UUID acting = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID assignment = UUID.randomUUID();
        UUID source = UUID.randomUUID();

        recorder.recordReach(patient, acting, actor, assignment, Map.of(source.toString(), 3L), "Cross-hospital test read");

        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(captor.capture());
        AuditEventRequestDTO event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(AuditEventType.RECORD_SHARE);
        assertThat(event.getStatus()).isEqualTo(AuditStatus.SUCCESS);
        assertThat(event.getUserId()).isEqualTo(actor);
        assertThat(event.getAssignmentId()).isEqualTo(assignment);
        assertThat(event.getPatientId()).isEqualTo(patient);
        assertThat(event.getEntityType()).isEqualTo("PATIENT");
        assertThat(event.getEventDescription()).isEqualTo("Cross-hospital test read");
        assertThat(String.valueOf(event.getDetails()))
            .contains("actingHospitalId=" + acting)
            .contains("sourceHospitalId=" + source)
            .contains("rowsSurfaced=3");
    }

    @Test
    @DisplayName("a batched reach writes every patient's disclosure in ONE audit transaction")
    void batchedReachWritesOneBatch() {
        UUID acting = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID sourceA = UUID.randomUUID();
        UUID sourceB = UUID.randomUUID();
        UUID patient1 = UUID.randomUUID();
        UUID patient2 = UUID.randomUUID();
        when(auditEventLogRepository.findDisclosureDetailsForActorSince(any(), any(), any(), any()))
            .thenReturn(List.of());

        recorder.recordBatchedReach(Map.of(
            patient1, Map.of(sourceA.toString(), 2L),
            patient2, Map.of(sourceB.toString(), 1L)), acting, actor, null, "Batched read");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditEventRequestDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(auditEventLogService).logEvents(captor.capture());
        verify(auditEventLogService, never()).logEvent(any());
        assertThat(captor.getValue()).hasSize(2)
            .extracting(AuditEventRequestDTO::getPatientId)
            .containsExactlyInAnyOrder(patient1, patient2);
    }

    @Test
    @DisplayName("a disclosure this actor already recorded today for that patient and source is not written again")
    void batchedReachSkipsTodaysDuplicates() {
        UUID acting = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID seen = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        // A recorded row keeps its source hospital in the details JSON.
        when(auditEventLogRepository.findDisclosureDetailsForActorSince(any(), any(), any(), any()))
            .thenReturn(List.<Object[]>of(new Object[]{seen, "{\"sourceHospitalId\":\"" + source + "\"}"}));

        recorder.recordBatchedReach(Map.of(
            seen, Map.of(source.toString(), 5L),
            fresh, Map.of(source.toString(), 1L)), acting, actor, null, "Batched read");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditEventRequestDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(auditEventLogService).logEvents(captor.capture());
        assertThat(captor.getValue()).singleElement()
            .extracting(AuditEventRequestDTO::getPatientId).isEqualTo(fresh);
        // A patient with nothing new to record costs no break-glass lookup.
        verify(breakGlassGate, never()).liveSessionId(actor, seen, acting);
    }

    @Test
    @DisplayName("a refresh that discloses nothing new writes nothing at all")
    void batchedReachAllDuplicatesWritesNothing() {
        UUID acting = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        when(auditEventLogRepository.findDisclosureDetailsForActorSince(any(), any(), any(), any()))
            .thenReturn(List.<Object[]>of(new Object[]{patient, "{\"sourceHospitalId\":\"" + source + "\"}"}));

        recorder.recordBatchedReach(Map.of(patient, Map.of(source.toString(), 9L)),
            acting, actor, null, "Batched read");

        verify(auditEventLogService, never()).logEvents(any());
        verifyNoInteractions(breakGlassGate);
    }

    @Test
    @DisplayName("a dedupe query that fails records everything rather than nothing")
    void batchedReachRecordsWhenTheDedupeQueryFails() {
        UUID acting = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        when(auditEventLogRepository.findDisclosureDetailsForActorSince(any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("db down"));

        recorder.recordBatchedReach(Map.of(patient, Map.of(UUID.randomUUID().toString(), 1L)),
            acting, actor, null, "Batched read");

        verify(auditEventLogService).logEvents(any());
    }

    @Test
    @DisplayName("an empty reach records nothing, so callers can pass it unconditionally")
    void emptyReachRecordsNothing() {
        recorder.recordReach(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, Map.of(), "x");
        recorder.recordReach(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null, "x");
        recorder.recordReach(null, UUID.randomUUID(), UUID.randomUUID(), null, Map.of("s", 1L), "x");

        verify(auditEventLogService, never()).logEvent(any());
    }

    @Test
    @DisplayName("an audit failure is logged and never fails the read; the other sources are still recorded")
    void auditFailureDoesNotPropagate() {
        when(auditEventLogService.logEvent(any()))
            .thenThrow(new IllegalStateException("ledger down"))
            .thenReturn(null);

        recorder.recordReach(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
            Map.of("a", 1L, "b", 2L), "x");

        verify(auditEventLogService, times(2)).logEvent(any());
    }

    @Test
    @DisplayName("a read under a live break-the-glass session names the session on every row (E9 #62)")
    void stampsTheBreakGlassSession() {
        UUID patient = UUID.randomUUID();
        UUID acting = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        when(breakGlassGate.liveSessionId(actor, patient, acting)).thenReturn(Optional.of(session));

        recorder.recordReach(patient, acting, actor, null, Map.of(UUID.randomUUID().toString(), 1L), "x");

        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(captor.capture());
        assertThat(String.valueOf(captor.getValue().getDetails())).contains("breakGlassSessionId=" + session);
    }

    @Test
    @DisplayName("without a session the row carries no session key")
    void noSessionNoStamp() {
        when(breakGlassGate.liveSessionId(any(), any(), any())).thenReturn(Optional.empty());
        recorder.recordReach(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, Map.of("s", 1L), "x");
        ArgumentCaptor<AuditEventRequestDTO> captor = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(captor.capture());
        assertThat(String.valueOf(captor.getValue().getDetails())).doesNotContain("breakGlassSessionId");
    }
}
