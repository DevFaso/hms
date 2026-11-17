package com.example.hms.mapper;

import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.model.embedded.PlatformOwnership;
import com.example.hms.model.embedded.PlatformServiceMetadata;
import com.example.hms.model.platform.OrganizationPlatformService;
import com.example.hms.payload.dto.PlatformOwnershipDTO;
import com.example.hms.payload.dto.PlatformServiceMetadataDTO;
import com.example.hms.payload.dto.PlatformServiceRegistrationRequestDTO;
import com.example.hms.payload.dto.PlatformServiceUpdateRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The partial-update contract of {@link PlatformServiceMapper} (D4/D5):
 * null leaves a field alone, blank clears it, anything else replaces it —
 * field by field inside ownership and metadata too.
 */
class PlatformServiceMapperTest {

    private final PlatformServiceMapper mapper = new PlatformServiceMapper();

    private OrganizationPlatformService entity;

    @BeforeEach
    void setUp() {
        entity = OrganizationPlatformService.builder()
            .serviceType(PlatformServiceType.EHR)
            .status(PlatformServiceStatus.ACTIVE)
            .provider("OpenMRS")
            .baseUrl("https://emr.example.org")
            .documentationUrl("https://docs.example.org")
            .apiKeyReference("vault://emr")
            .managedByPlatform(true)
            .ownership(PlatformOwnership.builder()
                .ownerTeam("Interop")
                .ownerContactEmail("interop@example.org")
                .dataSteward("Records")
                .serviceLevel("24x7")
                .build())
            .metadata(PlatformServiceMetadata.builder()
                .ehrSystem("OpenMRS 3")
                .billingSystem("Odoo")
                .inventorySystem("mSupply")
                .integrationNotes("old notes")
                .build())
            .build();
    }

    @Test
    @DisplayName("D4: metadata naming only the notes keeps the three system fields")
    void metadataIsMergedFieldByField() {
        mapper.updateOrganizationServiceFromDto(PlatformServiceUpdateRequestDTO.builder()
            .metadata(PlatformServiceMetadataDTO.builder().integrationNotes("new notes").build())
            .build(), entity);

        assertThat(entity.getMetadata().getEhrSystem()).isEqualTo("OpenMRS 3");
        assertThat(entity.getMetadata().getBillingSystem()).isEqualTo("Odoo");
        assertThat(entity.getMetadata().getInventorySystem()).isEqualTo("mSupply");
        assertThat(entity.getMetadata().getIntegrationNotes()).isEqualTo("new notes");
    }

    @Test
    @DisplayName("D4: ownership is merged field by field as well")
    void ownershipIsMergedFieldByField() {
        mapper.updateOrganizationServiceFromDto(PlatformServiceUpdateRequestDTO.builder()
            .ownership(PlatformOwnershipDTO.builder().ownerTeam("Platform").build())
            .build(), entity);

        assertThat(entity.getOwnership().getOwnerTeam()).isEqualTo("Platform");
        assertThat(entity.getOwnership().getOwnerContactEmail()).isEqualTo("interop@example.org");
        assertThat(entity.getOwnership().getDataSteward()).isEqualTo("Records");
        assertThat(entity.getOwnership().getServiceLevel()).isEqualTo("24x7");
    }

    @Test
    @DisplayName("D5: a blank string clears a field; null leaves it")
    void blankClearsNullKeeps() {
        mapper.updateOrganizationServiceFromDto(PlatformServiceUpdateRequestDTO.builder()
            .provider("  ")
            .baseUrl("")
            .ownership(PlatformOwnershipDTO.builder().dataSteward("").build())
            .metadata(PlatformServiceMetadataDTO.builder().integrationNotes(" ").billingSystem(null).build())
            .build(), entity);

        assertThat(entity.getProvider()).isNull();
        assertThat(entity.getBaseUrl()).isNull();
        assertThat(entity.getDocumentationUrl()).isEqualTo("https://docs.example.org");
        assertThat(entity.getOwnership().getDataSteward()).isNull();
        assertThat(entity.getOwnership().getOwnerTeam()).isEqualTo("Interop");
        assertThat(entity.getMetadata().getIntegrationNotes()).isNull();
        assertThat(entity.getMetadata().getBillingSystem()).isEqualTo("Odoo");
    }

    @Test
    @DisplayName("D5: a new value is trimmed")
    void valuesAreTrimmed() {
        mapper.updateOrganizationServiceFromDto(PlatformServiceUpdateRequestDTO.builder()
            .provider("  Bahmni ")
            .build(), entity);

        assertThat(entity.getProvider()).isEqualTo("Bahmni");
    }

    @Test
    @DisplayName("D5: the API key — blank keeps it, a value replaces it, the flag clears it")
    void apiKeyReferenceSemantics() {
        mapper.updateOrganizationServiceFromDto(PlatformServiceUpdateRequestDTO.builder()
            .apiKeyReference("")
            .build(), entity);
        assertThat(entity.getApiKeyReference()).isEqualTo("vault://emr");

        mapper.updateOrganizationServiceFromDto(PlatformServiceUpdateRequestDTO.builder()
            .apiKeyReference(" vault://rotated ")
            .build(), entity);
        assertThat(entity.getApiKeyReference()).isEqualTo("vault://rotated");

        mapper.updateOrganizationServiceFromDto(PlatformServiceUpdateRequestDTO.builder()
            .clearApiKeyReference(true)
            .build(), entity);
        assertThat(entity.getApiKeyReference()).isNull();
    }

    @Test
    @DisplayName("an entity whose embedded values loaded as null still merges")
    void mergesIntoNullEmbeddables() {
        entity.setOwnership(null);
        entity.setMetadata(null);

        mapper.updateOrganizationServiceFromDto(PlatformServiceUpdateRequestDTO.builder()
            .ownership(PlatformOwnershipDTO.builder().ownerTeam("Ops").build())
            .metadata(PlatformServiceMetadataDTO.builder().ehrSystem("OpenMRS").build())
            .build(), entity);

        assertThat(entity.getOwnership().getOwnerTeam()).isEqualTo("Ops");
        assertThat(entity.getMetadata().getEhrSystem()).isEqualTo("OpenMRS");
        assertThat(entity.getMetadata().getIntegrationNotes()).isNull();
    }

    @Test
    @DisplayName("status and managed flag are applied only when sent")
    void statusAndManagedFlag() {
        mapper.updateOrganizationServiceFromDto(PlatformServiceUpdateRequestDTO.builder()
            .status(PlatformServiceStatus.PILOT)
            .build(), entity);

        assertThat(entity.getStatus()).isEqualTo(PlatformServiceStatus.PILOT);
        assertThat(entity.isManagedByPlatform()).isTrue();
        assertThat(entity.getProvider()).isEqualTo("OpenMRS");
    }

    @Test
    @DisplayName("registration never stores an empty string")
    void registrationStoresNullForBlank() {
        OrganizationPlatformService created = mapper.toOrganizationPlatformService(
            PlatformServiceRegistrationRequestDTO.builder()
                .serviceType(PlatformServiceType.BILLING)
                .provider(" ")
                .metadata(PlatformServiceMetadataDTO.builder().integrationNotes("").billingSystem(" Odoo ").build())
                .build(),
            null);

        assertThat(created.getProvider()).isNull();
        assertThat(created.getMetadata().getIntegrationNotes()).isNull();
        assertThat(created.getMetadata().getBillingSystem()).isEqualTo("Odoo");
    }
}
