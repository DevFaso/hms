package com.example.hms.payload.dto.superadmin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuperAdminPlatformRegistrySummaryDTO {

    private List<ModuleCardDTO> modules;
    private List<AutomationTaskDTO> automationTasks;
    private ActionPanelDTO actions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModuleCardDTO {
        private String title;
        private String description;
        private String meta;
        private long activeIntegrations;
        private long pendingIntegrations;
        private long managedIntegrations;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AutomationTaskDTO {
        private String id;
        private String title;
        private String description;
        private AutomationStatus status;
        private String statusLabel;
        private String metricLabel;
        private String metricValue;
        private String nextAction;
        private String lastRun;
    }

    public enum AutomationStatus {
        ON_TRACK,
        AT_RISK,
        BLOCKED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionPanelDTO {
        private long totalIntegrations;
        private long pendingIntegrations;
        private long disabledLinks;
        private long activeReleaseWindows;
        private String lastSnapshotGeneratedAt;
    }
}
