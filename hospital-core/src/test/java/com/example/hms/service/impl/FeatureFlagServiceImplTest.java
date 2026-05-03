package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.config.FeatureFlagProperties;
import com.example.hms.model.platform.FeatureFlagOverride;
import com.example.hms.repository.platform.FeatureFlagOverrideRepository;
import com.example.hms.service.SubscriptionFeatureGateService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class FeatureFlagServiceImplTest {

    @Mock
    private FeatureFlagOverrideRepository overrideRepository;

    @Mock
    private Environment environment;

    @Mock
    private SubscriptionFeatureGateService subscriptionFeatureGate;

    @Mock
    private com.example.hms.service.AuditEventLogService auditEventLogService;

    @Captor
    private ArgumentCaptor<FeatureFlagOverride> overrideCaptor;

    private FeatureFlagProperties properties;
    private FeatureFlagServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new FeatureFlagProperties();
        Map<String, Boolean> defaults = new LinkedHashMap<>();
        defaults.put("feature.alpha", Boolean.TRUE);
        properties.setDefaults(defaults);

        Map<String, Boolean> overrides = new LinkedHashMap<>();
        overrides.put("feature.beta", Boolean.FALSE);
        properties.setOverrides(overrides);

        Map<String, Map<String, Boolean>> environmentOverrides = new LinkedHashMap<>();
        environmentOverrides.put("staging", Map.of("feature.gamma", Boolean.TRUE));
        properties.setEnvironments(environmentOverrides);

        lenient().when(environment.getActiveProfiles()).thenReturn(new String[] {"staging"});

        service = new FeatureFlagServiceImpl(
            properties, environment, overrideRepository, subscriptionFeatureGate, auditEventLogService);

        // Default: no tenant context → gate is a no-op (matches the
        // legacy behaviour the existing tests expect). Individual
        // MVP-6b tests that exercise gating set up their own stubs.
        lenient().when(subscriptionFeatureGate.isFeatureAllowedForOrg(
            org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(true);
    }

    @Test
    void listFlagsMergesPropertiesAndOverrides() {
        when(overrideRepository.findAllByOrderByFlagKeyAsc()).thenReturn(List.of(
            FeatureFlagOverride.builder().flagKey("feature.delta").enabled(false).build()
        ));

        Map<String, Boolean> flags = service.listFlags(null, Locale.ENGLISH);

        assertThat(flags)
            .containsEntry("feature.alpha", true)
            .containsEntry("feature.beta", false)
            .containsEntry("feature.gamma", true)
            .containsEntry("feature.delta", false);
    }

    @Test
    void upsertOverrideCreatesOrUpdatesRecord() {
        when(overrideRepository.findByFlagKeyAndOrganizationId("feature.delta", null))
            .thenReturn(Optional.empty());
        when(overrideRepository.findAllByOrderByFlagKeyAsc()).thenReturn(List.of(
            FeatureFlagOverride.builder().flagKey("feature.delta").enabled(false).build()
        ));

        Map<String, Boolean> flags = service.upsertOverride(
            " feature.delta ",
            false,
            "   rollout paused   ",
            "tester",
            "staging",
            Locale.ENGLISH
        );

        verify(overrideRepository).save(overrideCaptor.capture());
        FeatureFlagOverride saved = overrideCaptor.getValue();
        assertThat(saved.getFlagKey()).isEqualTo("feature.delta");
        assertThat(saved.isEnabled()).isFalse();
        assertThat(saved.getDescription()).isEqualTo("rollout paused");
        assertThat(saved.getUpdatedBy()).isEqualTo("tester");

        assertThat(flags)
            .containsEntry("feature.delta", false)
            .containsEntry("feature.alpha", true);
    }

    @Test
    void perTenantUpsertWritesOrgScopedRow() {
        UUID orgId = UUID.randomUUID();
        when(overrideRepository.findByFlagKeyAndOrganizationId("feature.beta", orgId))
            .thenReturn(Optional.empty());
        when(overrideRepository.findAllByOrderByFlagKeyAsc()).thenReturn(List.of());

        service.upsertOverride(
            "feature.beta",
            true,
            "tenant-only override",
            "tester",
            null,
            orgId,
            Locale.ENGLISH);

        verify(overrideRepository).save(overrideCaptor.capture());
        FeatureFlagOverride saved = overrideCaptor.getValue();
        assertThat(saved.getFlagKey()).isEqualTo("feature.beta");
        assertThat(saved.getOrganizationId()).isEqualTo(orgId);
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void deleteOverrideRemovesRecordAndRecalculatesFlags() {
        FeatureFlagOverride stored = FeatureFlagOverride.builder()
            .flagKey("feature.beta")
            .enabled(false)
            .build();

        when(overrideRepository.findByFlagKeyAndOrganizationId("feature.beta", null))
            .thenReturn(Optional.of(stored));
        when(overrideRepository.findAllByOrderByFlagKeyAsc())
            .thenReturn(List.of());

        Map<String, Boolean> flags = service.deleteOverride(
            "feature.beta",
            "tester",
            null,
            Locale.ENGLISH
        );

        verify(overrideRepository).delete(stored);
        assertThat(flags)
            .containsEntry("feature.alpha", true)
            .containsEntry("feature.gamma", true)
            .containsEntry("feature.beta", false);
    }

    // ── MVP-6c plan-tier audit emission ───────────────────────────────

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext() {
        com.example.hms.security.context.HospitalContextHolder.clear();
    }

    private void setTenantContext(UUID orgId, UUID hospitalId) {
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder()
                .principalUserId(UUID.randomUUID())
                .principalUsername("ops.user")
                .activeOrganizationId(orgId)
                .activeHospitalId(hospitalId)
                .superAdmin(false)
                .permittedOrganizationIds(orgId == null ? java.util.Set.of() : java.util.Set.of(orgId))
                .build());
    }

    @Test
    void planGateBlockEmitsAuditEvent() {
        UUID orgId = UUID.randomUUID();
        setTenantContext(orgId, UUID.randomUUID());
        // Disable feature.alpha for the org via the gate.
        when(subscriptionFeatureGate.isFeatureAllowedForOrg(
            org.mockito.ArgumentMatchers.eq(orgId), org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> !"feature.alpha".equals(inv.getArgument(1)));

        service.listFlags(null, Locale.ENGLISH);

        org.mockito.ArgumentCaptor<com.example.hms.payload.dto.AuditEventRequestDTO> captor =
            org.mockito.ArgumentCaptor.forClass(com.example.hms.payload.dto.AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(captor.capture());
        assertThat(captor.getValue().getEventType())
            .isEqualTo(com.example.hms.enums.AuditEventType.PLAN_FEATURE_GATE_BLOCKED);
        assertThat(captor.getValue().getResourceId()).isEqualTo("feature.alpha");
    }

    @Test
    void planGateAuditDedupSuppressesRepeatEmitsWithinWindow() {
        UUID orgId = UUID.randomUUID();
        setTenantContext(orgId, UUID.randomUUID());
        when(subscriptionFeatureGate.isFeatureAllowedForOrg(
            org.mockito.ArgumentMatchers.eq(orgId), org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> !"feature.alpha".equals(inv.getArgument(1)));

        // Three back-to-back resolves — the dedup window must compress
        // them to a single audit emit.
        service.listFlags(null, Locale.ENGLISH);
        service.listFlags(null, Locale.ENGLISH);
        service.listFlags(null, Locale.ENGLISH);

        verify(auditEventLogService, org.mockito.Mockito.times(1))
            .logEvent(org.mockito.ArgumentMatchers.any(
                com.example.hms.payload.dto.AuditEventRequestDTO.class));
    }
}
