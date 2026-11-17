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
            .provider(trimToNull(request.getProvider()))
            .baseUrl(trimToNull(request.getBaseUrl()))
            .documentationUrl(trimToNull(request.getDocumentationUrl()))
            .apiKeyReference(trimToNull(request.getApiKeyReference()))
            .managedByPlatform(managedByPlatform)
            .ownership(toOwnership(request.getOwnership()))
            .metadata(toMetadata(request.getMetadata()))
            .build();
    }

    /**
     * Applies a partial update. The contract, field by field (D4/D5):
     * <ul>
     *   <li>absent / {@code null} — the field is left unchanged;</li>
     *   <li>blank ({@code ""} or whitespace) — the field is cleared to {@code null};</li>
     *   <li>anything else — replaces the stored value (trimmed).</li>
     * </ul>
     * Ownership and metadata are merged field by field under the same rule,
     * so a request that names only {@code metadata.integrationNotes} no
     * longer wipes {@code ehrSystem}, {@code billingSystem} and
     * {@code inventorySystem}.
     *
     * <p>The API-key reference is the exception: it is write-only (item 45),
     * so the portal never holds its value and a blank field means "keep".
     * Clearing it is the explicit {@code clearApiKeyReference} flag; the
     * service refuses a request that both sets and clears it.
     */
    public void updateOrganizationServiceFromDto(PlatformServiceUpdateRequestDTO request, OrganizationPlatformService entity) {
        if (request == null || entity == null) {
            return;
        }

        if (request.getStatus() != null) {
            entity.setStatus(request.getStatus());
        }
        entity.setProvider(merged(entity.getProvider(), request.getProvider()));
        entity.setBaseUrl(merged(entity.getBaseUrl(), request.getBaseUrl()));
        entity.setDocumentationUrl(merged(entity.getDocumentationUrl(), request.getDocumentationUrl()));
        if (Boolean.TRUE.equals(request.getClearApiKeyReference())) {
            entity.setApiKeyReference(null);
        } else if (request.getApiKeyReference() != null && !request.getApiKeyReference().isBlank()) {
            entity.setApiKeyReference(request.getApiKeyReference().trim());
        }
        if (request.getManagedByPlatform() != null) {
            entity.setManagedByPlatform(request.getManagedByPlatform());
        }
        if (request.getOwnership() != null) {
            entity.setOwnership(mergeOwnership(entity.getOwnership(), request.getOwnership()));
        }
        if (request.getMetadata() != null) {
            entity.setMetadata(mergeMetadata(entity.getMetadata(), request.getMetadata()));
        }
    }

    private PlatformOwnership mergeOwnership(PlatformOwnership current, PlatformOwnershipDTO dto) {
        PlatformOwnership base = current != null ? current : PlatformOwnership.empty();
        return PlatformOwnership.builder()
            .ownerTeam(merged(base.getOwnerTeam(), dto.getOwnerTeam()))
            .ownerContactEmail(merged(base.getOwnerContactEmail(), dto.getOwnerContactEmail()))
            .dataSteward(merged(base.getDataSteward(), dto.getDataSteward()))
            .serviceLevel(merged(base.getServiceLevel(), dto.getServiceLevel()))
            .build();
    }

    private PlatformServiceMetadata mergeMetadata(PlatformServiceMetadata current, PlatformServiceMetadataDTO dto) {
        PlatformServiceMetadata base = current != null ? current : PlatformServiceMetadata.empty();
        return PlatformServiceMetadata.builder()
            .ehrSystem(merged(base.getEhrSystem(), dto.getEhrSystem()))
            .billingSystem(merged(base.getBillingSystem(), dto.getBillingSystem()))
            .inventorySystem(merged(base.getInventorySystem(), dto.getInventorySystem()))
            .integrationNotes(merged(base.getIntegrationNotes(), dto.getIntegrationNotes()))
            .build();
    }

    /** {@code null} keeps {@code current}; blank clears; otherwise the trimmed value. */
    private String merged(String current, String incoming) {
        if (incoming == null) {
            return current;
        }
        return trimToNull(incoming);
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
            // Write-only since item 45: the reference is a credential and
            // must not round-trip through reads, the portal or events.
            .apiKeyReferenceSet(entity.getApiKeyReference() != null
                && !entity.getApiKeyReference().isBlank())
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
            link.setCredentialsReference(trimToNull(request.getCredentialsReference()));
            link.setOverrideEndpoint(trimToNull(request.getOverrideEndpoint()));
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
            link.setCredentialsReference(trimToNull(request.getCredentialsReference()));
            link.setOverrideEndpoint(trimToNull(request.getOverrideEndpoint()));
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
            .credentialsReferenceSet(link.getCredentialsReference() != null
                && !link.getCredentialsReference().isBlank())
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
            .credentialsReferenceSet(link.getCredentialsReference() != null
                && !link.getCredentialsReference().isBlank())
            .overrideEndpoint(link.getOverrideEndpoint())
            .ownership(toOwnershipDto(link.getOwnership()))
            .build();
    }

    private PlatformOwnership toOwnership(PlatformOwnershipDTO dto) {
        if (dto == null) {
            return PlatformOwnership.empty();
        }

        return PlatformOwnership.builder()
            .ownerTeam(trimToNull(dto.getOwnerTeam()))
            .ownerContactEmail(trimToNull(dto.getOwnerContactEmail()))
            .dataSteward(trimToNull(dto.getDataSteward()))
            .serviceLevel(trimToNull(dto.getServiceLevel()))
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
            .ehrSystem(trimToNull(dto.getEhrSystem()))
            .billingSystem(trimToNull(dto.getBillingSystem()))
            .inventorySystem(trimToNull(dto.getInventorySystem()))
            .integrationNotes(trimToNull(dto.getIntegrationNotes()))
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

    /** Blank and null both mean "no value": an empty string is never stored. */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
