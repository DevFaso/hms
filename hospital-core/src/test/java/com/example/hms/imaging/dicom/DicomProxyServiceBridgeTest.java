package com.example.hms.imaging.dicom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.repository.ImagingReportRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
    private ImagingReportRepository imagingReportRepository;
    private DicomWebClient bridge;
    private DicomProxyService service;
    private final UUID hospitalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new DicomProxyProperties();
        auditService = mock(AuditEventLogService.class);
        userRepository = mock(UserRepository.class);
        imagingReportRepository = mock(ImagingReportRepository.class);
        bridge = mock(DicomWebClient.class);
        service = new DicomProxyService(
            properties, auditService, userRepository,
            imagingReportRepository, bridge);
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalId).build());
        // Default: the tenant guard recognises any study UID at the
        // active hospital. The cross-tenant test below overrides this.
        lenient().when(imagingReportRepository
            .existsByHospital_IdAndStudyInstanceUid(eq(hospitalId), any()))
            .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
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
    @DisplayName("listInstancesForStudy refuses a study UID that belongs to another hospital")
    void crossTenantStudyUidRejected() {
        properties.setEnabled(true);
        when(imagingReportRepository
            .existsByHospital_IdAndStudyInstanceUid(eq(hospitalId), eq("STUDY-OTHER")))
            .thenReturn(false);

        assertThat(service.listInstancesForStudy("STUDY-OTHER")).isEmpty();
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
    @DisplayName("fetchInstanceBytes returns empty when upstream returns null (404 path) + still audits")
    void wadoNullStillAudits() {
        properties.setEnabled(true);
        when(bridge.wadoFetchInstance(any(), any())).thenReturn(null);

        assertThat(service.fetchInstanceBytes("STUDY-5", "INSTANCE-Y")).isEmpty();
        verify(auditService).logEvent(any());
    }

    @Test
    @DisplayName("fetchInstanceBytes returns empty when flag off + does not call the bridge")
    void wadoFlagOffSkipsBridge() {
        assertThat(service.fetchInstanceBytes("STUDY-6", "INSTANCE-Z")).isEmpty();
        verify(bridge, never()).wadoFetchInstance(any(), any());
        verify(auditService, never()).logEvent(any());
    }

    @Test
    @DisplayName("fetchInstanceBytes returns empty on blank studyUid / instanceUid")
    void wadoRejectsBlanks() {
        properties.setEnabled(true);
        lenient().when(bridge.wadoFetchInstance(any(), any())).thenReturn(new byte[]{0});
        assertThat(service.fetchInstanceBytes("", "INSTANCE-Z")).isEmpty();
        assertThat(service.fetchInstanceBytes("STUDY-7", "")).isEmpty();
        verify(bridge, never()).wadoFetchInstance(any(), any());
    }

    @Test
    @DisplayName("fetchInstanceBytes refuses a study UID that belongs to another hospital")
    void wadoCrossTenantStudyUidRejected() {
        properties.setEnabled(true);
        when(imagingReportRepository
            .existsByHospital_IdAndStudyInstanceUid(eq(hospitalId), eq("STUDY-OTHER")))
            .thenReturn(false);

        assertThat(service.fetchInstanceBytes("STUDY-OTHER", "INSTANCE-Z")).isEmpty();
        verify(bridge, never()).wadoFetchInstance(any(), any());
        verify(auditService, never()).logEvent(any());
    }
}
