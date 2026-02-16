package com.example.hms.payload.dto;

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
public class HospitalPlatformServiceLinkResponseDTO {

    private UUID id;

    private UUID hospitalId;

    private String hospitalName;

    private UUID organizationServiceId;

    private PlatformServiceType serviceType;

    private boolean enabled;

    private String credentialsReference;

    private String overrideEndpoint;

    private PlatformOwnershipDTO ownership;
}
