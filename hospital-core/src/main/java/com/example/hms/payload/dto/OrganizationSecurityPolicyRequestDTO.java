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

    @NotBlank(message = "Policy name is required")
    @Size(max = 255, message = "Policy name must not exceed 255 characters")
    private String name;

    @NotBlank(message = "Policy code is required")
    @Size(max = 100, message = "Policy code must not exceed 100 characters")
    private String code;

    @Size(max = 1000, message = "Description must not exceed 1000 characters")
    private String description;

    @NotNull(message = "Policy type is required")
    private SecurityPolicyType policyType;

    @NotNull(message = "Organization ID is required")
    private UUID organizationId;

    @Builder.Default
    private Integer priority = 0;

    @Builder.Default
    private boolean active = true;

    @Builder.Default
    private boolean enforceStrict = false;
}