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
            properties, environment, overrideRepository, subscriptionFeatureGate);

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
        when(overrideRepository.findByFlagKeyIgnoreCase("feature.delta"))
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
    void deleteOverrideRemovesRecordAndRecalculatesFlags() {
        FeatureFlagOverride stored = FeatureFlagOverride.builder()
            .flagKey("feature.beta")
            .enabled(false)
            .build();

        when(overrideRepository.findByFlagKeyIgnoreCase("feature.beta"))
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
}
