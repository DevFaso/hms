package com.example.hms.payload.dto;

import com.example.hms.enums.SecurityRuleType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationSecurityRuleResponseDTO {

    private UUID id;
    private String name;
    private String code;
    private String description;
    private SecurityRuleType ruleType;
    private String ruleValue;
    private Integer priority;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    private UUID securityPolicyId;
    private String securityPolicyName;
}