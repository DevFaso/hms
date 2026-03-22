package com.example.hms.payload.dto.superadmin;

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
public class TenantOnboardingStatusDTO {

    private UUID organizationId;
    private String organizationName;
    private String organizationCode;
    private int completedSteps;
    private int totalSteps;
    private List<OnboardingStep> steps;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OnboardingStep {
        private String key;
        private String label;
        private boolean completed;
        private String detail;
    }
}
