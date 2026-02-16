package com.example.hms.service.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.exception.ConflictException;
import com.example.hms.payload.dto.PlatformOwnershipDTO;
import com.example.hms.payload.dto.PlatformServiceMetadataDTO;
import com.example.hms.payload.dto.PlatformServiceRegistrationRequestDTO;
import com.example.hms.service.platform.discovery.IntegrationDescriptor;
import com.example.hms.service.platform.discovery.PlatformServiceRegistry;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentMatchers;

@ExtendWith(MockitoExtension.class)
class OrganizationPlatformBootstrapServiceTest {

    private static final UUID ORG_ID = UUID.randomUUID();

    @Mock
    private PlatformServiceRegistry platformServiceRegistry;

    @Mock
    private PlatformRegistryService platformRegistryService;

    private OrganizationPlatformBootstrapService bootstrapService;

    @BeforeEach
    void setUp() {
        bootstrapService = new OrganizationPlatformBootstrapService(platformServiceRegistry, platformRegistryService);
    }

    @Test
    void shouldRegisterAutoProvisionedIntegrations() {
        IntegrationDescriptor ehr = descriptorBuilder()
            .serviceType(PlatformServiceType.EHR)
            .enabled(true)
            .autoProvision(true)
            .managedByPlatform(true)
            .provider("FHIR Reference Sandbox")
            .baseUrl("https://ehr-sandbox.local/api")
            .defaultMetadata(PlatformServiceMetadataDTO.builder().ehrSystem("Stub").build())
            .defaultOwnership(PlatformOwnershipDTO.builder().ownerTeam("Platform").build())
            .build();

        IntegrationDescriptor billing = descriptorBuilder()
            .serviceType(PlatformServiceType.BILLING)
            .enabled(true)
            .autoProvision(false)
            .build();

        when(platformServiceRegistry.listIntegrations(true, Locale.US)).thenReturn(List.of(ehr, billing));

        bootstrapService.bootstrapDefaultIntegrations(ORG_ID, Locale.US);

        ArgumentCaptor<PlatformServiceRegistrationRequestDTO> captor = ArgumentCaptor.forClass(PlatformServiceRegistrationRequestDTO.class);
        verify(platformRegistryService).registerOrganizationService(ArgumentMatchers.eq(ORG_ID), captor.capture(), ArgumentMatchers.eq(Locale.US));

        PlatformServiceRegistrationRequestDTO request = captor.getValue();
        verifyNoMoreInteractions(platformRegistryService);

        assertThat(request.getServiceType()).isEqualTo(PlatformServiceType.EHR);
        assertThat(request.getManagedByPlatform()).isTrue();
        assertThat(request.getProvider()).isEqualTo("FHIR Reference Sandbox");
        verify(platformServiceRegistry).listIntegrations(true, Locale.US);
    }

    @Test
    void shouldIgnoreConflictsWhenIntegrationAlreadyExists() {
        IntegrationDescriptor descriptor = descriptorBuilder()
            .serviceType(PlatformServiceType.EHR)
            .enabled(true)
            .autoProvision(true)
            .build();

        when(platformServiceRegistry.listIntegrations(true, Locale.US)).thenReturn(List.of(descriptor));
        doThrow(new ConflictException("duplicate"))
            .when(platformRegistryService)
            .registerOrganizationService(ArgumentMatchers.eq(ORG_ID), ArgumentMatchers.any(), ArgumentMatchers.eq(Locale.US));

        assertThatNoException().isThrownBy(() -> bootstrapService.bootstrapDefaultIntegrations(ORG_ID, Locale.US));

        verify(platformServiceRegistry).listIntegrations(true, Locale.US);
        verify(platformRegistryService).registerOrganizationService(ArgumentMatchers.eq(ORG_ID), ArgumentMatchers.any(), ArgumentMatchers.eq(Locale.US));
    }

    @Test
    void shouldSkipDisabledIntegrations() {
        IntegrationDescriptor descriptor = descriptorBuilder()
            .serviceType(PlatformServiceType.INVENTORY)
            .enabled(false)
            .autoProvision(true)
            .build();

        when(platformServiceRegistry.listIntegrations(true, Locale.getDefault())).thenReturn(List.of(descriptor));

        bootstrapService.bootstrapDefaultIntegrations(ORG_ID, null);

        verify(platformServiceRegistry).listIntegrations(true, Locale.getDefault());
        verifyNoInteractions(platformRegistryService);
    }

    private IntegrationDescriptor.IntegrationDescriptorBuilder descriptorBuilder() {
        return IntegrationDescriptor.builder()
            .id("stub")
            .managedByPlatform(false)
            .enabled(true)
            .autoProvision(false);
    }
}
