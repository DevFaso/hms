package com.example.hms.service.platform.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.enums.platform.PlatformServiceType;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PlatformIntegrationAdapterTest {

    @Test
    void shouldReturnDescriptorEnabledState() {
        PlatformIntegrationAdapter adapter = new PlatformIntegrationAdapter() {
            @Override
            public PlatformServiceType getServiceType() {
                return PlatformServiceType.EHR;
            }

            @Override
            public IntegrationDescriptor describe(Locale locale) {
                return IntegrationDescriptor.builder()
                    .id("ehr")
                    .serviceType(PlatformServiceType.EHR)
                    .displayName("EHR")
                    .enabled(true)
                    .build();
            }
        };

        assertThat(adapter.isEnabled(Locale.US)).isTrue();
    }

    @Test
    void shouldUseSuppliedLocaleWhenEvaluating() {
        AtomicReference<Locale> captured = new AtomicReference<>();
        PlatformIntegrationAdapter adapter = new PlatformIntegrationAdapter() {
            @Override
            public PlatformServiceType getServiceType() {
                return PlatformServiceType.LIMS;
            }

            @Override
            public IntegrationDescriptor describe(Locale locale) {
                captured.set(locale);
                return IntegrationDescriptor.builder()
                    .id("lims")
                    .serviceType(PlatformServiceType.LIMS)
                    .displayName("LIMS")
                    .enabled(false)
                    .build();
            }
        };

        boolean enabled = adapter.isEnabled(Locale.CANADA);

        assertThat(enabled).isFalse();
        assertThat(captured).hasValue(Locale.CANADA);
    }
}
