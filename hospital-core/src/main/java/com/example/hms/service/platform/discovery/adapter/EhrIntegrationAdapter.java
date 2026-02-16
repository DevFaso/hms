package com.example.hms.service.platform.discovery.adapter;

import com.example.hms.config.PlatformIntegrationProperties;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.payload.dto.PlatformOwnershipDTO;
import com.example.hms.payload.dto.PlatformServiceMetadataDTO;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class EhrIntegrationAdapter extends AbstractToggleableIntegrationAdapter {

    public EhrIntegrationAdapter(PlatformIntegrationProperties properties) {
        super(properties);
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
        return "EHR Core Interop";
    }

    @Override
    protected String defaultDescription(Locale locale) {
        return "FHIR R4 sandbox connector for clinical charting and patient demographics.";
    }

    @Override
    protected String defaultProvider(Locale locale) {
        return "FHIR Reference Sandbox";
    }

    @Override
    protected List<String> defaultCapabilities(Locale locale) {
        return List.of(
            "FHIR R4 patient read/write",
            "HL7 v2 ADT event mirroring",
            "Clinical document exchange stubs"
        );
    }

    @Override
    protected PlatformServiceMetadataDTO defaultMetadata(Locale locale) {
        return PlatformServiceMetadataDTO.builder()
            .ehrSystem("Stub EHR Sandbox")
            .integrationNotes("Synthetic dataset with consent-safe fixtures for testing.")
            .build();
    }

    @Override
    protected PlatformOwnershipDTO defaultOwnership(Locale locale) {
        return PlatformOwnershipDTO.builder()
            .ownerTeam("Platform EHR Team")
            .ownerContactEmail("ehr-ops@example.com")
            .dataSteward("Clinical Informatics")
            .serviceLevel("24x7")
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
