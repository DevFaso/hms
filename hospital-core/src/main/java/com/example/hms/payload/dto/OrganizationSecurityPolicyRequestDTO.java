package com.example.hms.payload.dto;

import com.example.hms.enums.SecurityPolicyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationSecurityPolicyRequestDTO {

    @NotBlank(message = "{organizationSecurityPolicy.name.required}")
    @Size(max = 255, message = "{organizationSecurityPolicy.name.size}")
    private String name;

    @NotBlank(message = "{organizationSecurityPolicy.code.required}")
    @Size(max = 100, message = "{organizationSecurityPolicy.code.size}")
    private String code;

    @Size(max = 1000, message = "{organizationSecurityPolicy.description.size}")
    private String description;

    @NotNull(message = "{organizationSecurityPolicy.policyType.required}")
    private SecurityPolicyType policyType;

    @NotNull(message = "{organizationSecurityPolicy.organizationId.required}")
    private UUID organizationId;

    @Builder.Default
    private Integer priority = 0;

    @Builder.Default
    private boolean active = true;

    @Builder.Default
    private boolean enforceStrict = false;
}