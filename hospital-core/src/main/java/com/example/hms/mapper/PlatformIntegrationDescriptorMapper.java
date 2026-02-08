package com.example.hms.mapper;

import com.example.hms.payload.dto.platform.PlatformIntegrationDescriptorDTO;
import com.example.hms.service.platform.discovery.IntegrationDescriptor;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PlatformIntegrationDescriptorMapper {

    public PlatformIntegrationDescriptorDTO toDto(IntegrationDescriptor descriptor) {
        if (descriptor == null) {
            return null;
        }

        List<String> capabilities = descriptor.getCapabilities() == null
            ? List.of()
            : descriptor.getCapabilities();

        return PlatformIntegrationDescriptorDTO.builder()
            .id(descriptor.getId())
            .serviceType(descriptor.getServiceType())
            .displayName(descriptor.getDisplayName())
            .description(descriptor.getDescription())
            .provider(descriptor.getProvider())
            .baseUrl(descriptor.getBaseUrl())
            .documentationUrl(descriptor.getDocumentationUrl())
            .sandboxUrl(descriptor.getSandboxUrl())
            .onboardingGuideUrl(descriptor.getOnboardingGuideUrl())
            .featureFlag(descriptor.getFeatureFlag())
            .enabled(descriptor.isEnabled())
            .autoProvision(descriptor.isAutoProvision())
            .managedByPlatform(descriptor.isManagedByPlatform())
            .capabilities(capabilities)
            .defaultOwnership(descriptor.getDefaultOwnership())
            .defaultMetadata(descriptor.getDefaultMetadata())
            .build();
    }

    public List<PlatformIntegrationDescriptorDTO> toDtoList(List<IntegrationDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return List.of();
        }
        return descriptors.stream()
            .filter(Objects::nonNull)
            .map(this::toDto)
            .toList();
    }
}
