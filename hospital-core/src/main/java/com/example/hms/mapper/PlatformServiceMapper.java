package com.example.hms.mapper;

import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.embedded.PlatformOwnership;
import com.example.hms.model.embedded.PlatformServiceMetadata;
import com.example.hms.model.platform.DepartmentPlatformServiceLink;
import com.example.hms.model.platform.HospitalPlatformServiceLink;
import com.example.hms.model.platform.OrganizationPlatformService;
import com.example.hms.payload.dto.DepartmentPlatformServiceLinkResponseDTO;
import com.example.hms.payload.dto.HospitalPlatformServiceLinkResponseDTO;
import com.example.hms.payload.dto.PlatformOwnershipDTO;
import com.example.hms.payload.dto.PlatformServiceLinkRequestDTO;
import com.example.hms.payload.dto.PlatformServiceMetadataDTO;
import com.example.hms.payload.dto.PlatformServiceRegistrationRequestDTO;
import com.example.hms.payload.dto.PlatformServiceResponseDTO;
import com.example.hms.payload.dto.PlatformServiceUpdateRequestDTO;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PlatformServiceMapper {

    public OrganizationPlatformService toOrganizationPlatformService(PlatformServiceRegistrationRequestDTO request, Organization organization) {
        if (request == null) {
            return OrganizationPlatformService.builder()
                .organization(organization)
                .build();
        }

        boolean managedByPlatform = request.getManagedByPlatform() == null
            || Boolean.TRUE.equals(request.getManagedByPlatform());

        return OrganizationPlatformService.builder()
            .organization(organization)
            .serviceType(request.getServiceType())
            .provider(trim(request.getProvider()))
            .baseUrl(trim(request.getBaseUrl()))
            .documentationUrl(trim(request.getDocumentationUrl()))
            .apiKeyReference(trim(request.getApiKeyReference()))
            .managedByPlatform(managedByPlatform)
            .ownership(toOwnership(request.getOwnership()))
            .metadata(toMetadata(request.getMetadata()))
            .build();
    }

    public void updateOrganizationServiceFromDto(PlatformServiceUpdateRequestDTO request, OrganizationPlatformService entity) {
        if (request == null || entity == null) {
            return;
        }

        if (request.getStatus() != null) {
            entity.setStatus(request.getStatus());
        }
        if (request.getProvider() != null) {
            entity.setProvider(trim(request.getProvider()));
        }
        if (request.getBaseUrl() != null) {
            entity.setBaseUrl(trim(request.getBaseUrl()));
        }
        if (request.getDocumentationUrl() != null) {
            entity.setDocumentationUrl(trim(request.getDocumentationUrl()));
        }
        if (request.getApiKeyReference() != null) {
            entity.setApiKeyReference(trim(request.getApiKeyReference()));
        }
        if (request.getManagedByPlatform() != null) {
            entity.setManagedByPlatform(request.getManagedByPlatform());
        }
        if (request.getOwnership() != null) {
            entity.setOwnership(toOwnership(request.getOwnership()));
        }
        if (request.getMetadata() != null) {
            entity.setMetadata(toMetadata(request.getMetadata()));
        }
    }

    public PlatformServiceResponseDTO toPlatformServiceResponse(OrganizationPlatformService entity) {
        if (entity == null) {
            return null;
        }

        UUID organizationId = entity.getOrganization() != null ? entity.getOrganization().getId() : null;

        return PlatformServiceResponseDTO.builder()
            .id(entity.getId())
            .organizationId(organizationId)
            .serviceType(entity.getServiceType())
            .status(entity.getStatus())
            .provider(entity.getProvider())
            .baseUrl(entity.getBaseUrl())
            .documentationUrl(entity.getDocumentationUrl())
            .apiKeyReference(entity.getApiKeyReference())
            .managedByPlatform(entity.isManagedByPlatform())
            .ownership(toOwnershipDto(entity.getOwnership()))
            .metadata(toMetadataDto(entity.getMetadata()))
            .hospitalLinkCount(entity.getHospitalLinks() != null ? entity.getHospitalLinks().size() : 0)
            .departmentLinkCount(entity.getDepartmentLinks() != null ? entity.getDepartmentLinks().size() : 0)
            .build();
    }

    public HospitalPlatformServiceLink toHospitalLink(PlatformServiceLinkRequestDTO request, Hospital hospital, OrganizationPlatformService service) {
        HospitalPlatformServiceLink link = HospitalPlatformServiceLink.builder()
            .hospital(hospital)
            .organizationService(service)
            .build();

        applyLinkRequest(request, link);
        return link;
    }

    public DepartmentPlatformServiceLink toDepartmentLink(PlatformServiceLinkRequestDTO request, Department department, OrganizationPlatformService service) {
        DepartmentPlatformServiceLink link = DepartmentPlatformServiceLink.builder()
            .department(department)
            .organizationService(service)
            .build();

        applyLinkRequest(request, link);
        return link;
    }

    private void applyLinkRequest(PlatformServiceLinkRequestDTO request, HospitalPlatformServiceLink link) {
        if (request != null) {
            link.setEnabled(request.getEnabled() == null || request.getEnabled());
            link.setCredentialsReference(trim(request.getCredentialsReference()));
            link.setOverrideEndpoint(trim(request.getOverrideEndpoint()));
            if (request.getOwnership() != null) {
                link.setOwnership(toOwnership(request.getOwnership()));
            }
        }
        if (request == null || request.getOwnership() == null) {
            link.setOwnership(PlatformOwnership.empty());
        }
    }

    private void applyLinkRequest(PlatformServiceLinkRequestDTO request, DepartmentPlatformServiceLink link) {
        if (request != null) {
            link.setEnabled(request.getEnabled() == null || request.getEnabled());
            link.setCredentialsReference(trim(request.getCredentialsReference()));
            link.setOverrideEndpoint(trim(request.getOverrideEndpoint()));
            if (request.getOwnership() != null) {
                link.setOwnership(toOwnership(request.getOwnership()));
            }
        }
        if (request == null || request.getOwnership() == null) {
            link.setOwnership(PlatformOwnership.empty());
        }
    }

    public HospitalPlatformServiceLinkResponseDTO toHospitalLinkResponse(HospitalPlatformServiceLink link) {
        if (link == null) {
            return null;
        }

        Hospital hospital = link.getHospital();
        OrganizationPlatformService service = link.getOrganizationService();

        return HospitalPlatformServiceLinkResponseDTO.builder()
            .id(link.getId())
            .hospitalId(hospital != null ? hospital.getId() : null)
            .hospitalName(hospital != null ? hospital.getName() : null)
            .organizationServiceId(service != null ? service.getId() : null)
            .serviceType(service != null ? service.getServiceType() : null)
            .enabled(link.isEnabled())
            .credentialsReference(link.getCredentialsReference())
            .overrideEndpoint(link.getOverrideEndpoint())
            .ownership(toOwnershipDto(link.getOwnership()))
            .build();
    }

    public DepartmentPlatformServiceLinkResponseDTO toDepartmentLinkResponse(DepartmentPlatformServiceLink link) {
        if (link == null) {
            return null;
        }

        Department department = link.getDepartment();
        OrganizationPlatformService service = link.getOrganizationService();
        Hospital hospital = department != null ? department.getHospital() : null;

        return DepartmentPlatformServiceLinkResponseDTO.builder()
            .id(link.getId())
            .departmentId(department != null ? department.getId() : null)
            .departmentName(department != null ? department.getName() : null)
            .hospitalId(hospital != null ? hospital.getId() : null)
            .organizationServiceId(service != null ? service.getId() : null)
            .serviceType(service != null ? service.getServiceType() : null)
            .enabled(link.isEnabled())
            .credentialsReference(link.getCredentialsReference())
            .overrideEndpoint(link.getOverrideEndpoint())
            .ownership(toOwnershipDto(link.getOwnership()))
            .build();
    }

    private PlatformOwnership toOwnership(PlatformOwnershipDTO dto) {
        if (dto == null) {
            return PlatformOwnership.empty();
        }

        return PlatformOwnership.builder()
            .ownerTeam(trim(dto.getOwnerTeam()))
            .ownerContactEmail(trim(dto.getOwnerContactEmail()))
            .dataSteward(trim(dto.getDataSteward()))
            .serviceLevel(trim(dto.getServiceLevel()))
            .build();
    }

    private PlatformOwnershipDTO toOwnershipDto(PlatformOwnership ownership) {
        if (ownership == null) {
            return PlatformOwnershipDTO.builder().build();
        }

        return PlatformOwnershipDTO.builder()
            .ownerTeam(ownership.getOwnerTeam())
            .ownerContactEmail(ownership.getOwnerContactEmail())
            .dataSteward(ownership.getDataSteward())
            .serviceLevel(ownership.getServiceLevel())
            .build();
    }

    private PlatformServiceMetadata toMetadata(PlatformServiceMetadataDTO dto) {
        if (dto == null) {
            return PlatformServiceMetadata.empty();
        }

        return PlatformServiceMetadata.builder()
            .ehrSystem(trim(dto.getEhrSystem()))
            .billingSystem(trim(dto.getBillingSystem()))
            .inventorySystem(trim(dto.getInventorySystem()))
            .integrationNotes(trim(dto.getIntegrationNotes()))
            .build();
    }

    private PlatformServiceMetadataDTO toMetadataDto(PlatformServiceMetadata metadata) {
        if (metadata == null) {
            return PlatformServiceMetadataDTO.builder().build();
        }

        return PlatformServiceMetadataDTO.builder()
            .ehrSystem(metadata.getEhrSystem())
            .billingSystem(metadata.getBillingSystem())
            .inventorySystem(metadata.getInventorySystem())
            .integrationNotes(metadata.getIntegrationNotes())
            .build();
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
