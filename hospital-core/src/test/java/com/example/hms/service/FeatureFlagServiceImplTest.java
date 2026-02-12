package com.example.hms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.config.FeatureFlagProperties;
import com.example.hms.model.platform.FeatureFlagOverride;
import com.example.hms.service.impl.FeatureFlagServiceImpl;
import com.example.hms.repository.platform.FeatureFlagOverrideRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class FeatureFlagServiceImplTest {

    private FeatureFlagProperties properties;
    private MockEnvironment environment;
    private FeatureFlagOverrideRepository overrideRepository;
    private FeatureFlagServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new FeatureFlagProperties();
        environment = new MockEnvironment();
        overrideRepository = mock(FeatureFlagOverrideRepository.class);
        when(overrideRepository.findAllByOrderByFlagKeyAsc()).thenReturn(List.of());
        service = new FeatureFlagServiceImpl(properties, environment, overrideRepository);
    }

    @Test
    void listFlagsMergesDefaultsAndOverrides() {
        properties.getDefaults().put("departmentGovernance", true);
        properties.getDefaults().put("hospitalPortfolio", false);
        properties.getOverrides().put("hospitalPortfolio", true);

        environment.setActiveProfiles("prod");

        Map<String, Boolean> flags = service.listFlags(null, Locale.ENGLISH);

        assertThat(flags)
            .containsEntry("departmentGovernance", true)
            .containsEntry("hospitalPortfolio", true);
    }

    @Test
    void listFlagsPrefersRequestedEnvironment() {
        properties.getDefaults().put("departmentGovernance", false);
        Map<String, Boolean> stagingFlags = new LinkedHashMap<>();
        stagingFlags.put("departmentGovernance", true);
        stagingFlags.put("betaPreview", true);
        properties.getEnvironments().put("staging", stagingFlags);

        Map<String, Boolean> flags = service.listFlags("staging", Locale.CANADA_FRENCH);

        assertThat(flags)
            .containsEntry("departmentGovernance", true)
            .containsEntry("betaPreview", true);
    }

    @Test
    void listFlagsUsesActiveProfileWhenNoOverrideProvided() {
        properties.getDefaults().put("departmentGovernance", false);
        Map<String, Boolean> devFlags = new LinkedHashMap<>();
        devFlags.put("departmentGovernance", true);
        properties.getEnvironments().put("dev", devFlags);

        environment.setActiveProfiles("dev");

        Map<String, Boolean> flags = service.listFlags(null, Locale.US);

        assertThat(flags)
            .containsEntry("departmentGovernance", true);
    }

    @Test
    void upsertOverrideTrimsKeyAndSanitizesDescription() {
        AtomicReference<FeatureFlagOverride> saved = new AtomicReference<>();
        when(overrideRepository.findByFlagKeyIgnoreCase("new-feature"))
            .thenReturn(Optional.empty());
        when(overrideRepository.save(any())).thenAnswer(invocation -> {
            FeatureFlagOverride entity = invocation.getArgument(0);
            saved.set(entity);
            return entity;
        });
        when(overrideRepository.findAllByOrderByFlagKeyAsc()).thenAnswer(invocation -> {
            FeatureFlagOverride entity = saved.get();
            return entity == null ? List.of() : List.of(entity);
        });

        String longDescription = "  ".repeat(10) + "A".repeat(300) + "  ";

        Map<String, Boolean> flags = service.upsertOverride(
            "  new-feature  ",
            true,
            longDescription,
            "tester",
            null,
            Locale.ENGLISH
        );

        FeatureFlagOverride persisted = saved.get();
        assertThat(persisted).isNotNull();
        assertThat(persisted.getFlagKey()).isEqualTo("new-feature");
        assertThat(persisted.getDescription()).hasSize(255);
        assertThat(persisted.isEnabled()).isTrue();
        assertThat(flags).containsEntry("new-feature", true);
    }

    @Test
    void upsertOverrideUpdatesExistingEntity() {
        FeatureFlagOverride existing = FeatureFlagOverride.builder()
            .flagKey("existing-flag")
            .enabled(false)
            .description("Old")
            .build();

        when(overrideRepository.findByFlagKeyIgnoreCase("existing-flag"))
            .thenReturn(Optional.of(existing));
        when(overrideRepository.save(existing)).thenReturn(existing);
        when(overrideRepository.findAllByOrderByFlagKeyAsc()).thenReturn(List.of(existing));

        Map<String, Boolean> flags = service.upsertOverride(
            "existing-flag",
            true,
            "updated",
            "tester",
            "qa",
            Locale.CANADA
        );

        assertThat(existing.isEnabled()).isTrue();
        assertThat(existing.getDescription()).isEqualTo("updated");
        assertThat(flags).containsEntry("existing-flag", true);
    }

    @Test
    void upsertOverrideThrowsForBlankKey() {
        assertThatThrownBy(() -> service.upsertOverride(
            "   ",
            true,
            "",
            "tester",
            null,
            Locale.ENGLISH
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Feature flag key");

        verify(overrideRepository, never()).save(any());
    }

    @Test
    void deleteOverrideRemovesEntityAndRefreshesFlags() {
        FeatureFlagOverride existing = FeatureFlagOverride.builder()
            .flagKey("beta-feature")
            .enabled(true)
            .build();

        when(overrideRepository.findByFlagKeyIgnoreCase("beta-feature"))
            .thenReturn(Optional.of(existing));
        when(overrideRepository.findAllByOrderByFlagKeyAsc()).thenReturn(List.of());

        Map<String, Boolean> flags = service.deleteOverride(
            "beta-feature",
            "tester",
            null,
            Locale.US
        );

        verify(overrideRepository).delete(existing);
        assertThat(flags).doesNotContainKey("beta-feature");
    }
}
