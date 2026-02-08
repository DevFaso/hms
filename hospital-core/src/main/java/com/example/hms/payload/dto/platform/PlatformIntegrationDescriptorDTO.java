package com.example.hms.payload.dto.platform;

import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.payload.dto.PlatformOwnershipDTO;
import com.example.hms.payload.dto.PlatformServiceMetadataDTO;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformIntegrationDescriptorDTO {

    private String id;

    private PlatformServiceType serviceType;

    private String displayName;

    private String description;

    private String provider;

    private String baseUrl;

    private String documentationUrl;

    private String sandboxUrl;

    private String onboardingGuideUrl;

    private String featureFlag;

    private boolean enabled;

    private boolean autoProvision;

    private boolean managedByPlatform;

    @Builder.Default
    private List<String> capabilities = List.of();

    private PlatformOwnershipDTO defaultOwnership;

    private PlatformServiceMetadataDTO defaultMetadata;
}
