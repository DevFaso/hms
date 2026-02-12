package com.example.hms.service.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.example.hms.service.platform.event.PlatformRegistryEventPublisher;
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
            eventPublisher
        );
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
            .hasMessageContaining("already registered");
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
            .hasMessageContaining("Hospital must belong");
    }

    @Test
    void linkHospitalToServiceWhenHospitalMissingThrowsResourceNotFound() {
        UUID hospitalId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> platformRegistryService
            .linkHospitalToService(hospitalId, serviceId, null, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Hospital not found");
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
            .hasMessageContaining("Platform service not found");
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
            .hasMessageContaining("missing organization association");
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

        assertThatThrownBy(() -> platformRegistryService
            .linkDepartmentToService(departmentId, serviceId, null, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Department not found");
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
            .hasMessageContaining("Platform service not found");
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
            .hasMessageContaining("Department is not associated");
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
            .hasMessageContaining("missing organization association");
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
            .hasMessageContaining("Hospital link not found");
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
            .hasMessageContaining("Department link not found");
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
}
