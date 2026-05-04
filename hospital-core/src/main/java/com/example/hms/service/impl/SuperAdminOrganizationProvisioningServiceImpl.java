package com.example.hms.service.impl;

import com.example.hms.enums.OrganizationRegion;
import com.example.hms.enums.OrganizationType;
import com.example.hms.mapper.OrganizationMapper;
import com.example.hms.model.Organization;
import com.example.hms.model.embedded.PlatformOwnership;
import com.example.hms.payload.dto.OrganizationResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminCreateOrganizationRequestDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.service.OrganizationSecurityService;
import com.example.hms.service.RegionRoutingResolver;
import com.example.hms.service.SuperAdminOrganizationProvisioningService;
import com.example.hms.service.platform.OrganizationPlatformBootstrapService;
import com.example.hms.service.provisioning.TenantProvisioningClient;
import jakarta.validation.Valid;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SuperAdminOrganizationProvisioningServiceImpl implements SuperAdminOrganizationProvisioningService {

    private static final String DEFAULT_OWNER_TEAM = "Super Admin Onboarding";

    private final OrganizationRepository organizationRepository;
    private final OrganizationSecurityService organizationSecurityService;
    private final OrganizationPlatformBootstrapService organizationPlatformBootstrapService;
    private final OrganizationMapper organizationMapper;
    private final RegionRoutingResolver regionRoutingResolver;
    private final TenantProvisioningClient tenantProvisioningClient;

    @Override
    public OrganizationResponseDTO createOrganization(@Valid SuperAdminCreateOrganizationRequestDTO request) {
        String normalizedCode = normalizeCode(request.getCode());
        if (organizationRepository.existsByCode(normalizedCode)) {
            throw new IllegalArgumentException("Organization with code '" + normalizedCode + "' already exists");
        }

        OrganizationType type = request.getType() != null ? request.getType() : OrganizationType.HEALTHCARE_NETWORK;
        OrganizationRegion region = request.getRegion() != null ? request.getRegion() : OrganizationRegion.BF;

        // MVP-9c — if the region's policy declares a remote deployment,
        // delegate to the configured TenantProvisioningClient. The stub
        // client throws 501; a real client forwards the request and
        // returns the remote's response. When no URL is configured we
        // fall through to local provisioning unchanged.
        Optional<String> targetUrl = regionRoutingResolver.resolveDeploymentUrl(region);
        if (targetUrl.isPresent()) {
            log.info("[REGION-ROUTING] Region {} -> remote deployment {}; delegating provisioning",
                region, targetUrl.get());
            return tenantProvisioningClient.provisionRemote(request, targetUrl.get());
        }

        Organization organization = Organization.builder()
            .name(request.getName())
            .code(normalizedCode)
            .description(request.getNotes())
            .type(type)
            .active(true)
            .region(region)
            .primaryContactEmail(request.getContactEmail())
            .primaryContactPhone(trimToNull(request.getContactPhone()))
            .defaultTimezone(request.getTimezone())
            .onboardingNotes(request.getNotes())
            .build();

        PlatformOwnership ownership = PlatformOwnership.empty();
        ownership.setOwnerContactEmail(request.getContactEmail());
        ownership.setOwnerTeam(DEFAULT_OWNER_TEAM);
        organization.setOwnership(ownership);

        organization = organizationRepository.save(organization);

        organizationSecurityService.applyDefaultSecurityPolicies(organization.getId(), type);
        organizationPlatformBootstrapService.bootstrapDefaultIntegrations(
            organization.getId(),
            LocaleContextHolder.getLocale()
        );

        Organization organizationWithAssociations = organizationRepository
            .findByIdWithHospitals(organization.getId())
            .orElse(organization);

        log.info("Provisioned organization {} with timezone {}", organization.getCode(), organization.getDefaultTimezone());
        return organizationMapper.toResponseDTO(organizationWithAssociations);
    }

    private String normalizeCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Organization code cannot be null");
        }
        return code.trim().toUpperCase(Locale.ENGLISH);
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
