package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformServiceLinkRequestDTO {

    private Boolean enabled;

    private String credentialsReference;

    private String overrideEndpoint;

    private PlatformOwnershipDTO ownership;
}
