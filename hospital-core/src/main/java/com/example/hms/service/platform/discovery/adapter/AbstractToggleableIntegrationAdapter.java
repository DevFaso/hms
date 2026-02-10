package com.example.hms.service.platform.discovery.adapter;

import com.example.hms.config.PlatformIntegrationProperties;
import com.example.hms.payload.dto.PlatformOwnershipDTO;
import com.example.hms.payload.dto.PlatformServiceMetadataDTO;
import com.example.hms.service.platform.discovery.IntegrationDescriptor;
import com.example.hms.service.platform.discovery.PlatformIntegrationAdapter;
import java.util.List;
import java.util.Locale;

public abstract class AbstractToggleableIntegrationAdapter implements PlatformIntegrationAdapter {

    private final PlatformIntegrationProperties properties;

    protected AbstractToggleableIntegrationAdapter(PlatformIntegrationProperties properties) {
        this.properties = properties;
    }

    @Override
    public IntegrationDescriptor describe(Locale locale) {
        PlatformIntegrationProperties.ModuleProperties config = properties.getModule(getServiceType());

        boolean enabled = config.getEnabled() != null ? config.getEnabled() : defaultEnabled();
        boolean autoProvision = config.getAutoProvision() != null ? config.getAutoProvision() : defaultAutoProvision();
        boolean managedByPlatform = config.getManagedByPlatform() != null ? config.getManagedByPlatform() : defaultManagedByPlatform();

        String displayName = firstNonBlank(config.getDisplayName(), defaultDisplayName(locale));
        String description = firstNonBlank(config.getDescription(), defaultDescription(locale));
        String provider = firstNonBlank(config.getProvider(), defaultProvider(locale));
        String baseUrl = firstNonBlank(config.getBaseUrl(), defaultBaseUrl(locale));
        String documentationUrl = firstNonBlank(config.getDocumentationUrl(), defaultDocumentationUrl(locale));
        String sandboxUrl = firstNonBlank(config.getSandboxUrl(), defaultSandboxUrl(locale));
        String onboardingGuideUrl = firstNonBlank(config.getOnboardingGuideUrl(), defaultOnboardingGuideUrl(locale));
        String featureFlag = firstNonBlank(config.getFeatureFlag(), defaultFeatureFlag());

        return IntegrationDescriptor.builder()
            .id(getServiceType().name().toLowerCase(Locale.ENGLISH))
            .serviceType(getServiceType())
            .displayName(displayName)
            .description(description)
            .provider(provider)
            .baseUrl(baseUrl)
            .documentationUrl(documentationUrl)
            .sandboxUrl(sandboxUrl)
            .onboardingGuideUrl(onboardingGuideUrl)
            .featureFlag(featureFlag)
            .capabilities(defaultCapabilities(locale))
            .enabled(enabled)
            .autoProvision(autoProvision && enabled)
            .managedByPlatform(managedByPlatform)
            .defaultOwnership(defaultOwnership(locale))
            .defaultMetadata(defaultMetadata(locale))
            .build();
    }

    protected abstract String defaultDisplayName(Locale locale);

    protected abstract String defaultDescription(Locale locale);

    protected abstract String defaultProvider(Locale locale);

    protected abstract List<String> defaultCapabilities(Locale locale);

    protected abstract PlatformServiceMetadataDTO defaultMetadata(Locale locale);

    protected PlatformOwnershipDTO defaultOwnership(Locale locale) {
        if (locale != null) {
            locale.getLanguage();
        }
        return PlatformOwnershipDTO.builder().build();
    }

    protected boolean defaultEnabled() {
        return true;
    }

    protected boolean defaultAutoProvision() {
        return false;
    }

    protected boolean defaultManagedByPlatform() {
        return true;
    }

    protected String defaultBaseUrl(Locale locale) {
        if (locale != null) {
            locale.getLanguage();
        }
        return null;
    }

    protected String defaultDocumentationUrl(Locale locale) {
        if (locale != null) {
            locale.getDisplayName(locale);
        }
        return null;
    }

    protected String defaultSandboxUrl(Locale locale) {
        if (locale != null) {
            locale.getCountry();
        }
        return null;
    }

    protected String defaultOnboardingGuideUrl(Locale locale) {
        if (locale != null) {
            locale.getISO3Language();
        }
        return null;
    }

    protected String defaultFeatureFlag() {
        return null;
    }

    private String firstNonBlank(String candidate, String fallback) {
        if (candidate != null && !candidate.isBlank()) {
            return candidate;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }
}
