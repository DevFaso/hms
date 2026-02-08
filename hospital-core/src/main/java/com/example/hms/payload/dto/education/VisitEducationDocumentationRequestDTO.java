package com.example.hms.payload.dto.education;

import com.example.hms.enums.EducationCategory;
import jakarta.validation.constraints.NotNull;
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
public class VisitEducationDocumentationRequestDTO {
    @NotNull(message = "Encounter ID is required")
    private UUID encounterId;
    
    @NotNull(message = "Patient ID is required")
    private UUID patientId;
    
    @NotNull(message = "Category is required")
    private EducationCategory category;
    
    @NotNull(message = "Topic discussed is required")
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
}
