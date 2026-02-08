package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityComplianceResponseDTO {

    private UUID organizationId;
    private String organizationName;
    private boolean compliant;
    private List<String> violations;
    private Integer passwordMinLength;
    private Integer sessionTimeoutMinutes;
    private String apiRateLimit;
    private List<SecurityPolicySummary> policies;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SecurityPolicySummary {
        private String code;
        private String name;
        private boolean active;
        private boolean enforceStrict;
        private Integer rulesCount;
    }
}