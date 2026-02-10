package com.example.hms.payload.dto;

import com.example.hms.enums.platform.PlatformServiceStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformServiceUpdateRequestDTO {

    private PlatformServiceStatus status;

    private String provider;

    private String baseUrl;

    private String documentationUrl;

    private String apiKeyReference;

    private Boolean managedByPlatform;

    private PlatformOwnershipDTO ownership;

    private PlatformServiceMetadataDTO metadata;
}
