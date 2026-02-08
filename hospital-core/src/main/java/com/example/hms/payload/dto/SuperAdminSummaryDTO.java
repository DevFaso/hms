package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuperAdminSummaryDTO {
    private long totalUsers;
    private long activeUsers;
    private long inactiveUsers;
    private long totalHospitals;
    private long activeHospitals;
    private long inactiveHospitals;
    private long totalPatients;
    private long totalRoles;
    private long totalAssignments;
    private long activeAssignments;
    private long inactiveAssignments;
    private long globalAssignments;
    private long activeGlobalAssignments;
    private LocalDateTime generatedAt;

    private List<RecentAuditEventDTO> recentAuditEvents;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentAuditEventDTO {
        private String id;
        private String eventType;
        private String status;
        private String entityType;
        private String resourceId;
        private String resourceName;
        private String userName;
        private String roleName;
        private String hospitalName;
        private LocalDateTime eventTimestamp;
        private String eventDescription;
    }
}
