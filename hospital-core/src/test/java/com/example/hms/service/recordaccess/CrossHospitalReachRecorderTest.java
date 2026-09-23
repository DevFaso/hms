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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.example.hms.model.Hospital;
import java.util.Optional;
import org.springframework.transaction.TransactionDefinition;

@ExtendWith(MockitoExtension.class)
class CrossHospitalReachRecorderTest {

    private record Row(UUID hospitalId) {
    }

    @Mock
    private AuditEventLogService auditEventLogService;

    @Mock
    private BreakGlassGate breakGlassGate;

    @Mock
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

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
    @DisplayName("every read is recorded — the same page read twice records it twice")
    void batchedReachRecordsEveryRead() {
        UUID acting = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        Map<UUID, Map<String, Long>> page = Map.of(patient, Map.of(source.toString(), 3L));

        recorder.recordBatchedReach(page, acting, actor, null, "Batched read");
        recorder.recordBatchedReach(page, acting, actor, null, "Batched read");

        // Nothing here suppresses a repeat: the accounting has no notion of
        // one, and every way of inventing a window under-reported something.
        verify(auditEventLogService, times(2)).logEvents(any());
    }

    @Test
    @DisplayName("an audit failure never reaches the read it was accounting for")
    void batchedReachNeverThrows() {
        UUID acting = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        when(breakGlassGate.liveSessionId(any(), any(), any()))
            .thenThrow(new IllegalStateException("break-glass lookup down"));

        recorder.recordBatchedReach(Map.of(patient, Map.of(UUID.randomUUID().toString(), 1L)),
            acting, actor, null, "Batched read");

        verify(auditEventLogService, never()).logEvents(any());
    }

    @Test
    @DisplayName("the break-glass read runs in its own transaction, so a repository failure cannot poison the caller's")
    void breakGlassLookupSuspendsTheCallersTransaction() {
        // The lookup is a repository read and the caller is a read-only
        // transaction serving a GET: a failure inside it would otherwise mark
        // that transaction rollback-only, the catch would swallow the
        // exception, and the read would still die at commit. Only a new
        // transaction keeps the damage local — asserted structurally, because
        // a mocked gate cannot mark anything rollback-only.
        UUID acting = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        when(breakGlassGate.liveSessionId(any(), any(), any())).thenReturn(Optional.empty());

        recorder.recordBatchedReach(Map.of(UUID.randomUUID(), Map.of(UUID.randomUUID().toString(), 1L)),
            acting, actor, null, "Batched read");

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    @DisplayName("a page's break-glass reads share ONE transaction, however many patients it holds")
    void breakGlassLookupsShareOneTransaction() {
        UUID acting = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        when(breakGlassGate.liveSessionId(any(), any(), any())).thenReturn(Optional.empty());

        recorder.recordBatchedReach(Map.of(
            UUID.randomUUID(), Map.of(source.toString(), 1L),
            UUID.randomUUID(), Map.of(source.toString(), 1L),
            UUID.randomUUID(), Map.of(source.toString(), 1L)), acting, actor, null, "Batched read");

        // A transaction per patient was the cost the batching exists to remove.
        verify(transactionManager, times(1)).getTransaction(any());
        verify(breakGlassGate, times(3)).liveSessionId(any(), any(), any());
    }

    @Test
    @DisplayName("one patient failing costs that patient's row, not the page's")
    void batchedReachLosesOnlyTheFailingPatient() {
        UUID acting = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID broken = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(breakGlassGate.liveSessionId(actor, broken, acting))
            .thenThrow(new IllegalStateException("break-glass lookup down"));
        when(breakGlassGate.liveSessionId(actor, healthy, acting)).thenReturn(Optional.empty());

        recorder.recordBatchedReach(Map.of(
            broken, Map.of(source.toString(), 1L),
            healthy, Map.of(source.toString(), 2L)), acting, actor, null, "Batched read");

        // The per-patient recorder this replaced lost only its own patient;
        // wrapping the whole loop was a regression on that.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditEventRequestDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(auditEventLogService).logEvents(captor.capture());
        assertThat(captor.getValue()).singleElement()
            .extracting(AuditEventRequestDTO::getPatientId).isEqualTo(healthy);
    }

    @Test
    @DisplayName("a failing audit write never reaches the read either")
    void batchedReachSwallowsAWriteFailure() {
        UUID acting = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        doThrow(new IllegalStateException("audit down")).when(auditEventLogService).logEvents(any());

        recorder.recordBatchedReach(Map.of(patient, Map.of(UUID.randomUUID().toString(), 1L)),
            acting, actor, null, "Batched read");
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
