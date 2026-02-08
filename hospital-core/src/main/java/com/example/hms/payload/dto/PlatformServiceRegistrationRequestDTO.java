package com.example.hms.payload.dto;

import com.example.hms.enums.platform.PlatformServiceType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformServiceRegistrationRequestDTO {

    @NotNull(message = "Platform service type is required")
    private PlatformServiceType serviceType;

    private String provider;

    private String baseUrl;

    private String documentationUrl;

    private String apiKeyReference;

    private Boolean managedByPlatform;

    private PlatformOwnershipDTO ownership;

    private PlatformServiceMetadataDTO metadata;
}
