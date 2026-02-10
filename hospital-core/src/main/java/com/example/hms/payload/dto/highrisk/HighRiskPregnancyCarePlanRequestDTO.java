package com.example.hms.payload.dto.highrisk;

import com.example.hms.enums.HighRiskMilestoneType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Request payload for creating or updating a high-risk pregnancy care plan.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HighRiskPregnancyCarePlanRequestDTO {

    @NotNull
    private UUID patientId;

    @NotNull
    private UUID hospitalId;

    @Valid
    private RiskProfileDTO riskProfile;

    @Valid
    private MonitoringPlanDTO monitoringPlan;

    @Valid
    private EducationPlanDTO educationPlan;

    @Valid
    private CareCoordinationDTO careCoordination;

    @Valid
    private SupportPlanDTO supportPlan;

    private Boolean active;

    @Size(max = 4000)
    private String overallNotes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskProfileDTO {
        private List<String> preexistingConditions;
        private List<String> pregnancyConditions;
        private List<String> lifestyleFactors;
        @Size(max = 60)
        private String riskLevel;
        @Size(max = 2000)
        private String riskNotes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonitoringPlanDTO {
        @Size(max = 150)
        private String visitCadence;
        @Size(max = 2000)
        private String homeMonitoringInstructions;
        @Size(max = 2000)
        private String medicationPlan;
        private LocalDate lastSpecialistReview;
        private List<MilestoneDTO> milestones;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MilestoneDTO {
        private UUID milestoneId;
        @NotNull
        private HighRiskMilestoneType type;
        private LocalDate targetDate;
        private Boolean completed;
        private LocalDate completedAt;
        @Size(max = 120)
        private String assignedTo;
        @Size(max = 240)
        private String location;
        @Size(max = 500)
        private String summary;
        @Size(max = 500)
        private String followUpActions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EducationPlanDTO {
        private List<String> preventiveGuidance;
        private List<String> emergencySymptoms;
        private List<EducationTopicDTO> topics;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EducationTopicDTO {
        @Size(max = 150)
        private String topic;
        @Size(max = 500)
        private String guidance;
        @Size(max = 500)
        private String materials;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CareCoordinationDTO {
        private List<CareTeamMemberDTO> careTeam;
        private List<CareTeamNoteDTO> communications;
        @Size(max = 500)
        private String coordinationNotes;
        @Size(max = 4000)
        private String deliveryRecommendations;
        @Size(max = 4000)
        private String escalationPlan;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CareTeamMemberDTO {
        @Size(max = 150)
        private String name;
        @Size(max = 120)
        private String role;
        @Size(max = 120)
        private String contact;
        @Size(max = 300)
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CareTeamNoteDTO {
        private UUID noteId;
        private LocalDateTime loggedAt;
        @Size(max = 120)
        private String author;
        @Size(max = 500)
        private String summary;
        @Size(max = 500)
        private String followUp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupportPlanDTO {
        private List<SupportResourceDTO> resources;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupportResourceDTO {
        @Size(max = 150)
        private String name;
        @Size(max = 120)
        private String type;
        @Size(max = 240)
        private String contact;
        @Size(max = 240)
        private String url;
        @Size(max = 300)
        private String notes;
    }
}
