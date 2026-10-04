package com.example.hms.service.platform.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.platform.PlatformRegistryEventType;
import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.exception.BusinessException;
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
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.DepartmentPlatformServiceLinkResponseDTO;
import com.example.hms.payload.dto.HospitalPlatformServiceLinkResponseDTO;
import com.example.hms.payload.dto.PlatformServiceLinkRequestDTO;
import com.example.hms.payload.dto.PlatformServiceLinkUpdateRequestDTO;
import com.example.hms.payload.dto.PlatformServiceRegistrationRequestDTO;
import com.example.hms.payload.dto.PlatformServiceResponseDTO;
import com.example.hms.payload.dto.PlatformServiceUpdateRequestDTO;
import com.example.hms.payload.event.PlatformServiceEventPayload;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.platform.DepartmentPlatformServiceLinkRepository;
import com.example.hms.repository.platform.HospitalPlatformServiceLinkRepository;
import com.example.hms.repository.platform.OrganizationPlatformServiceRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.platform.PlatformRegistryService;
import com.example.hms.service.platform.discovery.PlatformServiceRegistry;
import com.example.hms.service.platform.event.PlatformRegistryEventPublisher;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.TransactionCallbacks;
import java.time.Instant;
import java.util.Comparator;
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
    private static final String PLATFORM_SERVICE_NOT_FOUND_KEY = "platform.service.notFound";
    private static final String HOSPITAL_LINK_NOT_FOUND_KEY = "platform.hospitalLink.notFound";
    private static final String MISSING_ORGANIZATION_KEY = "platform.service.missingOrganization";

    private static final String ORGANIZATION_ID_REQUIRED = "organizationId is required";
    private static final String ORGANIZATION_SERVICE_ID_REQUIRED = "organizationServiceId is required";
    private static final String HOSPITAL_ID_REQUIRED = "hospitalId is required";
    private static final String DEPARTMENT_ID_REQUIRED = "departmentId is required";

    private static final String AUDIT_ENTITY_TYPE = "PLATFORM_SERVICE";
    private static final String SYSTEM_ACTOR = "system";

    private final OrganizationRepository organizationRepository;
    private final OrganizationPlatformServiceRepository organizationPlatformServiceRepository;
    private final HospitalRepository hospitalRepository;
    private final HospitalPlatformServiceLinkRepository hospitalPlatformServiceLinkRepository;
    private final DepartmentRepository departmentRepository;
    private final DepartmentPlatformServiceLinkRepository departmentPlatformServiceLinkRepository;
    private final PlatformServiceMapper platformServiceMapper;
    private final PlatformRegistryEventPublisher eventPublisher;
    private final PlatformServiceRegistry platformServiceRegistry;
    private final AuditEventLogService auditEventLogService;

    @Override
    public PlatformServiceResponseDTO registerOrganizationService(UUID organizationId,
                                                                  PlatformServiceRegistrationRequestDTO request,
                                                                  Locale locale) {
        Objects.requireNonNull(organizationId, ORGANIZATION_ID_REQUIRED);
        if (request == null || request.getServiceType() == null) {
            throw new IllegalArgumentException("Platform service type is required");
        }

        Organization organization = organizationRepository.findById(organizationId)
            .orElseThrow(() -> new ResourceNotFoundException("organization.notFound", organizationId));

        // D13: a catalog entry switched off for this deployment cannot be
        // provisioned, whichever screen the request came from. A type with no
        // catalog adapter at all stays registrable by hand.
        platformServiceRegistry.findIntegration(request.getServiceType(), locale)
            .filter(descriptor -> !descriptor.isEnabled())
            .ifPresent(descriptor -> {
                throw new ConflictException(MessageUtil.resolve(locale,
                    "platform.catalog.integrationDisabled", request.getServiceType().name()));
            });

        boolean exists = organizationPlatformServiceRepository
            .existsByOrganizationIdAndServiceType(organizationId, request.getServiceType());
        if (exists) {
            throw new ConflictException(MessageUtil.resolve(locale,
                "platform.service.alreadyRegistered", request.getServiceType().name()));
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
        auditAfterCommit("service registered", saved, null, null);
        return platformServiceMapper.toPlatformServiceResponse(saved);
    }

    @Override
    public PlatformServiceResponseDTO updateOrganizationService(UUID organizationId,
                                                                UUID serviceId,
                                                                PlatformServiceUpdateRequestDTO request,
                                                                Locale locale) {
        if (request != null && Boolean.TRUE.equals(request.getClearApiKeyReference())
            && request.getApiKeyReference() != null && !request.getApiKeyReference().isBlank()) {
            throw new BusinessException("platform.service.apiKey.setAndClear");
        }
        OrganizationPlatformService service = loadServiceForOrganization(organizationId, serviceId);
        platformServiceMapper.updateOrganizationServiceFromDto(request, service);
        OrganizationPlatformService saved = organizationPlatformServiceRepository.save(service);

        log.debug("Updated platform service {} for organization {}", serviceId, organizationId);
        publishServiceEvent(PlatformRegistryEventType.ORGANIZATION_SERVICE_UPDATED, saved, null, null, null);
        auditAfterCommit(request != null && request.getStatus() != null
            ? "service status set to " + request.getStatus().name()
            : "service updated", saved, null, null);
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
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", hospitalId));
        OrganizationPlatformService service = organizationPlatformServiceRepository.findById(organizationServiceId)
            .orElseThrow(() -> new ResourceNotFoundException(PLATFORM_SERVICE_NOT_FOUND_KEY, organizationServiceId));

        validateHospitalBelongsToServiceOrganization(hospital, service);

        boolean exists = hospitalPlatformServiceLinkRepository
            .existsByHospitalIdAndOrganizationServiceId(hospitalId, organizationServiceId);
        if (exists) {
            // An existing (possibly disabled) link is switched with
            // updateHospitalServiceLink; a second create is always a conflict.
            throw new ConflictException(MessageUtil.resolve(locale, "platform.hospitalLink.exists"));
        }

        HospitalPlatformServiceLink link = platformServiceMapper.toHospitalLink(request, hospital, service);
        hospital.addPlatformServiceLink(link);
        service.addHospitalLink(link);

        HospitalPlatformServiceLink saved = hospitalPlatformServiceLinkRepository.save(link);
        log.info("Linked hospital {} to platform service {}", hospitalId, organizationServiceId);
        publishServiceEvent(PlatformRegistryEventType.HOSPITAL_LINKED_TO_SERVICE, service, hospitalId, null, saved.isEnabled());
        auditAfterCommit("hospital linked (enabled=" + saved.isEnabled() + ")", service, hospitalId, null);
        return platformServiceMapper.toHospitalLinkResponse(saved);
    }

    @Override
    public HospitalPlatformServiceLinkResponseDTO updateHospitalServiceLink(UUID hospitalId,
                                                                            UUID organizationServiceId,
                                                                            PlatformServiceLinkUpdateRequestDTO request,
                                                                            Locale locale) {
        Objects.requireNonNull(hospitalId, HOSPITAL_ID_REQUIRED);
        Objects.requireNonNull(organizationServiceId, ORGANIZATION_SERVICE_ID_REQUIRED);
        if (request == null || request.getEnabled() == null) {
            throw new IllegalArgumentException("enabled is required");
        }

        HospitalPlatformServiceLink link = hospitalPlatformServiceLinkRepository
            .findByHospitalIdAndOrganizationServiceId(hospitalId, organizationServiceId)
            .orElseThrow(() -> ResourceNotFoundException.inLocale(locale, HOSPITAL_LINK_NOT_FOUND_KEY,
                hospitalId, organizationServiceId));
        OrganizationPlatformService service = link.getOrganizationService();
        // The ownership rule that guards creating a link guards switching one
        // back on: a link that spans two organizations is refused, not enabled.
        validateHospitalBelongsToServiceOrganization(link.getHospital(), service);

        link.setEnabled(request.getEnabled());
        HospitalPlatformServiceLink saved = hospitalPlatformServiceLinkRepository.save(link);
        log.info("Set hospital {} link to platform service {} enabled={}", hospitalId, organizationServiceId, saved.isEnabled());
        publishServiceEvent(PlatformRegistryEventType.HOSPITAL_SERVICE_LINK_UPDATED, service, hospitalId, null, saved.isEnabled());
        auditAfterCommit(saved.isEnabled() ? "hospital link enabled" : "hospital link disabled", service, hospitalId, null);
        return platformServiceMapper.toHospitalLinkResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HospitalPlatformServiceLinkResponseDTO> listServiceHospitalLinks(UUID organizationId,
                                                                                 UUID serviceId,
                                                                                 Locale locale) {
        OrganizationPlatformService service = loadServiceForOrganization(organizationId, serviceId);
        return hospitalPlatformServiceLinkRepository.findByOrganizationServiceId(service.getId()).stream()
            .map(platformServiceMapper::toHospitalLinkResponse)
            .sorted(Comparator.comparing(HospitalPlatformServiceLinkResponseDTO::getHospitalName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();
    }

    @Override
    public void unlinkHospitalFromService(UUID hospitalId,
                                          UUID organizationServiceId,
                                          Locale locale) {
        Objects.requireNonNull(hospitalId, HOSPITAL_ID_REQUIRED);
        Objects.requireNonNull(organizationServiceId, ORGANIZATION_SERVICE_ID_REQUIRED);

        HospitalPlatformServiceLink link = hospitalPlatformServiceLinkRepository
            .findByHospitalIdAndOrganizationServiceId(hospitalId, organizationServiceId)
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_LINK_NOT_FOUND_KEY, hospitalId, organizationServiceId));

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
        auditAfterCommit("hospital unlinked", service, hospitalId, null);
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
            .orElseThrow(() -> new ResourceNotFoundException("department.notFound", departmentId));

        OrganizationPlatformService service = organizationPlatformServiceRepository.findById(organizationServiceId)
            .orElseThrow(() -> new ResourceNotFoundException(PLATFORM_SERVICE_NOT_FOUND_KEY, organizationServiceId));

        validateDepartmentBelongsToServiceOrganization(department, service);

        boolean exists = departmentPlatformServiceLinkRepository
            .existsByDepartmentIdAndOrganizationServiceId(departmentId, organizationServiceId);
        if (exists) {
            throw new ConflictException(MessageUtil.resolve(locale, "platform.departmentLink.exists"));
        }

        DepartmentPlatformServiceLink link = platformServiceMapper.toDepartmentLink(request, department, service);
        department.addPlatformServiceLink(link);
        service.addDepartmentLink(link);

        DepartmentPlatformServiceLink saved = departmentPlatformServiceLinkRepository.save(link);
        log.info("Linked department {} to platform service {}", departmentId, organizationServiceId);
        publishServiceEvent(PlatformRegistryEventType.DEPARTMENT_LINKED_TO_SERVICE, service, null, departmentId, saved.isEnabled());
        auditAfterCommit("department linked (enabled=" + saved.isEnabled() + ")", service, null, departmentId);
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
            .orElseThrow(() -> new ResourceNotFoundException("platform.departmentLink.notFound", departmentId, organizationServiceId));

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
        auditAfterCommit("department unlinked", service, null, departmentId);
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
            .orElseThrow(() -> new ResourceNotFoundException(PLATFORM_SERVICE_NOT_FOUND_KEY, serviceId));

        UUID serviceOrganizationId = Optional.ofNullable(service.getOrganization())
            .map(Organization::getId)
            .orElseThrow(() -> BusinessRuleException.ofKey(MISSING_ORGANIZATION_KEY));

        if (!serviceOrganizationId.equals(organizationId)) {
            throw BusinessRuleException.ofKey("platform.service.wrongOrganization");
        }
        return service;
    }

    private void validateHospitalBelongsToServiceOrganization(Hospital hospital, OrganizationPlatformService service) {
        Organization organization = hospital == null ? null : hospital.getOrganization();
        if (organization == null || !Objects.equals(organization.getId(), getOrganizationId(service))) {
            throw BusinessRuleException.ofKey("platform.hospital.wrongOrganization");
        }
    }

    private void validateDepartmentBelongsToServiceOrganization(Department department, OrganizationPlatformService service) {
        Hospital hospital = department.getHospital();
        if (hospital == null) {
            throw BusinessRuleException.ofKey("platform.department.noHospital");
        }
        validateHospitalBelongsToServiceOrganization(hospital, service);
    }

    private UUID getOrganizationId(OrganizationPlatformService service) {
        return Optional.ofNullable(service)
            .map(OrganizationPlatformService::getOrganization)
            .map(Organization::getId)
            .orElseThrow(() -> BusinessRuleException.ofKey(MISSING_ORGANIZATION_KEY));
    }

    /**
     * One {@link AuditEventType#PLATFORM_REGISTRY_UPDATED} row per registry
     * write — the event the release-window schedule already records — so the
     * platform-config audit tab sees every change. The controller opts out of
     * the generic write audit, so the action is recorded once, not twice.
     *
     * <p>Written after commit: {@code logEvent} runs in its own transaction,
     * so recording it inline would keep a row for a write that a later flush
     * rolls back. Identifiers and the service type only: no names, no
     * credentials, no free text from the request.
     */
    private void auditAfterCommit(String action,
                                  OrganizationPlatformService service,
                                  UUID hospitalId,
                                  UUID departmentId) {
        if (service == null) {
            return;
        }
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        String actor = ctx.getPrincipalUsername();
        UUID organizationId = Optional.ofNullable(service.getOrganization()).map(Organization::getId).orElse(null);
        StringBuilder description = new StringBuilder("Platform ")
            .append(action)
            .append(" type=").append(service.getServiceType())
            .append(" organizationId=").append(organizationId);
        if (hospitalId != null) {
            description.append(" hospitalId=").append(hospitalId);
        }
        if (departmentId != null) {
            description.append(" departmentId=").append(departmentId);
        }
        AuditEventRequestDTO event = AuditEventRequestDTO.builder()
            .userId(ctx.getPrincipalUserId())
            .userName(actor != null && !actor.isBlank() ? actor : SYSTEM_ACTOR)
            .eventType(AuditEventType.PLATFORM_REGISTRY_UPDATED)
            .eventDescription(description.toString())
            .resourceId(service.getId() == null ? null : service.getId().toString())
            .resourceName(service.getServiceType() == null ? null : service.getServiceType().name())
            .entityType(AUDIT_ENTITY_TYPE)
            .status(AuditStatus.SUCCESS)
            .build();
        TransactionCallbacks.afterCommit(() -> auditEventLogService.logEvent(event));
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
            // apiKeyReference deliberately NOT copied (item 45): a
            // credential pointer must not ride a Kafka event.
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
