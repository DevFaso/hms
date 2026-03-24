package com.example.hms.service.platform.discovery.adapter;

import com.example.hms.config.PlatformIntegrationProperties;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.payload.dto.PlatformOwnershipDTO;
import com.example.hms.payload.dto.PlatformServiceMetadataDTO;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class BillingIntegrationAdapter extends AbstractToggleableIntegrationAdapter {

    public BillingIntegrationAdapter(PlatformIntegrationProperties properties) {
        super(properties);
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
        return "Revenue cycle & billing";
    }

    @Override
    protected String defaultDescription(Locale locale) {
        return "Claims submission stub with ERA/EDI test flows and reconciliation dashboards.";
    }

    @Override
    protected String defaultProvider(Locale locale) {
        return "RevenueCycle Stub";
    }

    @Override
    protected List<String> defaultCapabilities(Locale locale) {
        return List.of(
            "837 claim batching",
            "835 remittance ingest",
            "Denial workflow webhooks"
        );
    }

    @Override
    protected PlatformServiceMetadataDTO defaultMetadata(Locale locale) {
        return PlatformServiceMetadataDTO.builder()
            .billingSystem("RevenueCycle Stub")
            .integrationNotes("Sandbox limited to synthetic providers and payers.")
            .build();
    }

    @Override
    protected PlatformOwnershipDTO defaultOwnership(Locale locale) {
        return PlatformOwnershipDTO.builder()
            .ownerTeam("Finance Ops Guild")
            .ownerContactEmail("billing-ops@example.com")
            .serviceLevel("Business hours")
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
