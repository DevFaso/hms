package com.example.hms.service.impl;

import com.example.hms.enums.integration.IntegrationHealthStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.IntegrationHealthSnapshotMapper;
import com.example.hms.model.Organization;
import com.example.hms.model.integration.IntegrationHealthSnapshot;
import com.example.hms.payload.dto.superadmin.IntegrationHealthRowDTO;
import com.example.hms.payload.dto.superadmin.IntegrationHealthSummaryDTO;
import com.example.hms.repository.IntegrationHealthSnapshotRepository;
import com.example.hms.service.integration.eligibility.EligibilityProvider;
import com.example.hms.service.integration.eligibility.StubEligibilityProvider;
import com.example.hms.service.platform.discovery.IntegrationDescriptor;
import com.example.hms.service.platform.discovery.PlatformIntegrationAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SuperAdminIntegrationHealthServiceImpl")
class SuperAdminIntegrationHealthServiceImplTest {

    private IntegrationHealthSnapshotRepository repository;
    private IntegrationHealthSnapshotMapper mapper;
    private SuperAdminIntegrationHealthServiceImpl service;
    private FakeAdapter ehrAdapter;
    private Organization organizationA;
    private Organization organizationB;
    private final List<IntegrationHealthSnapshot> snapshots = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(IntegrationHealthSnapshotRepository.class);
        snapshots.clear();
        when(repository.findAllByOrderByIntegrationIdAscLastStatusAsc())
            .thenAnswer(inv -> List.copyOf(snapshots));
        when(repository.findByIntegrationIdOrderByLastStatusAscUpdatedAtDesc(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> {
                String id = inv.getArgument(0);
                return snapshots.stream().filter(s -> id.equals(s.getIntegrationId())).toList();
            });
        mapper = new IntegrationHealthSnapshotMapper();
        ehrAdapter = new FakeAdapter("ehr", PlatformServiceType.EHR, "EHR Sandbox");
        EligibilityProvider eligibility = new StubEligibilityProvider();
        service = new SuperAdminIntegrationHealthServiceImpl(
            List.of(ehrAdapter), List.of(eligibility), repository, mapper);
        organizationA = new Organization();
        organizationA.setId(UUID.randomUUID());
        organizationA.setName("Korle Bu Polyclinic Group");
        organizationB = new Organization();
        organizationB.setId(UUID.randomUUID());
        organizationB.setName("Faso Mutuelle Network");
    }

    @Test
    @DisplayName("inventory lists every adapter + eligibility row even when no snapshots exist")
    void inventoryListsAllRegistered() {
        IntegrationHealthSummaryDTO summary = service.getInventory();

        assertThat(summary.getTotalIntegrations()).isEqualTo(2);
        assertThat(summary.getNoHistoryCount()).isEqualTo(2);
        assertThat(summary.getIntegrations())
            .extracting(IntegrationHealthRowDTO::getIntegrationId)
            .containsExactlyInAnyOrder("ehr", "eligibility");
        for (IntegrationHealthRowDTO row : summary.getIntegrations()) {
            assertThat(row.getRolledUpStatus()).isEqualTo(IntegrationHealthStatus.NO_HISTORY);
            assertThat(row.getOrganizations()).isEmpty();
        }
    }

    @Test
    @DisplayName("inventory rolls up to FAILING when any org snapshot is failing")
    void rolledUpStatusPicksWorst() {
        snapshots.add(snapshot("eligibility", organizationA,
            IntegrationHealthStatus.HEALTHY, 5, 0));
        snapshots.add(snapshot("eligibility", organizationB,
            IntegrationHealthStatus.FAILING, 1, 4));

        IntegrationHealthSummaryDTO summary = service.getInventory();

        IntegrationHealthRowDTO eligibilityRow = summary.getIntegrations().stream()
            .filter(r -> "eligibility".equals(r.getIntegrationId()))
            .findFirst().orElseThrow();
        assertThat(eligibilityRow.getRolledUpStatus()).isEqualTo(IntegrationHealthStatus.FAILING);
        assertThat(eligibilityRow.getOrganizations()).hasSize(2);
        assertThat(summary.getFailingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("getIntegration returns one row with snapshot history; unknown id throws 404")
    void getIntegrationDrillDown() {
        snapshots.add(snapshot("eligibility", organizationA,
            IntegrationHealthStatus.HEALTHY, 7, 0));

        IntegrationHealthRowDTO row = service.getIntegration("eligibility");

        assertThat(row.getIntegrationId()).isEqualTo("eligibility");
        assertThat(row.getOrganizations()).hasSize(1);
        assertThat(row.getOrganizations().get(0).getOrganizationName())
            .isEqualTo("Korle Bu Polyclinic Group");

        assertThatThrownBy(() -> service.getIntegration("does-not-exist"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    private IntegrationHealthSnapshot snapshot(String integrationId,
                                               Organization org,
                                               IntegrationHealthStatus status,
                                               int successes,
                                               int failures) {
        return IntegrationHealthSnapshot.builder()
            .integrationId(integrationId)
            .organization(org)
            .lastStatus(status)
            .successCount24h(successes)
            .failureCount24h(failures)
            .lastSuccessAt(successes > 0 ? LocalDateTime.now() : null)
            .lastFailureAt(failures > 0 ? LocalDateTime.now() : null)
            .build();
    }

    /**
     * Minimal adapter that returns a deterministic descriptor — keeps the test
     * isolated from {@link com.example.hms.service.platform.discovery.adapter.AbstractToggleableIntegrationAdapter}
     * and its config-properties wiring.
     */
    private static final class FakeAdapter implements PlatformIntegrationAdapter {
        private final String id;
        private final PlatformServiceType type;
        private final String displayName;

        FakeAdapter(String id, PlatformServiceType type, String displayName) {
            this.id = id;
            this.type = type;
            this.displayName = displayName;
        }

        @Override public PlatformServiceType getServiceType() { return type; }

        @Override public IntegrationDescriptor describe(Locale locale) {
            return IntegrationDescriptor.builder()
                .id(id)
                .serviceType(type)
                .displayName(displayName)
                .provider("test")
                .enabled(true)
                .capabilities(List.of("test cap"))
                .build();
        }
    }
}
