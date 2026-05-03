package com.example.hms.service.integration.health;

import com.example.hms.enums.integration.IntegrationHealthStatus;
import com.example.hms.model.Organization;
import com.example.hms.model.integration.IntegrationHealthSnapshot;
import com.example.hms.repository.IntegrationHealthSnapshotRepository;
import com.example.hms.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("IntegrationHealthRecorder")
class IntegrationHealthRecorderTest {

    private static final String INTEGRATION = "eligibility";

    private IntegrationHealthSnapshotRepository repository;
    private OrganizationRepository organizationRepository;
    private Organization organization;
    private UUID orgId;
    private IntegrationHealthRecorder recorder;

    @BeforeEach
    void setUp() {
        repository = mock(IntegrationHealthSnapshotRepository.class);
        organizationRepository = mock(OrganizationRepository.class);
        organization = new Organization();
        orgId = UUID.randomUUID();
        organization.setId(orgId);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(organization));
        when(repository.save(any(IntegrationHealthSnapshot.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        Clock clock = Clock.fixed(Instant.parse("2026-05-02T08:00:00Z"), ZoneOffset.UTC);
        // MVP-3b: ObjectProvider for the optional time-series repository.
        // Tests skip the event-log persistence path by providing null.
        org.springframework.beans.factory.ObjectProvider<com.example.hms.repository.integration.IntegrationHealthEventRepository>
            eventProvider = new org.springframework.beans.factory.ObjectProvider<>() {
                @Override public com.example.hms.repository.integration.IntegrationHealthEventRepository getObject() { return null; }
                @Override public com.example.hms.repository.integration.IntegrationHealthEventRepository getObject(Object... args) { return null; }
                @Override public com.example.hms.repository.integration.IntegrationHealthEventRepository getIfAvailable() { return null; }
                @Override public com.example.hms.repository.integration.IntegrationHealthEventRepository getIfUnique() { return null; }
            };
        recorder = new IntegrationHealthRecorder(repository, organizationRepository, clock, eventProvider);
    }

    @Test
    @DisplayName("recordSuccess on a fresh snapshot upserts as HEALTHY with success_count=1")
    void freshSuccessCreatesHealthy() {
        when(repository.findOneFor(INTEGRATION, orgId)).thenReturn(Optional.empty());

        recorder.recordSuccess(INTEGRATION, orgId);

        ArgumentCaptor<IntegrationHealthSnapshot> captor =
            ArgumentCaptor.forClass(IntegrationHealthSnapshot.class);
        verify(repository, times(1)).save(captor.capture());
        IntegrationHealthSnapshot saved = captor.getValue();
        assertThat(saved.getIntegrationId()).isEqualTo(INTEGRATION);
        assertThat(saved.getOrganization()).isSameAs(organization);
        assertThat(saved.getLastStatus()).isEqualTo(IntegrationHealthStatus.HEALTHY);
        assertThat(saved.getSuccessCount24h()).isEqualTo(1);
        assertThat(saved.getFailureCount24h()).isZero();
        assertThat(saved.getLastSuccessAt()).isNotNull();
        assertThat(saved.getCountsWindowStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("recordFailure flips status to FAILING and stamps the error message")
    void failureFlipsFailing() {
        IntegrationHealthSnapshot existing = IntegrationHealthSnapshot.builder()
            .integrationId(INTEGRATION)
            .organization(organization)
            .lastStatus(IntegrationHealthStatus.HEALTHY)
            .successCount24h(3)
            .failureCount24h(0)
            .countsWindowStartedAt(Instant.parse("2026-05-02T07:30:00Z").atZone(ZoneOffset.UTC).toLocalDateTime())
            .build();
        when(repository.findOneFor(INTEGRATION, orgId)).thenReturn(Optional.of(existing));

        recorder.recordFailure(INTEGRATION, orgId, "Payer timeout");

        ArgumentCaptor<IntegrationHealthSnapshot> captor =
            ArgumentCaptor.forClass(IntegrationHealthSnapshot.class);
        verify(repository, times(1)).save(captor.capture());
        IntegrationHealthSnapshot saved = captor.getValue();
        assertThat(saved.getLastStatus()).isEqualTo(IntegrationHealthStatus.FAILING);
        assertThat(saved.getLastErrorMessage()).isEqualTo("Payer timeout");
        assertThat(saved.getFailureCount24h()).isEqualTo(1);
        assertThat(saved.getSuccessCount24h()).isEqualTo(3);
    }

    @Test
    @DisplayName("a success after a failure within the same window returns DEGRADED, not HEALTHY")
    void successAfterFailureIsDegraded() {
        IntegrationHealthSnapshot existing = IntegrationHealthSnapshot.builder()
            .integrationId(INTEGRATION)
            .organization(organization)
            .lastStatus(IntegrationHealthStatus.FAILING)
            .successCount24h(3)
            .failureCount24h(1)
            .countsWindowStartedAt(Instant.parse("2026-05-02T07:30:00Z").atZone(ZoneOffset.UTC).toLocalDateTime())
            .build();
        when(repository.findOneFor(INTEGRATION, orgId)).thenReturn(Optional.of(existing));

        recorder.recordSuccess(INTEGRATION, orgId);

        ArgumentCaptor<IntegrationHealthSnapshot> captor =
            ArgumentCaptor.forClass(IntegrationHealthSnapshot.class);
        verify(repository).save(captor.capture());
        IntegrationHealthSnapshot saved = captor.getValue();
        assertThat(saved.getLastStatus()).isEqualTo(IntegrationHealthStatus.DEGRADED);
        assertThat(saved.getSuccessCount24h()).isEqualTo(4);
        assertThat(saved.getFailureCount24h()).isEqualTo(1);
    }

    @Test
    @DisplayName("counts roll over when the 24h window has elapsed")
    void rollWindowResetsCounts() {
        IntegrationHealthSnapshot existing = IntegrationHealthSnapshot.builder()
            .integrationId(INTEGRATION)
            .organization(organization)
            .lastStatus(IntegrationHealthStatus.HEALTHY)
            .successCount24h(50)
            .failureCount24h(0)
            // 30+ hours ago — older than the 24h window
            .countsWindowStartedAt(Instant.parse("2026-05-01T01:00:00Z").atZone(ZoneOffset.UTC).toLocalDateTime())
            .build();
        when(repository.findOneFor(INTEGRATION, orgId)).thenReturn(Optional.of(existing));

        recorder.recordSuccess(INTEGRATION, orgId);

        ArgumentCaptor<IntegrationHealthSnapshot> captor =
            ArgumentCaptor.forClass(IntegrationHealthSnapshot.class);
        verify(repository).save(captor.capture());
        IntegrationHealthSnapshot saved = captor.getValue();
        assertThat(saved.getSuccessCount24h()).isEqualTo(1);
        assertThat(saved.getFailureCount24h()).isZero();
    }

    @Test
    @DisplayName("repository failure is swallowed — never throws back to the caller")
    void repositoryFailureIsSwallowed() {
        when(repository.findOneFor(INTEGRATION, orgId))
            .thenThrow(new RuntimeException("DB down"));

        recorder.recordSuccess(INTEGRATION, orgId);

        verify(repository, never()).save(any());
    }
}
