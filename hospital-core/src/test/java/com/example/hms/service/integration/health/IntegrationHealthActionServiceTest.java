package com.example.hms.service.integration.health;

import com.example.hms.enums.integration.IntegrationHealthStatus;
import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.integration.IntegrationHealthEvent;
import com.example.hms.payload.dto.superadmin.IntegrationHistoryBucketDTO;
import com.example.hms.payload.dto.superadmin.IntegrationProbeResultDTO;
import com.example.hms.repository.integration.IntegrationHealthEventRepository;
import com.example.hms.service.integration.probe.IntegrationConnectivityProbe;
import com.example.hms.service.integration.probe.Probe;
import com.example.hms.service.integration.probe.Resyncable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationHealthActionServiceTest {

    @Mock private IntegrationHealthRecorder recorder;
    @Mock private IntegrationHealthEventRepository eventRepository;

    private IntegrationConnectivityProbe okProbe;
    private IntegrationConnectivityProbe failingProbe;
    private IntegrationConnectivityProbe throwingProbe;
    private Resyncable resyncable;
    private IntegrationHealthActionService service;

    @BeforeEach
    void setUp() {
        okProbe = new IntegrationConnectivityProbe() {
            @Override public String integrationId() { return "ok.partner"; }
            @Override public Probe probe() { return Probe.ok(42, "pong"); }
        };
        failingProbe = new IntegrationConnectivityProbe() {
            @Override public String integrationId() { return "stub.partner"; }
            @Override public Probe probe() { return Probe.failed("Connector in stub mode"); }
        };
        throwingProbe = new IntegrationConnectivityProbe() {
            @Override public String integrationId() { return "boom.partner"; }
            @Override public Probe probe() { throw new IllegalStateException("network down"); }
        };
        resyncable = new Resyncable() {
            @Override public String integrationId() { return "ok.partner"; }
            @Override public void resync(UUID organizationId) { /* no-op for test */ }
        };
        service = new IntegrationHealthActionService(
            List.of(okProbe, failingProbe, throwingProbe),
            List.of(resyncable),
            recorder,
            eventRepository);
    }

    @Test
    void testConnectionOnHealthyProbeRecordsSuccess() {
        UUID orgId = UUID.randomUUID();
        IntegrationProbeResultDTO result = service.testConnection("ok.partner", orgId);

        assertThat(result.isOk()).isTrue();
        assertThat(result.getLatencyMs()).isEqualTo(42L);
        verify(recorder).recordSuccess("ok.partner", orgId, 42L);
        verify(recorder, never()).recordFailure(anyString(), any(), anyString(), any());
    }

    @Test
    void testConnectionOnFailingStubRecordsFailure() {
        UUID orgId = UUID.randomUUID();
        IntegrationProbeResultDTO result = service.testConnection("stub.partner", orgId);

        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).contains("stub mode");
        verify(recorder).recordFailure("stub.partner", orgId, "Connector in stub mode", 0L);
    }

    @Test
    void testConnectionWrapsThrowingProbeIntoFailureResult() {
        UUID orgId = UUID.randomUUID();
        IntegrationProbeResultDTO result = service.testConnection("boom.partner", orgId);

        assertThat(result.isOk()).isFalse();
        assertThat(result.getMessage()).contains("network down");
        verify(recorder).recordFailure(anyString(), any(), anyString(), any());
    }

    @Test
    void testConnectionThrowsResourceNotFoundForUnknownIntegration() {
        assertThatThrownBy(() -> service.testConnection("not.registered", UUID.randomUUID()))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("not.registered");
    }

    @Test
    void supportsResyncReflectsRegisteredBeans() {
        assertThat(service.supportsResync("ok.partner")).isTrue();
        assertThat(service.supportsResync("stub.partner")).isFalse();
    }

    @Test
    void isKnownIntegrationCoversBothProbeAndResyncBeans() {
        assertThat(service.isKnownIntegration("ok.partner")).isTrue();
        assertThat(service.isKnownIntegration("stub.partner")).isTrue();
        assertThat(service.isKnownIntegration("nope")).isFalse();
    }

    @Test
    void getHistoryBucketsEventsByHourAscending() {
        LocalDateTime t1 = LocalDateTime.of(2026, 5, 2, 8, 15);
        LocalDateTime t2 = LocalDateTime.of(2026, 5, 2, 8, 45);
        LocalDateTime t3 = LocalDateTime.of(2026, 5, 2, 9, 5);

        when(eventRepository.findRecentForIntegration(any(), any())).thenReturn(List.of(
            event("ok.partner", IntegrationHealthStatus.HEALTHY, t1),
            event("ok.partner", IntegrationHealthStatus.FAILING, t2),
            event("ok.partner", IntegrationHealthStatus.HEALTHY, t3)
        ));

        List<IntegrationHistoryBucketDTO> buckets = service.getHistory("ok.partner", 24);

        assertThat(buckets).hasSize(2);
        assertThat(buckets.get(0).getBucketStart()).isEqualTo(LocalDateTime.of(2026, 5, 2, 8, 0));
        assertThat(buckets.get(0).getSuccessCount()).isEqualTo(1);
        assertThat(buckets.get(0).getFailureCount()).isEqualTo(1);
        assertThat(buckets.get(1).getBucketStart()).isEqualTo(LocalDateTime.of(2026, 5, 2, 9, 0));
        assertThat(buckets.get(1).getSuccessCount()).isEqualTo(1);
        assertThat(buckets.get(1).getFailureCount()).isZero();
    }

    @Test
    void getHistoryRejectsOutOfRangeWindow() {
        assertThatThrownBy(() -> service.getHistory("ok.partner", 0))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("between 1 and 168");
        assertThatThrownBy(() -> service.getHistory("ok.partner", 169))
            .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void resyncOnUnknownIntegrationLogsWarningAndDoesNothing() {
        // Bean missing — service must not blow up; recorder gets no calls.
        service.resync("not.registered", UUID.randomUUID());
        verify(recorder, times(0)).recordSuccess(anyString(), any());
    }

    private IntegrationHealthEvent event(String integrationId,
                                         IntegrationHealthStatus status,
                                         LocalDateTime when) {
        return IntegrationHealthEvent.builder()
            .integrationId(integrationId).status(status).recordedAt(when).build();
    }
}
