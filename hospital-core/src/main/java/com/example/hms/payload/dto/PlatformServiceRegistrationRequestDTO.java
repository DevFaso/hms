package com.example.hms.payload.dto;

import com.example.hms.enums.platform.PlatformServiceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformServiceRegistrationRequestDTO {

    @NotNull(message = "{platformService.serviceType.required}")
    private PlatformServiceType serviceType;

    @Size(max = 120)
    private String provider;

    @Size(max = 255)
    private String baseUrl;

    @Size(max = 255)
    private String documentationUrl;

    @Size(max = 500)
    private String apiKeyReference;

    private Boolean managedByPlatform;

    @Valid
    private PlatformOwnershipDTO ownership;

    @Valid
    private PlatformServiceMetadataDTO metadata;
}
