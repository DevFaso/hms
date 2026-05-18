package com.example.hms.imaging.dicom;

import com.example.hms.repository.UserRepository;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link DicomProxyService}. The foundation pass
 * returns an empty list unconditionally; these tests pin that
 * contract so a half-implementation can't ship silently.
 */
class DicomProxyServiceTest {

    private DicomProxyProperties properties;
    private AuditEventLogService auditService;
    private DicomProxyService service;

    @BeforeEach
    void setUp() {
        properties = new DicomProxyProperties();
        auditService = mock(AuditEventLogService.class);
        // userRepository is mocked because SecurityUtils.getCurrentUsername()
        // returns null in this no-context unit test — the audit emission
        // path short-circuits before any repository call, so the mock
        // never sees a method invocation.
        // Row-42 follow-on: the optional DicomWebClient param is null
        // here so the test still pins the foundation-pass audit-only
        // contract (no upstream call attempted, audit row emitted).
        service = new DicomProxyService(
            properties, auditService, mock(UserRepository.class), null);
    }

    @Test
    @DisplayName("isEnabled reflects the configuration property")
    void isEnabledReflectsProperty() {
        assertThat(service.isEnabled()).isFalse();
        properties.setEnabled(true);
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("listInstancesForStudy returns empty + skips audit when flag off")
    void emptyAndNoAuditWhenFlagOff() {
        assertThat(service.listInstancesForStudy("1.2.3")).isEmpty();
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("listInstancesForStudy returns empty when studyUid is blank")
    void emptyWhenBlankUid() {
        properties.setEnabled(true);
        assertThat(service.listInstancesForStudy("")).isEmpty();
        assertThat(service.listInstancesForStudy(null)).isEmpty();
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("listInstancesForStudy returns empty + emits audit when flag on (foundation pass)")
    void emptyButAuditWhenFlagOn() {
        properties.setEnabled(true);
        assertThat(service.listInstancesForStudy("1.2.840.113619.2.55.1")).isEmpty();
        // Audit emission happens even when the upstream is a no-op so
        // the trail accumulates real-world usage data; mock verifies
        // the method was invoked.
        org.mockito.Mockito.verify(auditService).logEvent(org.mockito.ArgumentMatchers.any());
    }
}
