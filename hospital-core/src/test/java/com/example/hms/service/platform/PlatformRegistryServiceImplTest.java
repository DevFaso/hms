package com.example.hms.service.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.platform.PlatformRegistryEventType;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.embedded.PlatformServiceMetadata;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.HospitalPlatformServiceLinkResponseDTO;
import com.example.hms.payload.dto.PlatformServiceLinkUpdateRequestDTO;
import com.example.hms.payload.dto.PlatformServiceMetadataDTO;
import com.example.hms.payload.event.PlatformServiceEventPayload;
import com.example.hms.service.platform.discovery.IntegrationDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ConflictException;
import com.example.hms.mapper.PlatformServiceMapper;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.platform.DepartmentPlatformServiceLink;
import com.example.hms.model.platform.HospitalPlatformServiceLink;
import com.example.hms.model.platform.OrganizationPlatformService;
import com.example.hms.payload.dto.PlatformOwnershipDTO;
import com.example.hms.payload.dto.PlatformServiceLinkRequestDTO;
import com.example.hms.payload.dto.PlatformServiceRegistrationRequestDTO;
import com.example.hms.payload.dto.PlatformServiceResponseDTO;
import com.example.hms.payload.dto.PlatformServiceUpdateRequestDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.platform.DepartmentPlatformServiceLinkRepository;
import com.example.hms.repository.platform.HospitalPlatformServiceLinkRepository;
import com.example.hms.repository.platform.OrganizationPlatformServiceRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.platform.discovery.PlatformServiceRegistry;
import com.example.hms.service.platform.event.PlatformRegistryEventPublisher;
import com.example.hms.utility.MessageUtil;
import com.example.hms.service.platform.impl.PlatformRegistryServiceImpl;
import com.example.hms.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlatformRegistryServiceImplTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private OrganizationPlatformServiceRepository organizationPlatformServiceRepository;

    @Mock
    private HospitalRepository hospitalRepository;

    @Mock
    private HospitalPlatformServiceLinkRepository hospitalPlatformServiceLinkRepository;

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private DepartmentPlatformServiceLinkRepository departmentPlatformServiceLinkRepository;

    @Mock
    private PlatformRegistryEventPublisher eventPublisher;

    @Mock
    private PlatformServiceRegistry platformServiceRegistry;

    @Mock
    private AuditEventLogService auditEventLogService;

    private PlatformRegistryServiceImpl platformRegistryService;

    private final PlatformServiceMapper mapper = new PlatformServiceMapper();

    @BeforeEach
    void setUp() {
        platformRegistryService = new PlatformRegistryServiceImpl(
            organizationRepository,
            organizationPlatformServiceRepository,
            hospitalRepository,
            hospitalPlatformServiceLinkRepository,
            departmentRepository,
            departmentPlatformServiceLinkRepository,
            mapper,
            eventPublisher,
            platformServiceRegistry,
            auditEventLogService
        );
        MessageUtil.setMessageSource(TestMessageSources.bundles());
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @Test
    void registerOrganizationServicePersistsNewRecord() {
        UUID organizationId = UUID.randomUUID();
        Organization organization = Organization.builder().build();
        organization.setId(organizationId);

        PlatformServiceRegistrationRequestDTO request = PlatformServiceRegistrationRequestDTO.builder()
            .serviceType(PlatformServiceType.EHR)
            .provider("Acme")
            .build();

        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
        when(organizationPlatformServiceRepository.existsByOrganizationIdAndServiceType(organizationId, PlatformServiceType.EHR))
            .thenReturn(false);
        when(organizationPlatformServiceRepository.save(any(OrganizationPlatformService.class)))
            .thenAnswer(invocation -> {
                OrganizationPlatformService entity = invocation.getArgument(0);
                entity.setId(UUID.randomUUID());
                return entity;
            });

        PlatformServiceResponseDTO response = platformRegistryService
            .registerOrganizationService(organizationId, request, Locale.ENGLISH);

        assertThat(response).isNotNull();
        assertThat(response.getOrganizationId()).isEqualTo(organizationId);
        assertThat(response.getServiceType()).isEqualTo(PlatformServiceType.EHR);
        assertThat(response.getStatus()).isEqualTo(PlatformServiceStatus.PENDING);
        assertThat(response.isManagedByPlatform()).isTrue();

        ArgumentCaptor<OrganizationPlatformService> captor = ArgumentCaptor.forClass(OrganizationPlatformService.class);
        verify(organizationPlatformServiceRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganization()).isEqualTo(organization);

    verify(eventPublisher).publish(any());
    }

    @Test
    void registerOrganizationServiceWhenOrganizationMissingThrowsResourceNotFound() {
        UUID organizationId = UUID.randomUUID();
        PlatformServiceRegistrationRequestDTO request = PlatformServiceRegistrationRequestDTO.builder()
            .serviceType(PlatformServiceType.EHR)
            .build();

        when(organizationRepository.findById(organizationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> platformRegistryService
            .registerOrganizationService(organizationId, request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining(organizationId.toString());
    }

    @Test
    void registerOrganizationServicePublishesEventEvenWhenPublisherFails() {
        UUID organizationId = UUID.randomUUID();
        Organization organization = Organization.builder().build();
        organization.setId(organizationId);

        PlatformServiceRegistrationRequestDTO request = PlatformServiceRegistrationRequestDTO.builder()
            .serviceType(PlatformServiceType.LIMS)
            .provider("Acme")
            .build();

        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .principalUsername("nurse@example.com")
            .build());

        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
        when(organizationPlatformServiceRepository.existsByOrganizationIdAndServiceType(organizationId, PlatformServiceType.LIMS))
            .thenReturn(false);
        when(organizationPlatformServiceRepository.save(any(OrganizationPlatformService.class)))
            .thenAnswer(invocation -> {
                OrganizationPlatformService entity = invocation.getArgument(0);
                entity.setId(UUID.randomUUID());
                return entity;
            });
        doThrow(new RuntimeException("Kafka offline"))
            .when(eventPublisher).publish(any());

        PlatformServiceResponseDTO response = platformRegistryService
            .registerOrganizationService(organizationId, request, Locale.ENGLISH);

        assertThat(response.getOrganizationId()).isEqualTo(organizationId);
        verify(eventPublisher).publish(any());
    }

    @Test
    void registerOrganizationServiceDuplicateTypeThrowsConflict() {
        UUID organizationId = UUID.randomUUID();
        PlatformServiceRegistrationRequestDTO request = PlatformServiceRegistrationRequestDTO.builder()
            .serviceType(PlatformServiceType.BILLING)
            .build();

        Organization organization = Organization.builder().build();
        organization.setId(organizationId);

        when(organizationRepository.findById(organizationId))
            .thenReturn(Optional.of(organization));
        when(organizationPlatformServiceRepository.existsByOrganizationIdAndServiceType(organizationId, PlatformServiceType.BILLING))
            .thenReturn(true);

        assertThatThrownBy(() -> platformRegistryService
            .registerOrganizationService(organizationId, request, Locale.ENGLISH))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already has a BILLING service");
    }

    @Test
    void linkHospitalToServiceWithDifferentOrganizationThrowsBusinessRuleException() {
        UUID hospitalId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        Organization orgA = Organization.builder().build();
        orgA.setId(UUID.randomUUID());
        Organization orgB = Organization.builder().build();
        orgB.setId(UUID.randomUUID());

        Hospital hospital = Hospital.builder().organization(orgA).build();
        hospital.setId(hospitalId);

        OrganizationPlatformService service = OrganizationPlatformService.builder()
            .organization(orgB)
            .serviceType(PlatformServiceType.ANALYTICS)
            .status(PlatformServiceStatus.ACTIVE)
            .build();
        service.setId(serviceId);

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(organizationPlatformServiceRepository.findById(serviceId)).thenReturn(Optional.of(service));

        assertThatThrownBy(() -> platformRegistryService
            .linkHospitalToService(hospitalId, serviceId, null, Locale.ENGLISH))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("hospital must belong to the same organization");
    }

    @Test
    void linkHospitalToServiceWhenHospitalMissingThrowsResourceNotFound() {
        UUID hospitalId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)


            .isThrownBy(() -> platformRegistryService.linkHospitalToService(hospitalId, serviceId, null, Locale.ENGLISH))


            .satisfies(e -> assertThat(e.getMessageKey()).isEqualTo("hospital.notFound"));
    }

    @Test
    void linkHospitalToServiceWhenServiceMissingThrowsResourceNotFound() {
        UUID organizationId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        Organization organization = Organization.builder().build();
        organization.setId(organizationId);

        Hospital hospital = Hospital.builder().organization(organization).name("Metro").build();
        hospital.setId(hospitalId);

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(organizationPlatformServiceRepository.findById(serviceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> platformRegistryService
            .linkHospitalToService(hospitalId, serviceId, null, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasFieldOrPropertyWithValue("messageKey", "platform.service.notFound");
    }

    @Test
    void linkHospitalToServiceWhenServiceHasNoOrganizationThrowsBusinessRuleException() {
        UUID hospitalId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        Organization organization = Organization.builder().build();
        organization.setId(UUID.randomUUID());

        Hospital hospital = Hospital.builder().organization(organization).name("Regional").build();
        hospital.setId(hospitalId);

        OrganizationPlatformService service = OrganizationPlatformService.builder()
            .serviceType(PlatformServiceType.ANALYTICS)
            .status(PlatformServiceStatus.ACTIVE)
            .build();
        service.setId(serviceId);

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(organizationPlatformServiceRepository.findById(serviceId)).thenReturn(Optional.of(service));

        assertThatThrownBy(() -> platformRegistryService
            .linkHospitalToService(hospitalId, serviceId, null, Locale.ENGLISH))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("has no organization");
    }

    @Test
    void linkHospitalToServicePersistsLink() {
        UUID organizationId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        Organization organization = Organization.builder().build();
        organization.setId(organizationId);

        Hospital hospital = Hospital.builder().organization(organization).name("Central").build();
        hospital.setId(hospitalId);

        OrganizationPlatformService service = OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(PlatformServiceType.INVENTORY)
            .status(PlatformServiceStatus.ACTIVE)
            .build();
        service.setId(serviceId);

        PlatformServiceLinkRequestDTO request = PlatformServiceLinkRequestDTO.builder()
            .ownership(PlatformOwnershipDTO.builder().ownerTeam("Ops").build())
            .credentialsReference("secret")
            .build();

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(organizationPlatformServiceRepository.findById(serviceId)).thenReturn(Optional.of(service));
        when(hospitalPlatformServiceLinkRepository.existsByHospitalIdAndOrganizationServiceId(hospitalId, serviceId))
            .thenReturn(false);
        when(hospitalPlatformServiceLinkRepository.save(any(HospitalPlatformServiceLink.class)))
            .thenAnswer(invocation -> {
                HospitalPlatformServiceLink link = invocation.getArgument(0);
                link.setId(UUID.randomUUID());
                return link;
            });

        var response = platformRegistryService
            .linkHospitalToService(hospitalId, serviceId, request, Locale.ENGLISH);

        assertThat(response.getHospitalId()).isEqualTo(hospitalId);
        assertThat(response.getOrganizationServiceId()).isEqualTo(serviceId);
        assertThat(response.getServiceType()).isEqualTo(PlatformServiceType.INVENTORY);
        assertThat(response.isEnabled()).isTrue();

        verify(hospitalPlatformServiceLinkRepository)
            .existsByHospitalIdAndOrganizationServiceId(hospitalId, serviceId);
        verify(hospitalPlatformServiceLinkRepository).save(any(HospitalPlatformServiceLink.class));
        verify(eventPublisher).publish(any());
    }

    @Test
    void linkDepartmentToServicePersistsLink() {
        UUID organizationId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        Organization organization = Organization.builder().build();
        organization.setId(organizationId);

        Hospital hospital = Hospital.builder().organization(organization).build();
        hospital.setId(hospitalId);

        Department department = Department.builder().hospital(hospital).name("Cardiology").build();
        department.setId(departmentId);

        OrganizationPlatformService service = OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(PlatformServiceType.BILLING)
            .status(PlatformServiceStatus.ACTIVE)
            .build();
        service.setId(serviceId);

        when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(department));
        when(organizationPlatformServiceRepository.findById(serviceId)).thenReturn(Optional.of(service));
        when(departmentPlatformServiceLinkRepository.existsByDepartmentIdAndOrganizationServiceId(departmentId, serviceId))
            .thenReturn(false);
        when(departmentPlatformServiceLinkRepository.save(any(DepartmentPlatformServiceLink.class)))
            .thenAnswer(invocation -> {
                DepartmentPlatformServiceLink link = invocation.getArgument(0);
                link.setId(UUID.randomUUID());
                return link;
            });

        var response = platformRegistryService
            .linkDepartmentToService(departmentId, serviceId, null, Locale.ENGLISH);

        assertThat(response.getDepartmentId()).isEqualTo(departmentId);
        assertThat(response.getOrganizationServiceId()).isEqualTo(serviceId);
        assertThat(response.getServiceType()).isEqualTo(PlatformServiceType.BILLING);
        assertThat(response.isEnabled()).isTrue();

    verify(eventPublisher, atLeastOnce()).publish(any());
    }

    @Test
    void linkDepartmentToServiceWhenDepartmentMissingThrowsResourceNotFound() {
        UUID departmentId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        when(departmentRepository.findById(departmentId)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)


            .isThrownBy(() -> platformRegistryService.linkDepartmentToService(departmentId, serviceId, null, Locale.ENGLISH))


            .satisfies(e -> assertThat(e.getMessageKey()).isEqualTo("department.notFound"));
    }

    @Test
    void linkDepartmentToServiceWhenServiceMissingThrowsResourceNotFound() {
        UUID organizationId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        Organization organization = Organization.builder().build();
        organization.setId(organizationId);

        Hospital hospital = Hospital.builder().organization(organization).build();
        hospital.setId(UUID.randomUUID());

        Department department = Department.builder().hospital(hospital).name("Imaging").build();
        department.setId(departmentId);

        when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(department));
        when(organizationPlatformServiceRepository.findById(serviceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> platformRegistryService
            .linkDepartmentToService(departmentId, serviceId, null, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasFieldOrPropertyWithValue("messageKey", "platform.service.notFound");
    }

    @Test
    void linkDepartmentToServiceWhenDepartmentMissingHospitalThrowsBusinessRuleException() {
        UUID departmentId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        Department department = Department.builder().hospital(null).build();
        department.setId(departmentId);

        Organization serviceOrganization = Organization.builder().build();
        serviceOrganization.setId(UUID.randomUUID());

        OrganizationPlatformService service = OrganizationPlatformService.builder()
            .organization(serviceOrganization)
            .serviceType(PlatformServiceType.EHR)
            .status(PlatformServiceStatus.ACTIVE)
            .build();
        service.setId(serviceId);

        when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(department));
        when(organizationPlatformServiceRepository.findById(serviceId)).thenReturn(Optional.of(service));

        assertThatThrownBy(() -> platformRegistryService
            .linkDepartmentToService(departmentId, serviceId, null, Locale.ENGLISH))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("not attached to a hospital");
    }

    @Test
    void listOrganizationServicesFiltersByStatus() {
        UUID organizationId = UUID.randomUUID();
        Organization organization = Organization.builder().build();
        organization.setId(organizationId);

        OrganizationPlatformService active = OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(PlatformServiceType.ANALYTICS)
            .status(PlatformServiceStatus.ACTIVE)
            .build();
        active.setId(UUID.randomUUID());

        when(organizationPlatformServiceRepository.findByOrganizationIdAndStatus(organizationId, PlatformServiceStatus.ACTIVE))
            .thenReturn(List.of(active));

        List<PlatformServiceResponseDTO> results = platformRegistryService
            .listOrganizationServices(organizationId, PlatformServiceStatus.ACTIVE, Locale.ENGLISH);

        assertThat(results).hasSize(1);
    assertThat(results.get(0).getServiceType()).isEqualTo(PlatformServiceType.ANALYTICS);
    }

    @Test
    void listOrganizationServicesWithoutStatusReturnsAllEntries() {
        UUID organizationId = UUID.randomUUID();
        Organization organization = Organization.builder().build();
        organization.setId(organizationId);

        OrganizationPlatformService pending = OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(PlatformServiceType.BILLING)
            .status(PlatformServiceStatus.PENDING)
            .build();
        pending.setId(UUID.randomUUID());

        when(organizationPlatformServiceRepository.findByOrganizationId(organizationId))
            .thenReturn(List.of(pending));

        List<PlatformServiceResponseDTO> results = platformRegistryService
            .listOrganizationServices(organizationId, null, Locale.ENGLISH);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStatus()).isEqualTo(PlatformServiceStatus.PENDING);
    }

    @Test
    void updateOrganizationServiceAppliesChangesAndPublishesEvent() {
        UUID organizationId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        Organization organization = Organization.builder().build();
        organization.setId(organizationId);

        OrganizationPlatformService service = OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(PlatformServiceType.LIMS)
            .status(PlatformServiceStatus.PENDING)
            .provider("Legacy")
            .managedByPlatform(true)
            .build();
        service.setId(serviceId);

        PlatformServiceUpdateRequestDTO request = PlatformServiceUpdateRequestDTO.builder()
            .status(PlatformServiceStatus.ACTIVE)
            .provider("  Modern Labs  ")
            .documentationUrl("https://docs")
            .build();

        when(organizationPlatformServiceRepository.findById(serviceId)).thenReturn(Optional.of(service));
        when(organizationPlatformServiceRepository.save(service)).thenReturn(service);

        PlatformServiceResponseDTO response = platformRegistryService
            .updateOrganizationService(organizationId, serviceId, request, Locale.ENGLISH);

        assertThat(response.getStatus()).isEqualTo(PlatformServiceStatus.ACTIVE);
        assertThat(response.getProvider()).isEqualTo("Modern Labs");
        assertThat(response.getDocumentationUrl()).isEqualTo("https://docs");
            verify(eventPublisher).publish(any());
    }

    @Test
    void getOrganizationServiceReturnsMappedResult() {
        UUID organizationId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        Organization organization = Organization.builder().build();
        organization.setId(organizationId);

        OrganizationPlatformService service = OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(PlatformServiceType.EHR)
            .status(PlatformServiceStatus.ACTIVE)
            .provider("CloudHealth")
            .build();
        service.setId(serviceId);

        when(organizationPlatformServiceRepository.findById(serviceId)).thenReturn(Optional.of(service));

        PlatformServiceResponseDTO response = platformRegistryService
            .getOrganizationService(organizationId, serviceId, Locale.ENGLISH);

        assertThat(response.getId()).isEqualTo(serviceId);
        assertThat(response.getOrganizationId()).isEqualTo(organizationId);
        assertThat(response.getProvider()).isEqualTo("CloudHealth");
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void getOrganizationServiceWithMismatchedOrganizationThrowsBusinessRuleException() {
        UUID organizationId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        Organization organization = Organization.builder().build();
        organization.setId(UUID.randomUUID());

        OrganizationPlatformService service = OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(PlatformServiceType.BILLING)
            .status(PlatformServiceStatus.ACTIVE)
            .build();
        service.setId(serviceId);

        when(organizationPlatformServiceRepository.findById(serviceId)).thenReturn(Optional.of(service));

        assertThatThrownBy(() -> platformRegistryService
            .getOrganizationService(organizationId, serviceId, Locale.ENGLISH))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("does not belong");
    }

    @Test
    void getOrganizationServiceWhenServiceNotFoundThrowsResourceNotFound() {
        UUID organizationId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        when(organizationPlatformServiceRepository.findById(serviceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> platformRegistryService
            .getOrganizationService(organizationId, serviceId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining(serviceId.toString());
    }

    @Test
    void getOrganizationServiceWhenServiceMissingOrganizationThrowsBusinessRuleException() {
        UUID organizationId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        OrganizationPlatformService service = OrganizationPlatformService.builder()
            .organization(null)
            .serviceType(PlatformServiceType.INVENTORY)
            .status(PlatformServiceStatus.ACTIVE)
            .build();
        service.setId(serviceId);

        when(organizationPlatformServiceRepository.findById(serviceId)).thenReturn(Optional.of(service));

        assertThatThrownBy(() -> platformRegistryService
            .getOrganizationService(organizationId, serviceId, Locale.ENGLISH))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("has no organization");
    }

    @Test
    void linkHospitalToServiceWhenAlreadyLinkedThrowsConflict() {
        UUID organizationId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        Organization organization = Organization.builder().build();
        organization.setId(organizationId);

        Hospital hospital = Hospital.builder().organization(organization).name("Metro").build();
        hospital.setId(hospitalId);

        OrganizationPlatformService service = OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(PlatformServiceType.ANALYTICS)
            .status(PlatformServiceStatus.ACTIVE)
            .build();
        service.setId(serviceId);

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(organizationPlatformServiceRepository.findById(serviceId)).thenReturn(Optional.of(service));
        when(hospitalPlatformServiceLinkRepository.existsByHospitalIdAndOrganizationServiceId(hospitalId, serviceId))
            .thenReturn(true);

        assertThatThrownBy(() -> platformRegistryService
            .linkHospitalToService(hospitalId, serviceId, null, Locale.ENGLISH))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already linked");
    }

    @Test
    void unlinkHospitalFromServiceRemovesLinkAndPublishesEvent() {
        UUID organizationId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        Organization organization = Organization.builder().build();
        organization.setId(organizationId);

        Hospital hospital = Hospital.builder().organization(organization).name("North").build();
        hospital.setId(hospitalId);

        OrganizationPlatformService service = OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(PlatformServiceType.LIMS)
            .status(PlatformServiceStatus.ACTIVE)
            .build();
        service.setId(serviceId);

        HospitalPlatformServiceLink link = HospitalPlatformServiceLink.builder()
            .hospital(hospital)
            .organizationService(service)
            .credentialsReference("cred")
            .build();
        link.setId(UUID.randomUUID());
        hospital.addPlatformServiceLink(link);
        service.addHospitalLink(link);

        when(hospitalPlatformServiceLinkRepository
            .findByHospitalIdAndOrganizationServiceId(hospitalId, serviceId))
            .thenReturn(Optional.of(link));

        platformRegistryService.unlinkHospitalFromService(hospitalId, serviceId, Locale.ENGLISH);

        verify(hospitalPlatformServiceLinkRepository).delete(link);
        verify(eventPublisher).publish(any());
        assertThat(hospital.getPlatformServiceLinks()).isEmpty();
        assertThat(service.getHospitalLinks()).isEmpty();
    }

    @Test
    void unlinkHospitalFromServiceWhenLinkMissingThrowsResourceNotFound() {
        UUID hospitalId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        when(hospitalPlatformServiceLinkRepository
            .findByHospitalIdAndOrganizationServiceId(hospitalId, serviceId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> platformRegistryService
            .unlinkHospitalFromService(hospitalId, serviceId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasFieldOrPropertyWithValue("messageKey", "platform.hospitalLink.notFound");
    }

    @Test
    void unlinkHospitalFromServiceSkipsEventWhenServiceMissing() {
        UUID hospitalId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        HospitalPlatformServiceLink link = HospitalPlatformServiceLink.builder()
            .hospital(null)
            .organizationService(null)
            .build();
        link.setId(UUID.randomUUID());

        when(hospitalPlatformServiceLinkRepository
            .findByHospitalIdAndOrganizationServiceId(hospitalId, serviceId))
            .thenReturn(Optional.of(link));

        platformRegistryService.unlinkHospitalFromService(hospitalId, serviceId, Locale.ENGLISH);

        verify(eventPublisher, never()).publish(any());
        verify(hospitalPlatformServiceLinkRepository).delete(link);
    }

    @Test
    void listHospitalServiceLinksMapsEntities() {
        UUID organizationId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        Organization organization = Organization.builder().build();
        organization.setId(organizationId);

        Hospital hospital = Hospital.builder().organization(organization).name("Central").build();
        hospital.setId(hospitalId);

        OrganizationPlatformService service = OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(PlatformServiceType.ANALYTICS)
            .status(PlatformServiceStatus.ACTIVE)
            .build();
        service.setId(serviceId);

        HospitalPlatformServiceLink link = HospitalPlatformServiceLink.builder()
            .hospital(hospital)
            .organizationService(service)
            .credentialsReference("ref")
            .overrideEndpoint("https://override")
            .build();
        link.setId(UUID.randomUUID());

        when(hospitalPlatformServiceLinkRepository.findByHospitalId(hospitalId)).thenReturn(List.of(link));

        var responses = platformRegistryService.listHospitalServiceLinks(hospitalId, Locale.ENGLISH);

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).getHospitalId()).isEqualTo(hospitalId);
    assertThat(responses.get(0).getOrganizationServiceId()).isEqualTo(serviceId);
    assertThat(responses.get(0).getOverrideEndpoint()).isEqualTo("https://override");
    }

    @Test
    void linkDepartmentToServiceWhenAlreadyLinkedThrowsConflict() {
        UUID organizationId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        Organization organization = Organization.builder().build();
        organization.setId(organizationId);

        Hospital hospital = Hospital.builder().organization(organization).name("Regional").build();
        hospital.setId(UUID.randomUUID());

        Department department = Department.builder().hospital(hospital).name("Cardiology").build();
        department.setId(departmentId);

        OrganizationPlatformService service = OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(PlatformServiceType.EHR)
            .status(PlatformServiceStatus.ACTIVE)
            .build();
        service.setId(serviceId);

        when(departmentRepository.findById(departmentId)).thenReturn(Optional.of(department));
        when(organizationPlatformServiceRepository.findById(serviceId)).thenReturn(Optional.of(service));
        when(departmentPlatformServiceLinkRepository.existsByDepartmentIdAndOrganizationServiceId(departmentId, serviceId))
            .thenReturn(true);

        assertThatThrownBy(() -> platformRegistryService
            .linkDepartmentToService(departmentId, serviceId, null, Locale.ENGLISH))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already linked");
    }

    @Test
    void unlinkDepartmentFromServiceRemovesLinkAndPublishesEvent() {
        UUID organizationId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        Organization organization = Organization.builder().build();
        organization.setId(organizationId);

        Hospital hospital = Hospital.builder().organization(organization).name("Community").build();
        hospital.setId(UUID.randomUUID());

        Department department = Department.builder().hospital(hospital).name("Lab").build();
        department.setId(departmentId);

        OrganizationPlatformService service = OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(PlatformServiceType.INVENTORY)
            .status(PlatformServiceStatus.ACTIVE)
            .build();
        service.setId(serviceId);

        DepartmentPlatformServiceLink link = DepartmentPlatformServiceLink.builder()
            .department(department)
            .organizationService(service)
            .credentialsReference("dept-cred")
            .build();
        link.setId(UUID.randomUUID());
        department.addPlatformServiceLink(link);
        service.addDepartmentLink(link);

        when(departmentPlatformServiceLinkRepository
            .findByDepartmentIdAndOrganizationServiceId(departmentId, serviceId))
            .thenReturn(Optional.of(link));

        platformRegistryService.unlinkDepartmentFromService(departmentId, serviceId, Locale.ENGLISH);

        verify(departmentPlatformServiceLinkRepository).delete(link);
        verify(eventPublisher).publish(any());
        assertThat(department.getPlatformServiceLinks()).isEmpty();
        assertThat(service.getDepartmentLinks()).isEmpty();
    }

    @Test
    void unlinkDepartmentFromServiceWhenLinkMissingThrowsResourceNotFound() {
        UUID departmentId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        when(departmentPlatformServiceLinkRepository
            .findByDepartmentIdAndOrganizationServiceId(departmentId, serviceId))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> platformRegistryService
            .unlinkDepartmentFromService(departmentId, serviceId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasFieldOrPropertyWithValue("messageKey", "platform.departmentLink.notFound");
    }

    @Test
    void listDepartmentServiceLinksMapsEntities() {
        UUID organizationId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        Organization organization = Organization.builder().build();
        organization.setId(organizationId);

        Hospital hospital = Hospital.builder().organization(organization).name("Teaching").build();
        hospital.setId(UUID.randomUUID());

        Department department = Department.builder().hospital(hospital).name("Pharmacy").build();
        department.setId(departmentId);

        OrganizationPlatformService service = OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(PlatformServiceType.ANALYTICS)
            .status(PlatformServiceStatus.ACTIVE)
            .build();
        service.setId(serviceId);

        DepartmentPlatformServiceLink link = DepartmentPlatformServiceLink.builder()
            .department(department)
            .organizationService(service)
            .overrideEndpoint("https://dept")
            .build();
        link.setId(UUID.randomUUID());

        when(departmentPlatformServiceLinkRepository.findByDepartmentId(departmentId)).thenReturn(List.of(link));

        var responses = platformRegistryService.listDepartmentServiceLinks(departmentId, Locale.ENGLISH);

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).getDepartmentId()).isEqualTo(departmentId);
    assertThat(responses.get(0).getOrganizationServiceId()).isEqualTo(serviceId);
    assertThat(responses.get(0).getOverrideEndpoint()).isEqualTo("https://dept");
    }

    // ── Platform Management defects (D2, D3, D5, D13, audit) ──────────────

    private Organization org(UUID id) {
        Organization organization = Organization.builder().build();
        organization.setId(id);
        return organization;
    }

    private OrganizationPlatformService ehrService(Organization organization, UUID serviceId) {
        OrganizationPlatformService service = OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(PlatformServiceType.EHR)
            .status(PlatformServiceStatus.ACTIVE)
            .build();
        service.setId(serviceId);
        return service;
    }

    private static IntegrationDescriptor descriptor(PlatformServiceType type, boolean enabled) {
        return IntegrationDescriptor.builder().serviceType(type).enabled(enabled).build();
    }

    @Test
    @DisplayName("D13: a catalog entry that is disabled cannot be provisioned")
    void registerRefusesADisabledCatalogEntry() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(org(organizationId)));
        when(platformServiceRegistry.findIntegration(PlatformServiceType.INVENTORY, Locale.ENGLISH))
            .thenReturn(Optional.of(descriptor(PlatformServiceType.INVENTORY, false)));
        PlatformServiceRegistrationRequestDTO request = PlatformServiceRegistrationRequestDTO.builder()
            .serviceType(PlatformServiceType.INVENTORY)
            .build();

        assertThatThrownBy(() -> platformRegistryService.registerOrganizationService(organizationId, request, Locale.ENGLISH))
            .isInstanceOf(ConflictException.class)
            .hasMessage("The INVENTORY integration is disabled in this deployment's catalog and cannot be provisioned.");
        verify(organizationPlatformServiceRepository, never()).save(any());
        verifyNoInteractions(auditEventLogService);
    }

    @Test
    @DisplayName("D13: the refusal is in the caller's language")
    void registerRefusalIsLocalized() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(org(organizationId)));
        when(platformServiceRegistry.findIntegration(PlatformServiceType.INVENTORY, Locale.FRENCH))
            .thenReturn(Optional.of(descriptor(PlatformServiceType.INVENTORY, false)));
        PlatformServiceRegistrationRequestDTO request = PlatformServiceRegistrationRequestDTO.builder()
            .serviceType(PlatformServiceType.INVENTORY)
            .build();

        assertThatThrownBy(() -> platformRegistryService.registerOrganizationService(organizationId, request, Locale.FRENCH))
            .isInstanceOf(ConflictException.class)
            .hasMessageStartingWith("L'intégration INVENTORY est désactivée");
    }

    @Test
    @DisplayName("D13: an enabled catalog entry still registers")
    void registerAcceptsAnEnabledCatalogEntry() {
        UUID organizationId = UUID.randomUUID();
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(org(organizationId)));
        when(platformServiceRegistry.findIntegration(PlatformServiceType.EHR, Locale.ENGLISH))
            .thenReturn(Optional.of(descriptor(PlatformServiceType.EHR, true)));
        when(organizationPlatformServiceRepository.save(any(OrganizationPlatformService.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        PlatformServiceRegistrationRequestDTO request = PlatformServiceRegistrationRequestDTO.builder()
            .serviceType(PlatformServiceType.EHR)
            .build();

        PlatformServiceResponseDTO response = platformRegistryService.registerOrganizationService(organizationId,
            request, Locale.ENGLISH);

        assertThat(response.getServiceType()).isEqualTo(PlatformServiceType.EHR);
    }

    @Test
    @DisplayName("D2: a disabled link is switched on in place, not re-created")
    void updateHospitalServiceLinkEnablesTheExistingLink() {
        UUID organizationId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        Organization organization = org(organizationId);
        Hospital hospital = Hospital.builder().organization(organization).name("Clinique Nord").build();
        hospital.setId(hospitalId);
        OrganizationPlatformService service = ehrService(organization, serviceId);
        HospitalPlatformServiceLink link = HospitalPlatformServiceLink.builder()
            .hospital(hospital)
            .organizationService(service)
            .enabled(false)
            .build();
        when(hospitalPlatformServiceLinkRepository.findByHospitalIdAndOrganizationServiceId(hospitalId, serviceId))
            .thenReturn(Optional.of(link));
        when(hospitalPlatformServiceLinkRepository.save(link)).thenReturn(link);

        HospitalPlatformServiceLinkResponseDTO response = platformRegistryService.updateHospitalServiceLink(hospitalId,
            serviceId, PlatformServiceLinkUpdateRequestDTO.builder().enabled(true).build(), Locale.ENGLISH);

        assertThat(response.isEnabled()).isTrue();
        assertThat(link.isEnabled()).isTrue();
        verify(hospitalPlatformServiceLinkRepository, never()).existsByHospitalIdAndOrganizationServiceId(any(), any());
        ArgumentCaptor<PlatformServiceEventPayload> event = ArgumentCaptor.forClass(PlatformServiceEventPayload.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(PlatformRegistryEventType.HOSPITAL_SERVICE_LINK_UPDATED);
        assertThat(event.getValue().getLinkEnabled()).isTrue();
    }

    @Test
    @DisplayName("D2: the update keeps the create's organization-ownership rule")
    void updateHospitalServiceLinkRefusesACrossOrganizationLink() {
        UUID hospitalId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        Hospital hospital = Hospital.builder().organization(org(UUID.randomUUID())).build();
        hospital.setId(hospitalId);
        HospitalPlatformServiceLink link = HospitalPlatformServiceLink.builder()
            .hospital(hospital)
            .organizationService(ehrService(org(UUID.randomUUID()), serviceId))
            .enabled(false)
            .build();
        when(hospitalPlatformServiceLinkRepository.findByHospitalIdAndOrganizationServiceId(hospitalId, serviceId))
            .thenReturn(Optional.of(link));
        PlatformServiceLinkUpdateRequestDTO request = PlatformServiceLinkUpdateRequestDTO.builder().enabled(true).build();

        assertThatThrownBy(() -> platformRegistryService.updateHospitalServiceLink(hospitalId, serviceId, request, Locale.ENGLISH))
            .isInstanceOf(BusinessRuleException.class);
        assertThat(link.isEnabled()).isFalse();
        verify(hospitalPlatformServiceLinkRepository, never()).save(any());
    }

    @Test
    void updateHospitalServiceLinkWhenMissingIsNotFound() {
        UUID hospitalId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        when(hospitalPlatformServiceLinkRepository.findByHospitalIdAndOrganizationServiceId(hospitalId, serviceId))
            .thenReturn(Optional.empty());
        PlatformServiceLinkUpdateRequestDTO request = PlatformServiceLinkUpdateRequestDTO.builder().enabled(false).build();

        assertThatExceptionOfType(ResourceNotFoundException.class)
            .isThrownBy(() -> platformRegistryService.updateHospitalServiceLink(hospitalId, serviceId, request, Locale.ENGLISH))
            .satisfies(e -> assertThat(e.getMessageKey()).isEqualTo("platform.hospitalLink.notFound"));
    }

    @Test
    void updateHospitalServiceLinkRequiresEnabled() {
        UUID hospitalId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        PlatformServiceLinkUpdateRequestDTO request = new PlatformServiceLinkUpdateRequestDTO();
        assertThatThrownBy(() -> platformRegistryService.updateHospitalServiceLink(hospitalId, serviceId, request, Locale.ENGLISH))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("D3: one call lists the service's links across every hospital of the organization")
    void listServiceHospitalLinksSpansHospitals() {
        UUID organizationId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        Organization organization = org(organizationId);
        OrganizationPlatformService service = ehrService(organization, serviceId);
        Hospital zeta = Hospital.builder().organization(organization).name("Zeta").build();
        zeta.setId(UUID.randomUUID());
        Hospital alpha = Hospital.builder().organization(organization).name("alpha").build();
        alpha.setId(UUID.randomUUID());
        when(organizationPlatformServiceRepository.findById(serviceId)).thenReturn(Optional.of(service));
        when(hospitalPlatformServiceLinkRepository.findByOrganizationServiceId(serviceId)).thenReturn(List.of(
            HospitalPlatformServiceLink.builder().hospital(zeta).organizationService(service).enabled(true).build(),
            HospitalPlatformServiceLink.builder().hospital(alpha).organizationService(service).enabled(false).build()));

        List<HospitalPlatformServiceLinkResponseDTO> links =
            platformRegistryService.listServiceHospitalLinks(organizationId, serviceId, Locale.ENGLISH);

        assertThat(links).extracting(HospitalPlatformServiceLinkResponseDTO::getHospitalName).containsExactly("alpha", "Zeta");
        assertThat(links).extracting(HospitalPlatformServiceLinkResponseDTO::isEnabled).containsExactly(false, true);
    }

    @Test
    void listServiceHospitalLinksRefusesAnotherOrganizationsService() {
        UUID serviceId = UUID.randomUUID();
        when(organizationPlatformServiceRepository.findById(serviceId))
            .thenReturn(Optional.of(ehrService(org(UUID.randomUUID()), serviceId)));
        UUID otherOrganization = UUID.randomUUID();

        assertThatThrownBy(() -> platformRegistryService.listServiceHospitalLinks(otherOrganization, serviceId, Locale.ENGLISH))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("does not belong to this organization");
        verify(hospitalPlatformServiceLinkRepository, never()).findByOrganizationServiceId(any());
    }

    @Test
    @DisplayName("D5: a request that both sets and clears the API key is refused")
    void updateRefusesSettingAndClearingTheApiKey() {
        PlatformServiceUpdateRequestDTO request = PlatformServiceUpdateRequestDTO.builder()
            .apiKeyReference("vault://new")
            .clearApiKeyReference(true)
            .build();
        UUID organizationId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        assertThatThrownBy(() -> platformRegistryService.updateOrganizationService(organizationId, serviceId, request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessage("Send a new API key reference or ask to clear it, not both.");
        verify(organizationPlatformServiceRepository, never()).save(any());
    }

    @Test
    @DisplayName("D4: an update naming only integration notes keeps the other metadata")
    void updateMergesMetadataThroughTheService() {
        UUID organizationId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        OrganizationPlatformService service = ehrService(org(organizationId), serviceId);
        service.setMetadata(PlatformServiceMetadata.builder()
            .ehrSystem("OpenMRS")
            .billingSystem("Odoo")
            .inventorySystem("mSupply")
            .integrationNotes("old")
            .build());
        when(organizationPlatformServiceRepository.findById(serviceId)).thenReturn(Optional.of(service));
        when(organizationPlatformServiceRepository.save(service)).thenReturn(service);
        PlatformServiceUpdateRequestDTO request = PlatformServiceUpdateRequestDTO.builder()
            .metadata(PlatformServiceMetadataDTO.builder().integrationNotes("new").build())
            .build();

        PlatformServiceResponseDTO response = platformRegistryService.updateOrganizationService(organizationId, serviceId,
            request, Locale.ENGLISH);

        assertThat(response.getMetadata().getEhrSystem()).isEqualTo("OpenMRS");
        assertThat(response.getMetadata().getBillingSystem()).isEqualTo("Odoo");
        assertThat(response.getMetadata().getInventorySystem()).isEqualTo("mSupply");
        assertThat(response.getMetadata().getIntegrationNotes()).isEqualTo("new");
    }

    @Test
    @DisplayName("audit: a registry write records PLATFORM_REGISTRY_UPDATED once, after commit, without names")
    void registryWritesAreAuditedAfterCommit() {
        UUID organizationId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        Organization organization = org(organizationId);
        Hospital hospital = Hospital.builder().organization(organization).name("Clinique Nord").build();
        hospital.setId(hospitalId);
        OrganizationPlatformService service = ehrService(organization, serviceId);
        HospitalPlatformServiceLink link = HospitalPlatformServiceLink.builder()
            .hospital(hospital).organizationService(service).enabled(true).build();
        when(hospitalPlatformServiceLinkRepository.findByHospitalIdAndOrganizationServiceId(hospitalId, serviceId))
            .thenReturn(Optional.of(link));
        when(hospitalPlatformServiceLinkRepository.save(link)).thenReturn(link);
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(UUID.randomUUID())
            .principalUsername("superadmin")
            .build());
        PlatformServiceLinkUpdateRequestDTO request = PlatformServiceLinkUpdateRequestDTO.builder().enabled(false).build();

        TransactionSynchronizationManager.initSynchronization();
        try {
            platformRegistryService.updateHospitalServiceLink(hospitalId, serviceId, request, Locale.ENGLISH);
            verifyNoInteractions(auditEventLogService);

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.PLATFORM_REGISTRY_UPDATED);
        assertThat(audit.getValue().getEntityType()).isEqualTo("PLATFORM_SERVICE");
        assertThat(audit.getValue().getUserName()).isEqualTo("superadmin");
        assertThat(audit.getValue().getEventDescription())
            .contains("hospital link disabled", "type=EHR", hospitalId.toString())
            .doesNotContain("Clinique Nord");
    }

    @Test
    void unlinkAndRegisterAreAuditedToo() {
        UUID organizationId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        Organization organization = org(organizationId);
        Hospital hospital = Hospital.builder().organization(organization).build();
        hospital.setId(hospitalId);
        OrganizationPlatformService service = ehrService(organization, serviceId);
        HospitalPlatformServiceLink link = HospitalPlatformServiceLink.builder()
            .hospital(hospital).organizationService(service).enabled(true).build();
        when(hospitalPlatformServiceLinkRepository.findByHospitalIdAndOrganizationServiceId(hospitalId, serviceId))
            .thenReturn(Optional.of(link));
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
        when(organizationPlatformServiceRepository.save(any(OrganizationPlatformService.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        PlatformServiceRegistrationRequestDTO register = PlatformServiceRegistrationRequestDTO.builder()
            .serviceType(PlatformServiceType.LIMS)
            .build();

        platformRegistryService.unlinkHospitalFromService(hospitalId, serviceId, Locale.ENGLISH);
        platformRegistryService.registerOrganizationService(organizationId, register, Locale.ENGLISH);

        // No transaction in a unit test: TransactionCallbacks runs the audit inline.
        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService, times(2)).logEvent(audit.capture());
        assertThat(audit.getAllValues()).extracting(AuditEventRequestDTO::getEventDescription)
            .anySatisfy(d -> assertThat(d).startsWith("Platform hospital unlinked"))
            .anySatisfy(d -> assertThat(d).startsWith("Platform service registered type=LIMS"));
    }
}
