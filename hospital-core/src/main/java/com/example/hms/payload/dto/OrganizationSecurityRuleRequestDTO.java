package com.example.hms.payload.dto;

import com.example.hms.enums.SecurityRuleType;
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
public class OrganizationSecurityRuleRequestDTO {

    @NotBlank(message = "Rule name is required")
    @Size(max = 255, message = "Rule name must not exceed 255 characters")
    private String name;

    @NotBlank(message = "Rule code is required")
    @Size(max = 100, message = "Rule code must not exceed 100 characters")
    private String code;

    @Size(max = 1000, message = "Description must not exceed 1000 characters")
    private String description;

    @NotNull(message = "Rule type is required")
    private SecurityRuleType ruleType;

    @Size(max = 2000, message = "Rule value must not exceed 2000 characters")
    private String ruleValue;

    @NotNull(message = "Security policy ID is required")
    private UUID securityPolicyId;

    @Builder.Default
    private Integer priority = 0;

    @Builder.Default
    private boolean active = true;
}