package com.example.hms.service.platform.discovery.adapter;

import com.example.hms.config.PlatformIntegrationProperties;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.payload.dto.PlatformOwnershipDTO;
import com.example.hms.payload.dto.PlatformServiceMetadataDTO;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

@Component
public class BillingIntegrationAdapter extends AbstractToggleableIntegrationAdapter {

    public BillingIntegrationAdapter(PlatformIntegrationProperties properties, MessageSource messageSource) {
        super(properties, messageSource);
    }

    @Override
    public PlatformServiceType getServiceType() {
        return PlatformServiceType.BILLING;
    }

    @Override
    protected boolean defaultManagedByPlatform() {
        return false;
    }

    @Override
    protected String defaultDisplayName(Locale locale) {
        return text("platform.catalog.billing.displayName", locale);
    }

    @Override
    protected String defaultDescription(Locale locale) {
        return text("platform.catalog.billing.description", locale);
    }

    @Override
    protected String defaultProvider(Locale locale) {
        return "RevenueCycle Stub";
    }

    @Override
    protected List<String> defaultCapabilities(Locale locale) {
        return List.of(
            text("platform.catalog.billing.capability.1", locale),
            text("platform.catalog.billing.capability.2", locale),
            text("platform.catalog.billing.capability.3", locale)
        );
    }

    @Override
    protected PlatformServiceMetadataDTO defaultMetadata(Locale locale) {
        return PlatformServiceMetadataDTO.builder()
            .billingSystem("RevenueCycle Stub")
            .integrationNotes(text("platform.catalog.billing.integrationNotes", locale))
            .build();
    }

    @Override
    protected PlatformOwnershipDTO defaultOwnership(Locale locale) {
        return PlatformOwnershipDTO.builder()
            .ownerTeam("Finance Ops Guild")
            .ownerContactEmail("billing-ops@example.com")
            .serviceLevel(text("platform.catalog.billing.serviceLevel", locale))
            .build();
    }

    @Override
    protected String defaultDocumentationUrl(Locale locale) {
        return "https://docs.internal/platform/billing";
    }

    @Override
    protected String defaultFeatureFlag() {
        return "ff.platform.billing";
    }
}
