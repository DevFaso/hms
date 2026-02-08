package com.example.hms.payload.dto.education;

import com.example.hms.enums.EducationCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisitEducationDocumentationResponseDTO {
    private UUID id;
    private UUID encounterId;
    private UUID patientId;
    private UUID staffId;
    private UUID hospitalId;
    private EducationCategory category;
    private String topicDiscussed;
    private String discussionNotes;
    private Set<UUID> resourcesProvided;
    private Boolean patientEngaged;
    private String patientQuestions;
    private String patientConcerns;
    private Boolean patientUnderstood;
    private String comprehensionNotes;
    private Boolean requiresFollowUp;
    private String followUpPlan;
    private LocalDateTime followUpScheduledFor;
    private Boolean nutritionDiscussed;
    private Boolean exerciseDiscussed;
    private Boolean breastfeedingDiscussed;
    private Boolean birthPlanDiscussed;
    private Boolean warningSignsDiscussed;
    private Boolean mentalHealthDiscussed;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
