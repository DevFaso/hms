package com.example.hms.service.platform.discovery;

import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.payload.dto.PlatformOwnershipDTO;
import com.example.hms.payload.dto.PlatformServiceMetadataDTO;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class IntegrationDescriptor {

    private final String id;

    private final PlatformServiceType serviceType;

    private final String displayName;

    private final String description;

    private final String provider;

    private final String baseUrl;

    private final String documentationUrl;

    private final String sandboxUrl;

    @Builder.Default
    private final List<String> capabilities = List.of();

    private final String onboardingGuideUrl;

    private final String featureFlag;

    private final boolean enabled;

    private final boolean autoProvision;

    private final boolean managedByPlatform;

    private final PlatformOwnershipDTO defaultOwnership;

    private final PlatformServiceMetadataDTO defaultMetadata;
}
