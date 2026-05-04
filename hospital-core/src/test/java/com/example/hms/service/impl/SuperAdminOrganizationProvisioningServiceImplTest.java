package com.example.hms.service.impl;

import com.example.hms.enums.OrganizationRegion;
import com.example.hms.enums.OrganizationType;
import com.example.hms.exception.RemoteProvisioningNotConfiguredException;
import com.example.hms.mapper.OrganizationMapper;
import com.example.hms.model.Organization;
import com.example.hms.payload.dto.OrganizationResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminCreateOrganizationRequestDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.service.OrganizationSecurityService;
import com.example.hms.service.RegionRoutingResolver;
import com.example.hms.service.platform.OrganizationPlatformBootstrapService;
import com.example.hms.service.provisioning.TenantProvisioningClient;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SuperAdminOrganizationProvisioningServiceImplTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private OrganizationSecurityService organizationSecurityService;

    @Mock
    private OrganizationPlatformBootstrapService organizationPlatformBootstrapService;

    @Mock
    private OrganizationMapper organizationMapper;

    @Mock
    private RegionRoutingResolver regionRoutingResolver;

    @Mock
    private TenantProvisioningClient tenantProvisioningClient;

    @Captor
    private ArgumentCaptor<Organization> organizationCaptor;

    private SuperAdminOrganizationProvisioningServiceImpl service;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.CANADA);
        service = new SuperAdminOrganizationProvisioningServiceImpl(
            organizationRepository,
            organizationSecurityService,
            organizationPlatformBootstrapService,
            organizationMapper,
            regionRoutingResolver,
            tenantProvisioningClient
        );
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void createOrganization_whenCodeAlreadyExists_throwsIllegalArgumentException() {
        SuperAdminCreateOrganizationRequestDTO request = SuperAdminCreateOrganizationRequestDTO.builder()
            .name("Acme Health")
            .code("ACME")
            .timezone("America/Toronto")
            .contactEmail("ops@acmehealth.io")
            .build();

        when(organizationRepository.existsByCode("ACME")).thenReturn(true);

        assertThatThrownBy(() -> service.createOrganization(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already exists");

        verify(organizationRepository, never()).save(any(Organization.class));
        verifyNoInteractions(organizationSecurityService, organizationPlatformBootstrapService, organizationMapper);
    }

    @Test
    void createOrganization_persistsOrganizationAndBootstrapsDependencies() {
        SuperAdminCreateOrganizationRequestDTO request = SuperAdminCreateOrganizationRequestDTO.builder()
            .name("Beacon Health Network")
            .code("bh-north")
            .timezone("America/New_York")
            .contactEmail("contact@beaconhealth.org")
            .contactPhone("  +1-555-0100  ")
            .notes("Primary onboarding cohort")
            .build();

        UUID generatedId = UUID.randomUUID();
        AtomicReference<Organization> savedReference = new AtomicReference<>();

        when(organizationRepository.existsByCode("BH-NORTH")).thenReturn(false);
        when(organizationRepository.save(any(Organization.class))).thenAnswer(invocation -> {
            Organization org = invocation.getArgument(0);
            org.setId(generatedId);
            savedReference.set(org);
            return org;
        });
        when(organizationRepository.findByIdWithHospitals(generatedId))
            .thenAnswer(invocation -> Optional.ofNullable(savedReference.get()));
        when(organizationMapper.toResponseDTO(any(Organization.class))).thenAnswer(invocation -> {
            Organization org = invocation.getArgument(0);
            return OrganizationResponseDTO.builder()
                .id(org.getId())
                .code(org.getCode())
                .name(org.getName())
                .primaryContactEmail(org.getPrimaryContactEmail())
                .primaryContactPhone(org.getPrimaryContactPhone())
                .defaultTimezone(org.getDefaultTimezone())
                .onboardingNotes(org.getOnboardingNotes())
                .type(org.getType())
                .build();
        });

        OrganizationResponseDTO response = service.createOrganization(request);

        verify(organizationRepository).existsByCode("BH-NORTH");
        verify(organizationRepository).save(organizationCaptor.capture());
        verify(organizationSecurityService).applyDefaultSecurityPolicies(generatedId, OrganizationType.HEALTHCARE_NETWORK);
        verify(organizationPlatformBootstrapService).bootstrapDefaultIntegrations(generatedId, Locale.CANADA);
        verify(organizationMapper).toResponseDTO(savedReference.get());

        Organization persisted = organizationCaptor.getValue();
        assertThat(persisted.getCode()).isEqualTo("BH-NORTH");
        assertThat(persisted.getPrimaryContactEmail()).isEqualTo("contact@beaconhealth.org");
        assertThat(persisted.getPrimaryContactPhone()).isEqualTo("+1-555-0100");
        assertThat(persisted.getDefaultTimezone()).isEqualTo("America/New_York");
        assertThat(persisted.getOnboardingNotes()).isEqualTo("Primary onboarding cohort");
        assertThat(persisted.getOwnership()).isNotNull();
        assertThat(persisted.getOwnership().getOwnerContactEmail()).isEqualTo("contact@beaconhealth.org");
        assertThat(persisted.getType()).isEqualTo(OrganizationType.HEALTHCARE_NETWORK);

        assertThat(response.getId()).isEqualTo(generatedId);
        assertThat(response.getCode()).isEqualTo("BH-NORTH");
        assertThat(response.getPrimaryContactEmail()).isEqualTo("contact@beaconhealth.org");
        assertThat(response.getPrimaryContactPhone()).isEqualTo("+1-555-0100");
        assertThat(response.getDefaultTimezone()).isEqualTo("America/New_York");
        assertThat(response.getOnboardingNotes()).isEqualTo("Primary onboarding cohort");
        assertThat(response.getType()).isEqualTo(OrganizationType.HEALTHCARE_NETWORK);
    }

    @Test
    void createOrganization_whenContactPhoneBlank_setsNullPhoneNumber() {
        SuperAdminCreateOrganizationRequestDTO request = SuperAdminCreateOrganizationRequestDTO.builder()
            .name("Orion Medical Group")
            .code("orion")
            .timezone("America/Toronto")
            .contactEmail("ops@orion.example")
            .contactPhone("   ")
            .notes("Bootstrap for pilot program")
            .type(OrganizationType.PRIVATE_PRACTICE)
            .build();

        UUID generatedId = UUID.randomUUID();

        when(organizationRepository.existsByCode("ORION")).thenReturn(false);
        when(organizationRepository.save(any(Organization.class))).thenAnswer(invocation -> {
            Organization org = invocation.getArgument(0);
            org.setId(generatedId);
            return org;
        });
        when(organizationRepository.findByIdWithHospitals(generatedId)).thenReturn(Optional.empty());
        when(organizationMapper.toResponseDTO(any(Organization.class))).thenAnswer(invocation -> {
            Organization org = invocation.getArgument(0);
            return OrganizationResponseDTO.builder()
                .id(org.getId())
                .code(org.getCode())
                .name(org.getName())
                .primaryContactPhone(org.getPrimaryContactPhone())
                .type(org.getType())
                .build();
        });

        OrganizationResponseDTO response = service.createOrganization(request);

        verify(organizationRepository).save(organizationCaptor.capture());
        Organization persisted = organizationCaptor.getValue();
        assertThat(persisted.getPrimaryContactPhone()).isNull();
        assertThat(persisted.getType()).isEqualTo(OrganizationType.PRIVATE_PRACTICE);

        assertThat(response.getPrimaryContactPhone()).isNull();
    }

    // ── MVP-9c routing scaffold ─────────────────────────────────────────

    @Test
    void createOrganization_whenRegionHasTargetDeploymentUrl_delegatesToProvisioningClient() {
        SuperAdminCreateOrganizationRequestDTO request = SuperAdminCreateOrganizationRequestDTO.builder()
            .name("EU Tenant")
            .code("eu-tenant")
            .timezone("Europe/Paris")
            .contactEmail("ops@eu-tenant.example")
            .region(OrganizationRegion.EU)
            .build();

        OrganizationResponseDTO remoteResponse = OrganizationResponseDTO.builder()
            .id(UUID.randomUUID())
            .code("EU-TENANT")
            .name("EU Tenant")
            .build();

        when(organizationRepository.existsByCode("EU-TENANT")).thenReturn(false);
        when(regionRoutingResolver.resolveDeploymentUrl(OrganizationRegion.EU))
            .thenReturn(Optional.of("https://eu.hms.example/api"));
        when(tenantProvisioningClient.provisionRemote(any(), eq("https://eu.hms.example/api")))
            .thenReturn(remoteResponse);

        OrganizationResponseDTO response = service.createOrganization(request);

        // Local-side state must be untouched: no save, no security policies,
        // no platform bootstrap, no mapper invocation. The remote response
        // is returned verbatim.
        assertThat(response).isSameAs(remoteResponse);
        verify(organizationRepository, never()).save(any(Organization.class));
        verifyNoInteractions(organizationSecurityService, organizationPlatformBootstrapService, organizationMapper);
    }

    @Test
    void createOrganization_whenStubClientRejectsRemote_propagates501() {
        SuperAdminCreateOrganizationRequestDTO request = SuperAdminCreateOrganizationRequestDTO.builder()
            .name("EU Tenant")
            .code("eu-tenant")
            .timezone("Europe/Paris")
            .contactEmail("ops@eu-tenant.example")
            .region(OrganizationRegion.EU)
            .build();

        when(organizationRepository.existsByCode("EU-TENANT")).thenReturn(false);
        when(regionRoutingResolver.resolveDeploymentUrl(OrganizationRegion.EU))
            .thenReturn(Optional.of("https://eu.hms.example/api"));
        when(tenantProvisioningClient.provisionRemote(any(), eq("https://eu.hms.example/api")))
            .thenThrow(new RemoteProvisioningNotConfiguredException(
                "Region routing is configured but no remote client is wired"));

        assertThatThrownBy(() -> service.createOrganization(request))
            .isInstanceOf(RemoteProvisioningNotConfiguredException.class)
            .hasMessageContaining("no remote client is wired");

        // Local provisioning side-effects must not have run.
        verify(organizationRepository, never()).save(any(Organization.class));
        verifyNoInteractions(organizationSecurityService, organizationPlatformBootstrapService, organizationMapper);
    }
}
