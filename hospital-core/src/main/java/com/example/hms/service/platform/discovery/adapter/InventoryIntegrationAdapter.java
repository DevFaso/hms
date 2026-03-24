package com.example.hms.service.platform.discovery.adapter;

import com.example.hms.config.PlatformIntegrationProperties;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.payload.dto.PlatformOwnershipDTO;
import com.example.hms.payload.dto.PlatformServiceMetadataDTO;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class InventoryIntegrationAdapter extends AbstractToggleableIntegrationAdapter {

    public InventoryIntegrationAdapter(PlatformIntegrationProperties properties) {
        super(properties);
    }

    @Override
    public PlatformServiceType getServiceType() {
        return PlatformServiceType.INVENTORY;
    }

    @Override
    protected boolean defaultEnabled() {
        return false;
    }

    @Override
    protected String defaultDisplayName(Locale locale) {
        return "Supply chain & formulary";
    }

    @Override
    protected String defaultDescription(Locale locale) {
        return "Inventory bridge stub for implants, pharmacy stock and par-level monitoring.";
    }

    @Override
    protected String defaultProvider(Locale locale) {
        return "InventoryBridge Stub";
    }

    @Override
    protected List<String> defaultCapabilities(Locale locale) {
        return List.of(
            "Requisition sync",
            "Lot/expiration alerts",
            "Par-level variance reports"
        );
    }

    @Override
    protected PlatformServiceMetadataDTO defaultMetadata(Locale locale) {
        return PlatformServiceMetadataDTO.builder()
            .inventorySystem("InventoryBridge Stub")
            .integrationNotes("Disabled by default until site readiness review is complete.")
            .build();
    }

    @Override
    protected PlatformOwnershipDTO defaultOwnership(Locale locale) {
        return PlatformOwnershipDTO.builder()
            .ownerTeam("Supply Chain Guild")
            .ownerContactEmail("inventory-ops@example.com")
            .serviceLevel("Business hours")
            .build();
    }

    @Override
    protected String defaultDocumentationUrl(Locale locale) {
        return "https://docs.internal/platform/inventory";
    }

    @Override
    protected String defaultFeatureFlag() {
        return "ff.platform.inventory";
    }
}
