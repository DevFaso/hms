package com.example.hms.service.platform.impl;

import com.example.hms.enums.platform.PlatformRegistryEventType;
import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.exception.BusinessRuleException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PlatformServiceMapper;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.platform.DepartmentPlatformServiceLink;
import com.example.hms.model.platform.HospitalPlatformServiceLink;
import com.example.hms.model.platform.OrganizationPlatformService;
import com.example.hms.payload.dto.DepartmentPlatformServiceLinkResponseDTO;
import com.example.hms.payload.dto.HospitalPlatformServiceLinkResponseDTO;
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
import com.example.hms.payload.event.PlatformServiceEventPayload;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.platform.event.PlatformRegistryEventPublisher;
import com.example.hms.service.platform.PlatformRegistryService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PlatformRegistryServiceImpl implements PlatformRegistryService {

    private static final String ORGANIZATION_ID_REQUIRED = "organizationId is required";
    private static final String ORGANIZATION_SERVICE_ID_REQUIRED = "organizationServiceId is required";
    private static final String HOSPITAL_ID_REQUIRED = "hospitalId is required";
    private static final String DEPARTMENT_ID_REQUIRED = "departmentId is required";
    private static final String PLATFORM_SERVICE_NOT_FOUND = "Platform service not found: ";

    private final OrganizationRepository organizationRepository;
    private final OrganizationPlatformServiceRepository organizationPlatformServiceRepository;
    private final HospitalRepository hospitalRepository;
    private final HospitalPlatformServiceLinkRepository hospitalPlatformServiceLinkRepository;
    private final DepartmentRepository departmentRepository;
    private final DepartmentPlatformServiceLinkRepository departmentPlatformServiceLinkRepository;
    private final PlatformServiceMapper platformServiceMapper;
    private final PlatformRegistryEventPublisher eventPublisher;

    @Override
    public PlatformServiceResponseDTO registerOrganizationService(UUID organizationId,
                                                                  PlatformServiceRegistrationRequestDTO request,
                                                                  Locale locale) {
        Objects.requireNonNull(organizationId, ORGANIZATION_ID_REQUIRED);
        if (request == null || request.getServiceType() == null) {
            throw new IllegalArgumentException("Platform service type is required");
        }

        Organization organization = organizationRepository.findById(organizationId)
            .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + organizationId));

        boolean exists = organizationPlatformServiceRepository
            .existsByOrganizationIdAndServiceType(organizationId, request.getServiceType());
        if (exists) {
            throw new ConflictException("Platform service already registered for organization");
        }

        OrganizationPlatformService newService = platformServiceMapper
            .toOrganizationPlatformService(request, organization);
        if (newService.getStatus() == null) {
            newService.setStatus(PlatformServiceStatus.PENDING);
        }
        organization.addPlatformService(newService);

        OrganizationPlatformService saved = organizationPlatformServiceRepository.save(newService);

        log.info("Registered platform service type={} for organization {}", newService.getServiceType(), organizationId);
        publishServiceEvent(PlatformRegistryEventType.ORGANIZATION_SERVICE_REGISTERED, saved, null, null, null);
        return platformServiceMapper.toPlatformServiceResponse(saved);
    }

    @Override
    public PlatformServiceResponseDTO updateOrganizationService(UUID organizationId,
                                                                UUID serviceId,
                                                                PlatformServiceUpdateRequestDTO request,
                                                                Locale locale) {
        OrganizationPlatformService service = loadServiceForOrganization(organizationId, serviceId);
        platformServiceMapper.updateOrganizationServiceFromDto(request, service);
        OrganizationPlatformService saved = organizationPlatformServiceRepository.save(service);

        log.debug("Updated platform service {} for organization {}", serviceId, organizationId);
        publishServiceEvent(PlatformRegistryEventType.ORGANIZATION_SERVICE_UPDATED, saved, null, null, null);
        return platformServiceMapper.toPlatformServiceResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PlatformServiceResponseDTO getOrganizationService(UUID organizationId,
                                                             UUID serviceId,
                                                             Locale locale) {
        OrganizationPlatformService service = loadServiceForOrganization(organizationId, serviceId);
        return platformServiceMapper.toPlatformServiceResponse(service);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlatformServiceResponseDTO> listOrganizationServices(UUID organizationId,
                                                                     PlatformServiceStatus status,
                                                                     Locale locale) {
        Objects.requireNonNull(organizationId, ORGANIZATION_ID_REQUIRED);

        List<OrganizationPlatformService> services = Optional.ofNullable(status)
            .map(it -> organizationPlatformServiceRepository.findByOrganizationIdAndStatus(organizationId, it))
            .orElseGet(() -> organizationPlatformServiceRepository.findByOrganizationId(organizationId));

        return services.stream()
            .map(platformServiceMapper::toPlatformServiceResponse)
            .toList();
    }

    @Override
    public HospitalPlatformServiceLinkResponseDTO linkHospitalToService(UUID hospitalId,
                                                                        UUID organizationServiceId,
                                                                        PlatformServiceLinkRequestDTO request,
                                                                        Locale locale) {
    Objects.requireNonNull(hospitalId, HOSPITAL_ID_REQUIRED);
    Objects.requireNonNull(organizationServiceId, ORGANIZATION_SERVICE_ID_REQUIRED);

        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + hospitalId));
        OrganizationPlatformService service = organizationPlatformServiceRepository.findById(organizationServiceId)
            .orElseThrow(() -> new ResourceNotFoundException(PLATFORM_SERVICE_NOT_FOUND + organizationServiceId));

        validateHospitalBelongsToServiceOrganization(hospital, service);

        boolean exists = hospitalPlatformServiceLinkRepository
            .existsByHospitalIdAndOrganizationServiceId(hospitalId, organizationServiceId);
        if (exists) {
            throw new ConflictException("Hospital already linked to platform service");
        }

        HospitalPlatformServiceLink link = platformServiceMapper.toHospitalLink(request, hospital, service);
        hospital.addPlatformServiceLink(link);
        service.addHospitalLink(link);

        HospitalPlatformServiceLink saved = hospitalPlatformServiceLinkRepository.save(link);
        log.info("Linked hospital {} to platform service {}", hospitalId, organizationServiceId);
        publishServiceEvent(PlatformRegistryEventType.HOSPITAL_LINKED_TO_SERVICE, service, hospitalId, null, saved.isEnabled());
        return platformServiceMapper.toHospitalLinkResponse(saved);
    }

    @Override
    public void unlinkHospitalFromService(UUID hospitalId,
                                          UUID organizationServiceId,
                                          Locale locale) {
    Objects.requireNonNull(hospitalId, HOSPITAL_ID_REQUIRED);
    Objects.requireNonNull(organizationServiceId, ORGANIZATION_SERVICE_ID_REQUIRED);

        HospitalPlatformServiceLink link = hospitalPlatformServiceLinkRepository
            .findByHospitalIdAndOrganizationServiceId(hospitalId, organizationServiceId)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital link not found"));

        Hospital hospital = link.getHospital();
        OrganizationPlatformService service = link.getOrganizationService();
        if (hospital != null) {
            hospital.removePlatformServiceLink(link);
        }
        if (service != null) {
            service.removeHospitalLink(link);
        }

        hospitalPlatformServiceLinkRepository.delete(link);
        log.info("Unlinked hospital {} from platform service {}", hospitalId, organizationServiceId);
        publishServiceEvent(PlatformRegistryEventType.HOSPITAL_UNLINKED_FROM_SERVICE, service, hospitalId, null, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HospitalPlatformServiceLinkResponseDTO> listHospitalServiceLinks(UUID hospitalId,
                                                                                 Locale locale) {
    Objects.requireNonNull(hospitalId, HOSPITAL_ID_REQUIRED);
        return hospitalPlatformServiceLinkRepository.findByHospitalId(hospitalId).stream()
            .map(platformServiceMapper::toHospitalLinkResponse)
            .toList();
    }

    @Override
    public DepartmentPlatformServiceLinkResponseDTO linkDepartmentToService(UUID departmentId,
                                                                            UUID organizationServiceId,
                                                                            PlatformServiceLinkRequestDTO request,
                                                                            Locale locale) {
    Objects.requireNonNull(departmentId, DEPARTMENT_ID_REQUIRED);
    Objects.requireNonNull(organizationServiceId, ORGANIZATION_SERVICE_ID_REQUIRED);

        Department department = departmentRepository.findById(departmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + departmentId));

        OrganizationPlatformService service = organizationPlatformServiceRepository.findById(organizationServiceId)
            .orElseThrow(() -> new ResourceNotFoundException(PLATFORM_SERVICE_NOT_FOUND + organizationServiceId));

        validateDepartmentBelongsToServiceOrganization(department, service);

        boolean exists = departmentPlatformServiceLinkRepository
            .existsByDepartmentIdAndOrganizationServiceId(departmentId, organizationServiceId);
        if (exists) {
            throw new ConflictException("Department already linked to platform service");
        }

        DepartmentPlatformServiceLink link = platformServiceMapper.toDepartmentLink(request, department, service);
        department.addPlatformServiceLink(link);
        service.addDepartmentLink(link);

        DepartmentPlatformServiceLink saved = departmentPlatformServiceLinkRepository.save(link);
        log.info("Linked department {} to platform service {}", departmentId, organizationServiceId);
        publishServiceEvent(PlatformRegistryEventType.DEPARTMENT_LINKED_TO_SERVICE, service, null, departmentId, saved.isEnabled());
        return platformServiceMapper.toDepartmentLinkResponse(saved);
    }

    @Override
    public void unlinkDepartmentFromService(UUID departmentId,
                                            UUID organizationServiceId,
                                            Locale locale) {
    Objects.requireNonNull(departmentId, DEPARTMENT_ID_REQUIRED);
    Objects.requireNonNull(organizationServiceId, ORGANIZATION_SERVICE_ID_REQUIRED);

        DepartmentPlatformServiceLink link = departmentPlatformServiceLinkRepository
            .findByDepartmentIdAndOrganizationServiceId(departmentId, organizationServiceId)
            .orElseThrow(() -> new ResourceNotFoundException("Department link not found"));

        Department department = link.getDepartment();
        OrganizationPlatformService service = link.getOrganizationService();
        if (department != null) {
            department.removePlatformServiceLink(link);
        }
        if (service != null) {
            service.removeDepartmentLink(link);
        }

        departmentPlatformServiceLinkRepository.delete(link);
        log.info("Unlinked department {} from platform service {}", departmentId, organizationServiceId);
        publishServiceEvent(PlatformRegistryEventType.DEPARTMENT_UNLINKED_FROM_SERVICE, service, null, departmentId, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepartmentPlatformServiceLinkResponseDTO> listDepartmentServiceLinks(UUID departmentId,
                                                                                     Locale locale) {
    Objects.requireNonNull(departmentId, DEPARTMENT_ID_REQUIRED);
        return departmentPlatformServiceLinkRepository.findByDepartmentId(departmentId).stream()
            .map(platformServiceMapper::toDepartmentLinkResponse)
            .toList();
    }

    private OrganizationPlatformService loadServiceForOrganization(UUID organizationId, UUID serviceId) {
    Objects.requireNonNull(organizationId, ORGANIZATION_ID_REQUIRED);
        Objects.requireNonNull(serviceId, "serviceId is required");

        OrganizationPlatformService service = organizationPlatformServiceRepository.findById(serviceId)
            .orElseThrow(() -> new ResourceNotFoundException(PLATFORM_SERVICE_NOT_FOUND + serviceId));

        UUID serviceOrganizationId = Optional.ofNullable(service.getOrganization())
            .map(Organization::getId)
            .orElseThrow(() -> new BusinessRuleException("Platform service missing organization association"));

        if (!serviceOrganizationId.equals(organizationId)) {
            throw new BusinessRuleException("Platform service does not belong to organization");
        }
        return service;
    }

    private void validateHospitalBelongsToServiceOrganization(Hospital hospital, OrganizationPlatformService service) {
        Organization organization = hospital.getOrganization();
        if (organization == null || !Objects.equals(organization.getId(), getOrganizationId(service))) {
            throw new BusinessRuleException("Hospital must belong to the same organization as the platform service");
        }
    }

    private void validateDepartmentBelongsToServiceOrganization(Department department, OrganizationPlatformService service) {
        Hospital hospital = department.getHospital();
        if (hospital == null) {
            throw new BusinessRuleException("Department is not associated with a hospital");
        }
        validateHospitalBelongsToServiceOrganization(hospital, service);
    }

    private UUID getOrganizationId(OrganizationPlatformService service) {
        return Optional.ofNullable(service.getOrganization())
            .map(Organization::getId)
            .orElseThrow(() -> new BusinessRuleException("Platform service missing organization association"));
    }

    private void publishServiceEvent(PlatformRegistryEventType eventType,
                                     OrganizationPlatformService service,
                                     UUID hospitalId,
                                     UUID departmentId,
                                     Boolean linkEnabled) {
        if (service == null) {
            return;
        }

        PlatformServiceEventPayload payload = PlatformServiceEventPayload.builder()
            .eventType(eventType)
            .organizationId(Optional.ofNullable(service.getOrganization()).map(Organization::getId).orElse(null))
            .organizationServiceId(service.getId())
            .hospitalId(hospitalId)
            .departmentId(departmentId)
            .serviceType(service.getServiceType())
            .status(service.getStatus())
            .linkEnabled(linkEnabled)
            .managedByPlatform(service.isManagedByPlatform())
            .provider(service.getProvider())
            .baseUrl(service.getBaseUrl())
            .documentationUrl(service.getDocumentationUrl())
            .apiKeyReference(service.getApiKeyReference())
            .ownershipTeam(service.getOwnership() != null ? service.getOwnership().getOwnerTeam() : null)
            .ownershipContactEmail(service.getOwnership() != null ? service.getOwnership().getOwnerContactEmail() : null)
            .ownershipServiceLevel(service.getOwnership() != null ? service.getOwnership().getServiceLevel() : null)
            .ownershipDataSteward(service.getOwnership() != null ? service.getOwnership().getDataSteward() : null)
            .metadataEhrSystem(service.getMetadata() != null ? service.getMetadata().getEhrSystem() : null)
            .metadataBillingSystem(service.getMetadata() != null ? service.getMetadata().getBillingSystem() : null)
            .metadataInventorySystem(service.getMetadata() != null ? service.getMetadata().getInventorySystem() : null)
            .metadataIntegrationNotes(service.getMetadata() != null ? service.getMetadata().getIntegrationNotes() : null)
            .triggeredBy(HospitalContextHolder.getContext().map(ctx -> ctx.getPrincipalUserId()).orElse(null))
            .occurredAt(Instant.now())
            .build();

        try {
            eventPublisher.publish(payload);
        } catch (RuntimeException ex) {
            log.warn("Failed to publish platform registry event {} for organization {}: {}",
                eventType,
                payload.getOrganizationId(),
                ex.getMessage());
        }
    }
}
