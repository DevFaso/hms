package com.example.hms.service.platform.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.enums.platform.PlatformServiceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultPlatformServiceRegistryTest {

    private PlatformIntegrationAdapter enabledAdapter;
    private DefaultPlatformServiceRegistry registry;

    @BeforeEach
    void setUp() {
        enabledAdapter = stubAdapter(PlatformServiceType.EHR, IntegrationDescriptor.builder()
            .id("ehr")
            .serviceType(PlatformServiceType.EHR)
            .displayName("EHR Core Interop")
            .enabled(true)
            .autoProvision(true)
            .managedByPlatform(true)
            .build());

        PlatformIntegrationAdapter disabledAdapter = stubAdapter(PlatformServiceType.INVENTORY, IntegrationDescriptor.builder()
            .id("inventory")
            .serviceType(PlatformServiceType.INVENTORY)
            .displayName("Inventory Bridge")
            .enabled(false)
            .autoProvision(false)
            .managedByPlatform(false)
            .build());

        registry = new DefaultPlatformServiceRegistry(List.of(enabledAdapter, disabledAdapter));
    }

    @Test
    void shouldListOnlyEnabledDescriptorsByDefault() {
        List<IntegrationDescriptor> descriptors = registry.listIntegrations(false, Locale.US);

        assertThat(descriptors)
            .hasSize(1)
            .first()
            .satisfies(descriptor -> {
                assertThat(descriptor.getServiceType()).isEqualTo(PlatformServiceType.EHR);
                assertThat(descriptor.isEnabled()).isTrue();
            });
    }

    @Test
    void shouldIncludeDisabledDescriptorsWhenRequested() {
        List<IntegrationDescriptor> descriptors = registry.listIntegrations(true, Locale.US);

        assertThat(descriptors)
            .hasSize(2)
            .anyMatch(descriptor -> descriptor.getServiceType() == PlatformServiceType.INVENTORY && !descriptor.isEnabled());
    }

    @Test
    void shouldFindDescriptorByServiceType() {
        assertThat(registry.findIntegration(PlatformServiceType.EHR, Locale.US))
            .isPresent()
            .get()
            .satisfies(descriptor -> assertThat(descriptor.getId()).isEqualTo("ehr"));

        assertThat(registry.findIntegration(PlatformServiceType.LIMS, Locale.US)).isEmpty();
    }

    @Test
    void shouldUseDefaultLocaleWhenInputLocaleIsNull() {
        Locale original = Locale.getDefault();
        Locale target = Locale.CANADA_FRENCH;
        Locale.setDefault(target);
        try {
            AtomicReference<Locale> captured = new AtomicReference<>();
            PlatformIntegrationAdapter capturingAdapter = new PlatformIntegrationAdapter() {
                @Override
                public PlatformServiceType getServiceType() {
                    return PlatformServiceType.EHR;
                }

                @Override
                public IntegrationDescriptor describe(Locale locale) {
                    captured.set(locale);
                    return enabledDescriptor("captured", "Captured", true, PlatformServiceType.EHR);
                }
            };

            DefaultPlatformServiceRegistry localeAwareRegistry = new DefaultPlatformServiceRegistry(List.of(capturingAdapter));

            localeAwareRegistry.listIntegrations(true, null);

            assertThat(captured).hasValue(target);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void shouldSkipNullDescriptors() {
        List<PlatformIntegrationAdapter> adapters = new ArrayList<>();
        adapters.add(enabledAdapter);
        adapters.add(new PlatformIntegrationAdapter() {
            @Override
            public PlatformServiceType getServiceType() {
                return PlatformServiceType.BILLING;
            }

            @Override
            public IntegrationDescriptor describe(Locale locale) {
                return null;
            }
        });

        DefaultPlatformServiceRegistry registryWithNulls = new DefaultPlatformServiceRegistry(adapters);

        List<IntegrationDescriptor> descriptors = registryWithNulls.listIntegrations(true, Locale.US);

        assertThat(descriptors)
            .extracting(IntegrationDescriptor::getServiceType)
            .containsExactly(PlatformServiceType.EHR);
    }

    @Test
    void shouldReturnEmptyOptionalWhenServiceTypeIsNull() {
        assertThat(registry.findIntegration(null, Locale.US)).isEmpty();
    }

    @Test
    void shouldSortDescriptorsByDisplayNameIgnoringCase() {
    PlatformIntegrationAdapter beta = stubAdapter(PlatformServiceType.BILLING, enabledDescriptor("billing", "beta", true, PlatformServiceType.BILLING));
    PlatformIntegrationAdapter alpha = stubAdapter(PlatformServiceType.ANALYTICS, enabledDescriptor("analytics", "Alpha", true, PlatformServiceType.ANALYTICS));
    PlatformIntegrationAdapter nullName = stubAdapter(PlatformServiceType.LIMS, enabledDescriptor("lims", null, true, PlatformServiceType.LIMS));

        DefaultPlatformServiceRegistry sortingRegistry = new DefaultPlatformServiceRegistry(List.of(beta, alpha, nullName));

        List<IntegrationDescriptor> ordered = sortingRegistry.listIntegrations(true, Locale.US);

        assertThat(ordered)
            .extracting(IntegrationDescriptor::getDisplayName)
            .containsExactly("Alpha", "beta", null);
    }

    @Test
    void shouldDelegateListEnabledIntegrationsToListIntegrations() {
        AtomicReference<Boolean> includeDisabledFlag = new AtomicReference<>();
        AtomicReference<Locale> localeCaptured = new AtomicReference<>();
        PlatformServiceRegistry customRegistry = new PlatformServiceRegistry() {
            @Override
            public List<IntegrationDescriptor> listIntegrations(boolean includeDisabled, Locale locale) {
                includeDisabledFlag.set(includeDisabled);
                localeCaptured.set(locale);
                return List.of();
            }

            @Override
            public Optional<IntegrationDescriptor> findIntegration(PlatformServiceType serviceType, Locale locale) {
                return Optional.empty();
            }
        };

        customRegistry.listEnabledIntegrations(Locale.JAPAN);

        assertThat(includeDisabledFlag).hasValue(false);
        assertThat(localeCaptured).hasValue(Locale.JAPAN);
    }

    private PlatformIntegrationAdapter stubAdapter(PlatformServiceType type, IntegrationDescriptor descriptor) {
        return new PlatformIntegrationAdapter() {
            @Override
            public PlatformServiceType getServiceType() {
                return type;
            }

            @Override
            public IntegrationDescriptor describe(Locale locale) {
                return descriptor;
            }
        };
    }

    private IntegrationDescriptor enabledDescriptor(String id, String name, boolean enabled, PlatformServiceType type) {
        return IntegrationDescriptor.builder()
            .id(id)
            .serviceType(type)
            .displayName(name)
            .enabled(enabled)
            .autoProvision(enabled)
            .managedByPlatform(enabled)
            .build();
    }
}
