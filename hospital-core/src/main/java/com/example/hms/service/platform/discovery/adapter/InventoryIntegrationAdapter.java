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
public class InventoryIntegrationAdapter extends AbstractToggleableIntegrationAdapter {

    public InventoryIntegrationAdapter(PlatformIntegrationProperties properties, MessageSource messageSource) {
        super(properties, messageSource);
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
        return text("platform.catalog.inventory.displayName", locale);
    }

    @Override
    protected String defaultDescription(Locale locale) {
        return text("platform.catalog.inventory.description", locale);
    }

    @Override
    protected String defaultProvider(Locale locale) {
        return "InventoryBridge Stub";
    }

    @Override
    protected List<String> defaultCapabilities(Locale locale) {
        return List.of(
            text("platform.catalog.inventory.capability.1", locale),
            text("platform.catalog.inventory.capability.2", locale),
            text("platform.catalog.inventory.capability.3", locale)
        );
    }

    @Override
    protected PlatformServiceMetadataDTO defaultMetadata(Locale locale) {
        return PlatformServiceMetadataDTO.builder()
            .inventorySystem("InventoryBridge Stub")
            .integrationNotes(text("platform.catalog.inventory.integrationNotes", locale))
            .build();
    }

    @Override
    protected PlatformOwnershipDTO defaultOwnership(Locale locale) {
        return PlatformOwnershipDTO.builder()
            .ownerTeam("Supply Chain Guild")
            .ownerContactEmail("inventory-ops@example.com")
            .serviceLevel(text("platform.catalog.inventory.serviceLevel", locale))
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
