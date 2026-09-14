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

    @NotBlank(message = "{organizationSecurityRule.name.required}")
    @Size(max = 255, message = "{organizationSecurityRule.name.size}")
    private String name;

    @NotBlank(message = "{organizationSecurityRule.code.required}")
    @Size(max = 100, message = "{organizationSecurityRule.code.size}")
    private String code;

    @Size(max = 1000, message = "{organizationSecurityRule.description.size}")
    private String description;

    @NotNull(message = "{organizationSecurityRule.ruleType.required}")
    private SecurityRuleType ruleType;

    @Size(max = 2000, message = "{organizationSecurityRule.ruleValue.size}")
    private String ruleValue;

    @NotNull(message = "{organizationSecurityRule.securityPolicyId.required}")
    private UUID securityPolicyId;

    @Builder.Default
    private Integer priority = 0;

    @Builder.Default
    private boolean active = true;
}