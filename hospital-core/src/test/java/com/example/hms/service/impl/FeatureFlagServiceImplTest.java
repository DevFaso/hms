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
    void upsertOverrideEmitsConfigurationChangedAudit() {
        // MVP-c3 — feature-flag writes must surface under the
        // PLATFORM_CONFIG source on the audit-search aggregation tab.
        when(overrideRepository.findByFlagKeyAndOrganizationId("feature.delta", null))
            .thenReturn(Optional.empty());
        when(overrideRepository.findAllByOrderByFlagKeyAsc()).thenReturn(List.of());

        service.upsertOverride(
            "feature.delta",
            true,
            "enable for canary",
            "operator",
            "staging",
            Locale.ENGLISH
        );

        ArgumentCaptor<com.example.hms.payload.dto.AuditEventRequestDTO> cap =
            ArgumentCaptor.forClass(com.example.hms.payload.dto.AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(cap.capture());
        assertThat(cap.getValue().getEventType())
            .isEqualTo(com.example.hms.enums.AuditEventType.CONFIGURATION_CHANGED);
        assertThat(cap.getValue().getEntityType()).isEqualTo("FEATURE_FLAG_OVERRIDE");
        assertThat(cap.getValue().getResourceId()).isEqualTo("feature.delta");
        assertThat(cap.getValue().getUserName()).isEqualTo("operator");
    }

    @Test
    void deleteOverrideEmitsConfigurationChangedAudit() {
        FeatureFlagOverride stored = FeatureFlagOverride.builder()
            .flagKey("feature.beta")
            .enabled(true)
            .build();
        when(overrideRepository.findByFlagKeyAndOrganizationId("feature.beta", null))
            .thenReturn(Optional.of(stored));
        when(overrideRepository.findAllByOrderByFlagKeyAsc()).thenReturn(List.of());

        service.deleteOverride(
            "feature.beta",
            "operator",
            null,
            Locale.ENGLISH
        );

        ArgumentCaptor<com.example.hms.payload.dto.AuditEventRequestDTO> cap =
            ArgumentCaptor.forClass(com.example.hms.payload.dto.AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(cap.capture());
        assertThat(cap.getValue().getEventType())
            .isEqualTo(com.example.hms.enums.AuditEventType.CONFIGURATION_CHANGED);
        assertThat(cap.getValue().getResourceName()).isEqualTo("FEATURE_FLAG_OVERRIDE_DELETED");
    }

    @Test
    void upsertOverrideSucceedsEvenWhenAuditEmissionThrows() {
        when(overrideRepository.findByFlagKeyAndOrganizationId("feature.delta", null))
            .thenReturn(Optional.empty());
        when(overrideRepository.findAllByOrderByFlagKeyAsc()).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new RuntimeException("audit pipeline down"))
            .when(auditEventLogService).logEvent(org.mockito.ArgumentMatchers.any());

        // Audit failure must not roll back the operator's intended write.
        Map<String, Boolean> flags = service.upsertOverride(
            "feature.delta", true, "ok", "operator", "staging", Locale.ENGLISH);
        assertThat(flags).isNotNull();
        verify(overrideRepository).save(overrideCaptor.capture());
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

    @Test
    void planGateAuditDedupEvictsEntriesOlderThanTwiceTheWindow() {
        // Copilot review fix #5 — the dedup map used to grow without
        // bound. evictExpiredAuditDedupEntries(now) must drop entries
        // whose last-emit timestamp is older than 2× the dedup window.
        UUID orgId = UUID.randomUUID();
        setTenantContext(orgId, UUID.randomUUID());
        when(subscriptionFeatureGate.isFeatureAllowedForOrg(
            org.mockito.ArgumentMatchers.eq(orgId), org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> !"feature.alpha".equals(inv.getArgument(1)));

        // Populate the map by emitting once.
        service.listFlags(null, Locale.ENGLISH);
        assertThat(service.planGateAuditDedupSize()).isEqualTo(1);

        // Sweep with a "now" 30 minutes in the future — entries older
        // than 2× the 5-minute dedup window (>10 min) must be removed.
        long farFuture = System.currentTimeMillis() + (30L * 60L * 1000L);
        service.evictExpiredAuditDedupEntries(farFuture);

        assertThat(service.planGateAuditDedupSize()).isZero();
    }

    @Test
    void listOverridesMapsRowsInFlagKeyOrder() {
        UUID orgId = UUID.randomUUID();
        FeatureFlagOverride globalRow = FeatureFlagOverride.builder()
            .flagKey("feature.alpha").enabled(true).updatedBy("root").build();
        FeatureFlagOverride tenantRow = FeatureFlagOverride.builder()
            .flagKey("feature.beta").enabled(false).organizationId(orgId).build();
        when(overrideRepository.findAllByOrderByFlagKeyAsc())
            .thenReturn(List.of(globalRow, tenantRow));

        var result = service.listOverrides();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getFlagKey()).isEqualTo("feature.alpha");
        assertThat(result.get(0).isEnabled()).isTrue();
        assertThat(result.get(0).getUpdatedBy()).isEqualTo("root");
        assertThat(result.get(0).getOrganizationId()).isNull();
        assertThat(result.get(1).getOrganizationId()).isEqualTo(orgId);
    }
}
