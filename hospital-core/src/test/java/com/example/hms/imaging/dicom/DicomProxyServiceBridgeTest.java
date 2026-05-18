package com.example.hms.imaging.dicom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.repository.UserRepository;
import com.example.hms.service.AuditEventLogService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Row 42 follow-on: pins the bridge delegation between
 * {@link DicomProxyService} and {@link DicomWebClient}. Co-exists
 * with {@code DicomProxyServiceTest} which pins the foundation-pass
 * audit-only contract (no upstream bean injected).
 */
class DicomProxyServiceBridgeTest {

    private DicomProxyProperties properties;
    private AuditEventLogService auditService;
    private UserRepository userRepository;
    private DicomWebClient bridge;
    private DicomProxyService service;

    @BeforeEach
    void setUp() {
        properties = new DicomProxyProperties();
        auditService = mock(AuditEventLogService.class);
        userRepository = mock(UserRepository.class);
        bridge = mock(DicomWebClient.class);
        service = new DicomProxyService(properties, auditService, userRepository, bridge);
    }

    @Test
    @DisplayName("listInstancesForStudy delegates to the bridge when the flag is on")
    void delegatesQido() {
        properties.setEnabled(true);
        when(bridge.qidoListInstances("STUDY-1"))
            .thenReturn(List.of("INSTANCE-A", "INSTANCE-B"));

        List<String> result = service.listInstancesForStudy("STUDY-1");

        assertThat(result).containsExactly("INSTANCE-A", "INSTANCE-B");
        verify(bridge).qidoListInstances("STUDY-1");
        verify(auditService).logEvent(any());
    }

    @Test
    @DisplayName("listInstancesForStudy emits the audit even when the upstream returns empty")
    void emptyResultStillAudits() {
        properties.setEnabled(true);
        when(bridge.qidoListInstances(any())).thenReturn(List.of());

        assertThat(service.listInstancesForStudy("STUDY-2")).isEmpty();
        verify(auditService).logEvent(any());
    }

    @Test
    @DisplayName("listInstancesForStudy skips the bridge when the master flag is off")
    void flagOffSkipsBridge() {
        // properties.enabled left false
        assertThat(service.listInstancesForStudy("STUDY-3")).isEmpty();
        verify(bridge, never()).qidoListInstances(any());
        verify(auditService, never()).logEvent(any());
    }

    @Test
    @DisplayName("fetchInstanceBytes delegates to wadoFetchInstance + audits the byte count")
    void delegatesWado() {
        properties.setEnabled(true);
        byte[] payload = new byte[]{1, 2, 3, 4};
        when(bridge.wadoFetchInstance("STUDY-4", "INSTANCE-X")).thenReturn(payload);

        byte[] result = service.fetchInstanceBytes("STUDY-4", "INSTANCE-X");

        assertThat(result).isSameAs(payload);
        verify(bridge).wadoFetchInstance("STUDY-4", "INSTANCE-X");
        verify(auditService).logEvent(any());
    }

    @Test
    @DisplayName("fetchInstanceBytes returns null when upstream returns null (404 path) + still audits")
    void wadoNullStillAudits() {
        properties.setEnabled(true);
        when(bridge.wadoFetchInstance(any(), any())).thenReturn(null);

        assertThat(service.fetchInstanceBytes("STUDY-5", "INSTANCE-Y")).isNull();
        verify(auditService).logEvent(any());
    }

    @Test
    @DisplayName("fetchInstanceBytes returns null when flag off + does not call the bridge")
    void wadoFlagOffSkipsBridge() {
        assertThat(service.fetchInstanceBytes("STUDY-6", "INSTANCE-Z")).isNull();
        verify(bridge, never()).wadoFetchInstance(any(), any());
        verify(auditService, never()).logEvent(any());
    }

    @Test
    @DisplayName("fetchInstanceBytes returns null on blank studyUid / instanceUid")
    void wadoRejectsBlanks() {
        properties.setEnabled(true);
        lenient().when(bridge.wadoFetchInstance(any(), any())).thenReturn(new byte[]{0});
        assertThat(service.fetchInstanceBytes("", "INSTANCE-Z")).isNull();
        assertThat(service.fetchInstanceBytes("STUDY-7", "")).isNull();
        verify(bridge, never()).wadoFetchInstance(any(), any());
    }
}
