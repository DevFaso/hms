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
public class EhrIntegrationAdapter extends AbstractToggleableIntegrationAdapter {

    public EhrIntegrationAdapter(PlatformIntegrationProperties properties, MessageSource messageSource) {
        super(properties, messageSource);
    }

    @Override
    public PlatformServiceType getServiceType() {
        return PlatformServiceType.EHR;
    }

    @Override
    protected boolean defaultAutoProvision() {
        return true;
    }

    @Override
    protected String defaultDisplayName(Locale locale) {
        return text("platform.catalog.ehr.displayName", locale);
    }

    @Override
    protected String defaultDescription(Locale locale) {
        return text("platform.catalog.ehr.description", locale);
    }

    @Override
    protected String defaultProvider(Locale locale) {
        return "FHIR Reference Sandbox";
    }

    @Override
    protected List<String> defaultCapabilities(Locale locale) {
        return List.of(
            text("platform.catalog.ehr.capability.1", locale),
            text("platform.catalog.ehr.capability.2", locale),
            text("platform.catalog.ehr.capability.3", locale)
        );
    }

    @Override
    protected PlatformServiceMetadataDTO defaultMetadata(Locale locale) {
        return PlatformServiceMetadataDTO.builder()
            .ehrSystem("Stub EHR Sandbox")
            .integrationNotes(text("platform.catalog.ehr.integrationNotes", locale))
            .build();
    }

    @Override
    protected PlatformOwnershipDTO defaultOwnership(Locale locale) {
        return PlatformOwnershipDTO.builder()
            .ownerTeam("Platform EHR Team")
            .ownerContactEmail("ehr-ops@example.com")
            .dataSteward("Clinical Informatics")
            .serviceLevel(text("platform.catalog.ehr.serviceLevel", locale))
            .build();
    }

    @Override
    protected String defaultBaseUrl(Locale locale) {
        return "https://ehr-sandbox.local/api";
    }

    @Override
    protected String defaultDocumentationUrl(Locale locale) {
        return "https://docs.internal/platform/ehr";
    }

    @Override
    protected String defaultSandboxUrl(Locale locale) {
        return "https://ehr-sandbox.local/portal";
    }

    @Override
    protected String defaultFeatureFlag() {
        return "ff.platform.ehr";
    }
}
