package com.example.hms.payload.dto;

import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformServiceResponseDTO {

    private UUID id;

    private UUID organizationId;

    private PlatformServiceType serviceType;

    private PlatformServiceStatus status;

    private String provider;

    private String baseUrl;

    private String documentationUrl;

    private String apiKeyReference;

    private boolean managedByPlatform;

    private PlatformOwnershipDTO ownership;

    private PlatformServiceMetadataDTO metadata;

    private int hospitalLinkCount;

    private int departmentLinkCount;
}
