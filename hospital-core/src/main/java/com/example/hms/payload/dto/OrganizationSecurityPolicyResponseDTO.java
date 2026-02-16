package com.example.hms.payload.dto;

import com.example.hms.enums.SecurityPolicyType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationSecurityPolicyResponseDTO {

    private UUID id;
    private String name;
    private String code;
    private String description;
    private SecurityPolicyType policyType;
    private Integer priority;
    private boolean active;
    private boolean enforceStrict;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    private UUID organizationId;
    private String organizationName;
    
    private List<OrganizationSecurityRuleResponseDTO> rules;
}