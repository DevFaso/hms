package com.example.hms.payload.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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

    @Size(max = 500)
    private String credentialsReference;

    @Size(max = 255)
    private String overrideEndpoint;

    @Valid
    private PlatformOwnershipDTO ownership;
}
