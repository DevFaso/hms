package com.example.hms.service.platform;

import com.example.hms.exception.ConflictException;
import com.example.hms.payload.dto.PlatformServiceRegistrationRequestDTO;
import com.example.hms.service.platform.discovery.IntegrationDescriptor;
import com.example.hms.service.platform.discovery.PlatformServiceRegistry;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationPlatformBootstrapService {

    private final PlatformServiceRegistry platformServiceRegistry;
    private final PlatformRegistryService platformRegistryService;

    public void bootstrapDefaultIntegrations(UUID organizationId, Locale locale) {
        Objects.requireNonNull(organizationId, "organizationId is required");
        Locale safeLocale = locale == null ? Locale.getDefault() : locale;

        platformServiceRegistry.listIntegrations(true, safeLocale).stream()
            .filter(descriptor -> descriptor.isEnabled() && descriptor.isAutoProvision())
            .forEach(descriptor -> registerIfMissing(organizationId, descriptor, safeLocale));
    }

    private void registerIfMissing(UUID organizationId, IntegrationDescriptor descriptor, Locale locale) {
        try {
            PlatformServiceRegistrationRequestDTO request = PlatformServiceRegistrationRequestDTO.builder()
                .serviceType(descriptor.getServiceType())
                .provider(descriptor.getProvider())
                .baseUrl(descriptor.getBaseUrl())
                .documentationUrl(descriptor.getDocumentationUrl())
                .managedByPlatform(descriptor.isManagedByPlatform())
                .ownership(descriptor.getDefaultOwnership())
                .metadata(descriptor.getDefaultMetadata())
                .build();

            platformRegistryService.registerOrganizationService(organizationId, request, locale);
            log.info("Auto-provisioned {} integration for organization {}", descriptor.getServiceType(), organizationId);
        } catch (ConflictException conflict) {
            log.debug("Integration {} already registered for organization {}", descriptor.getServiceType(), organizationId);
        } catch (RuntimeException ex) {
            log.warn("Failed to bootstrap integration {} for organization {}: {}", descriptor.getServiceType(), organizationId, ex.getMessage());
        }
    }
}
