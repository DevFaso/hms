package com.example.hms.payload.dto;

import com.example.hms.enums.OrganizationType;
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
public class OrganizationResponseDTO {

    private UUID id;
    private String name;
    private String code;
    private String description;
    private OrganizationType type;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String primaryContactEmail;
    private String primaryContactPhone;
    private String defaultTimezone;
    private String onboardingNotes;
    
    private List<OrganizationSecurityPolicyResponseDTO> securityPolicies;
    private List<HospitalMinimalDTO> hospitals;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HospitalMinimalDTO {
        private UUID id;
        private String name;
        private String code;
        private String city;
        private boolean active;
    }
}