package com.example.hms.payload.dto.highrisk;

import com.example.hms.enums.HighRiskMilestoneType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response payload exposing the current high-risk pregnancy care plan state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HighRiskPregnancyCarePlanResponseDTO {

    private UUID id;
    private UUID patientId;
    private UUID hospitalId;
    private String patientDisplayName;
    private String patientMrn;
    private String patientPrimaryPhone;
    private String patientEmail;
    private LocalDate patientDateOfBirth;
    private String riskLevel;
    private String riskNotes;
    private boolean active;
    private LocalDate lastSpecialistReview;
    private String visitCadence;
    private String homeMonitoringInstructions;
    private String medicationPlan;
    private String coordinationNotes;
    private String deliveryRecommendations;
    private String escalationPlan;
    private String overallNotes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<String> preexistingConditions;
    private List<String> pregnancyConditions;
    private List<String> lifestyleFactors;
    private List<String> preventiveGuidance;
    private List<String> emergencySymptoms;
    private List<EducationTopicDTO> educationTopics;
    private List<CareTeamMemberDTO> careTeam;
    private List<SupportResourceDTO> supportResources;
    private List<CareTeamNoteDTO> communications;
    private List<MilestoneDTO> milestones;
    private List<BloodPressureLogDTO> bloodPressureLogs;
    private List<MedicationLogDTO> medicationLogs;
    private List<String> alerts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EducationTopicDTO {
        private String topic;
        private String guidance;
        private String materials;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CareTeamMemberDTO {
        private String name;
        private String role;
        private String contact;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupportResourceDTO {
        private String name;
        private String type;
        private String contact;
        private String url;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CareTeamNoteDTO {
        private UUID noteId;
        private LocalDateTime loggedAt;
        private String author;
        private String summary;
        private String followUp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MilestoneDTO {
        private UUID milestoneId;
        private HighRiskMilestoneType type;
        private LocalDate targetDate;
        private Boolean completed;
        private LocalDate completedAt;
        private String assignedTo;
        private String location;
        private String summary;
        private String followUpActions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BloodPressureLogDTO {
        private UUID logId;
        private LocalDate readingDate;
        private Integer systolic;
        private Integer diastolic;
        private Integer heartRate;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MedicationLogDTO {
        private UUID logId;
        private String medicationName;
        private String dosage;
        private Boolean taken;
        private LocalDateTime takenAt;
        private String notes;
    }
}
