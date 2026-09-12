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
import static org.mockito.Mockito.when;
import com.example.hms.model.Hospital;

@ExtendWith(MockitoExtension.class)
class CrossHospitalReachRecorderTest {

    private record Row(UUID hospitalId) {
    }

    @Mock
    private AuditEventLogService auditEventLogService;

    @InjectMocks
    private CrossHospitalReachRecorder recorder;

    @Test
    @DisplayName("reachOf counts rows per foreign hospital and ignores local and hospital-less rows")
    void reachOfCountsForeignRowsPerSource() {
        UUID acting = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<Row> rows = List.of(new Row(acting), new Row(a), new Row(a), new Row(b), new Row(null));

        Map<String, Long> reach = CrossHospitalReachRecorder.reachOf(rows, Row::hospitalId, acting);

        assertThat(reach).containsOnly(Map.entry(a.toString(), 2L), Map.entry(b.toString(), 1L));
        assertThat(CrossHospitalReachRecorder.reachOf(null, Row::hospitalId, acting)).isEmpty();
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
}
